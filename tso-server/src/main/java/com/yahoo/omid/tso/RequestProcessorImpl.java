package com.yahoo.omid.tso;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;

class RequestProcessorImpl
    implements EventHandler<RequestProcessorImpl.RequestEvent>, RequestProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestProcessorImpl.class);

    static final int DEFAULT_MAX_ITEMS = 1000000;
    static final String TSO_MAX_ITEMS_KEY = "tso.maxitems";

    private final TimestampOracle timestampOracle;
    public final CommitHashMap hashmap;
    private final PersistenceProcessor persistProc;
    private final RingBuffer<RequestEvent> requestRing;
    private long lowWatermark;

    @Inject
    RequestProcessorImpl(MetricRegistry metrics,
                         TimestampOracle timestampOracle,
                         PersistenceProcessor persistProc,
                         Panicker panicker,
                         TSOServerConfig config)
    {

        this.persistProc = persistProc;
        this.timestampOracle = timestampOracle;
        this.lowWatermark = timestampOracle.getLast();
        persistProc.persistLowWatermark(lowWatermark);

        this.hashmap = new CommitHashMap(config.getMaxItems(), lowWatermark);

        // Set up the disruptor thread
        requestRing = RingBuffer.<RequestEvent>createMultiProducer(RequestEvent.EVENT_FACTORY, 1<<12,
                                                                   new BusySpinWaitStrategy());
        SequenceBarrier requestSequenceBarrier = requestRing.newBarrier();
        BatchEventProcessor<RequestEvent> requestProcessor =
                new BatchEventProcessor<RequestEvent>(requestRing,
                                                      requestSequenceBarrier,
                                                      this);
        requestRing.addGatingSequences(requestProcessor.getSequence());
        requestProcessor.setExceptionHandler(new FatalExceptionHandler(panicker));

        ExecutorService requestExec = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder().setNameFormat("request-%d").build());
        // Each processor runs on a separate thread
        requestExec.submit(requestProcessor);
    }

    @Override
    public void onEvent(final RequestEvent event, final long sequence, final boolean endOfBatch)
        throws Exception
    {
        if (event.getType() == RequestEvent.Type.TIMESTAMP) {
            handleTimestamp(event.getChannel());
        } else if (event.getType() == RequestEvent.Type.COMMIT) {
            handleCommit(event.getStartTimestamp(), event.rows(), event.isRetry(), event.getChannel());
        }
    }

    @Override
    public void timestampRequest(Channel c) {
        long seq = requestRing.next();
        RequestEvent e = requestRing.get(seq);
        RequestEvent.makeTimestampRequest(e, c);
        requestRing.publish(seq);
    }

    @Override
    public void commitRequest(long startTimestamp, Collection<Long> rows, boolean isRetry, Channel c) {
        long seq = requestRing.next();
        RequestEvent e = requestRing.get(seq);
        RequestEvent.makeCommitRequest(e, startTimestamp, rows, isRetry, c);
        requestRing.publish(seq);
    }
    
    public void handleTimestamp(Channel c) {
        long timestamp;

        try {
            timestamp = timestampOracle.next();
        } catch (IOException e) {
            LOG.error("Error getting timestamp", e);
            return;
        }

        persistProc.persistTimestamp(timestamp, c);
    }

    public long handleCommit(long startTimestamp, Iterable<Long> rows, boolean isRetry, Channel c) {
        boolean committed = false;
        long commitTimestamp = 0L;
        
        int numRows = 0;
        // 0. check if it should abort
        if (startTimestamp <= lowWatermark) {
            committed = false;
        } else {
            // 1. check the write-write conflicts
            committed = true;
            for (long r : rows) {
                long value;
                value = hashmap.getLatestWriteForRow(r);
                if (value != 0 && value >= startTimestamp) {
                    committed = false;
                    break;
                }
                numRows++;
            }
        }

        if (committed) {
            // 2. commit
            try {
                commitTimestamp = timestampOracle.next();

                if (numRows > 0) {
                    long newLowWatermark = lowWatermark;

                    for (long r : rows) {
                        long removed = hashmap.putLatestWriteForRow(r, commitTimestamp);
                        newLowWatermark = Math.max(removed, newLowWatermark);
                    }

                    lowWatermark = newLowWatermark;
                    persistProc.persistLowWatermark(newLowWatermark);
                }
                persistProc.persistCommit(startTimestamp, commitTimestamp, c);
            } catch (IOException e) {
                LOG.error("Error committing", e);
            }
        } else { // add it to the aborted list
            persistProc.persistAbort(startTimestamp, isRetry, c);
        }

        return commitTimestamp;
    }

    final static class RequestEvent implements Iterable<Long> {
        enum Type {
            TIMESTAMP, COMMIT
        };
        private Type type = null;
        private Channel channel = null;

        private boolean isRetry = false;
        private long startTimestamp = 0;
        private long numRows = 0;

        private static final int MAX_INLINE = 40;
        private Long rows[] = new Long[MAX_INLINE];
        private Collection<Long> rowCollection = null; // for the case where there's more than MAX_INLINE

        static void makeTimestampRequest(RequestEvent e, Channel c) {
            e.type = Type.TIMESTAMP;
            e.channel = c;
        }

        // TODO Rename rows to cells
        static void makeCommitRequest(RequestEvent e,
                                      long startTimestamp, Collection<Long> rows, boolean isRetry, Channel c) {
            e.type = Type.COMMIT;
            e.channel = c;
            e.startTimestamp = startTimestamp;
            e.isRetry = isRetry;
            if (rows.size() > 40) {
                e.numRows = rows.size();
                e.rowCollection = rows;
            } else {
                e.rowCollection = null;
                e.numRows = rows.size();
                int i = 0;
                for (Long l : rows) {
                    e.rows[i] = l;
                    i++;
                }
            }
        }

        Type getType() {
            return type;
        }

        long getStartTimestamp() {
            return startTimestamp;
        }

        Channel getChannel() {
            return channel;
        }

        @Override
        public Iterator<Long> iterator() {
            if (rowCollection != null) {
                return rowCollection.iterator();
            }
            return new Iterator<Long>() {
                int i = 0;

                @Override
                public boolean hasNext() {
                    return i < numRows;
                }

                @Override
                public Long next() {
                    return rows[i++];
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        Iterable<Long> rows() {
            return this;
        }
        
        boolean isRetry() {
            return isRetry;
        }

        public final static EventFactory<RequestEvent> EVENT_FACTORY
            = new EventFactory<RequestEvent>()
        {
            public RequestEvent newInstance()
            {
                return new RequestEvent();
            }
        };
    }
};
