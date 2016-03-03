package com.yahoo.omid.examples;

/**
 * Copyright 2011-2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import com.yahoo.omid.transaction.HBaseOmidClientConfiguration;
import com.yahoo.omid.transaction.HBaseTransactionManager;
import com.yahoo.omid.transaction.RollbackException;
import com.yahoo.omid.transaction.TTable;
import com.yahoo.omid.transaction.Transaction;
import com.yahoo.omid.transaction.TransactionException;
import com.yahoo.omid.transaction.TransactionManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.yahoo.omid.tsoclient.OmidClientConfiguration.ConnType.DIRECT;

/**
 * ****************************************************************************************************************
 *
 *  Example code demonstrates client side instrumentation
 *
 * ****************************************************************************************************************
 *
 * Please @see{BasicExample} first
 */
public class InstrumentationExample {
    private static final Logger LOG = LoggerFactory.getLogger(InstrumentationExample.class);

    public static void main(String[] args) throws Exception {
        LOG.info("Parsing command line args...");
        String userTableName = "MY_TX_TABLE";
        if (args != null && args.length > 0 && StringUtils.isNotEmpty(args[0])) {
            userTableName = args[0];
        }
        byte[] family = Bytes.toBytes("MY_CF");
        if (args != null && args.length > 1 && StringUtils.isNotEmpty(args[1])) {
            family = Bytes.toBytes(args[1]);
        }
        LOG.info("Table '{}', column family '{}'", userTableName, Bytes.toString(family));

        //uses default Omid settings, see hbase-omid-client-config-default.yml and omid-client-config.yml
        new InstrumentationExample().doWork(userTableName, family, new HBaseOmidClientConfiguration());
        //to configure Omid settings in a file, place 'hbase-omid-client-config.yml into classpath
        // HBaseOmidClientConfiguration firsts looks for 'hbase-omid-client-config.yml' in the classpath and then
        // fallbacks to 'hbase-omid-client-config-default.yml'


        //configure Omid settings from code
        HBaseOmidClientConfiguration omidClientConfiguration = new HBaseOmidClientConfiguration();
        omidClientConfiguration.setRetryDelayMs(3000);
        omidClientConfiguration.setConnectionType(DIRECT);

        new InstrumentationExample().doWork(userTableName, family, omidClientConfiguration);
    }

    private void doWork(String userTableName, byte[] family, HBaseOmidClientConfiguration configuration)
            throws IOException, TransactionException, RollbackException {

        byte[] exampleRow1 = Bytes.toBytes("EXAMPLE_ROW1");
        byte[] exampleRow2 = Bytes.toBytes("EXAMPLE_ROW2");
        byte[] qualifier = Bytes.toBytes("MY_Q");
        byte[] dataValue1 = Bytes.toBytes("val1");
        byte[] dataValue2 = Bytes.toBytes("val2");

        LOG.info("Creating HBase Transaction Manager");
        TransactionManager tm = HBaseTransactionManager.newInstance(configuration);

        LOG.info("Creating access to Transactional Table '{}'", userTableName);
        try (TTable tt = new TTable(userTableName)) {
            for (int i = 0; i < 1000; i++) {
                Transaction tx = tm.begin();
                LOG.info("Transaction #{} {} STARTED", i, tx);

                Put row1 = new Put(exampleRow1);
                row1.add(family, qualifier, dataValue1);
                tt.put(tx, row1);
                LOG.info("Transaction {} writing value in [TABLE:ROW/CF/Q] => {}:{}/{}/{} = {} ",
                         tx, userTableName, Bytes.toString(exampleRow1), Bytes.toString(family),
                         Bytes.toString(qualifier), Bytes.toString(dataValue1));

                Put row2 = new Put(exampleRow2);
                row2.add(family, qualifier, dataValue2);
                tt.put(tx, row2);
                LOG.info("Transaction {} writing value in [TABLE:ROW/CF/Q] => {}:{}/{}/{} = {} ",
                         tx, userTableName, Bytes.toString(exampleRow2), Bytes.toString(family),
                         Bytes.toString(qualifier), Bytes.toString(dataValue2));

                tm.commit(tx);
                LOG.info("Transaction #{} {} COMMITTED", i, tx);
            }
        }

        tm.close();
    }

}
