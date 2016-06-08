/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.transactions;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.jsr166.ThreadLocalRandom8;

import static org.apache.ignite.transactions.TransactionIsolation.REPEATABLE_READ;

/**
 *
 */
public class TxDeadlockDetectionNoHangsTest extends GridCommonAbstractTest {
    /** Nodes count. */
    private static final int NODES_CNT = 3;

    /** Cache. */
    private static final String CACHE = "cache";

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        CacheConfiguration ccfg = defaultCacheConfiguration();

        ccfg.setName(CACHE);
        ccfg.setCacheMode(CacheMode.PARTITIONED);
        ccfg.setBackups(1);
        ccfg.setNearConfiguration(null);

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        startGridsMultiThreaded(NODES_CNT);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testNoHangsPessimistic() throws Exception {
        doTest(TransactionConcurrency.PESSIMISTIC);
    }

    /**
     * @throws Exception If failed.
     */
    public void testNoHangsOptimistic() throws Exception {
        doTest(TransactionConcurrency.OPTIMISTIC);
    }

    /**
     * @param concurrency Concurrency.
     */
    private void doTest(final TransactionConcurrency concurrency) throws org.apache.ignite.IgniteCheckedException {
        final AtomicBoolean stop = new AtomicBoolean();

        IgniteInternalFuture<Long> restartFut = null;

        try {
            restartFut = GridTestUtils.runMultiThreadedAsync(new Runnable() {
                @Override public void run() {
                    while (!stop.get()) {
                        try {
                            U.sleep(500);

                            startGrid(NODES_CNT);

                            awaitPartitionMapExchange();

                            U.sleep(500);

                            stopGrid(NODES_CNT);
                        }
                        catch (Exception e) {
                            // No-op.
                        }
                    }
                }
            }, 1, "restart-thread");

            long stopTime = System.currentTimeMillis() + 2 * 60_000L;

            for (int i = 0; System.currentTimeMillis() < stopTime; i++) {
                log.info(">>> Iteration " + i);

                final AtomicInteger threadCnt = new AtomicInteger();

                IgniteInternalFuture<Long> fut = GridTestUtils.runMultiThreadedAsync(new Runnable() {
                    @Override public void run() {
                        int threadNum = threadCnt.getAndIncrement();

                        Ignite ignite = ignite(threadNum % NODES_CNT);

                        IgniteCache<Integer, Integer> cache = ignite.cache(CACHE);

                        try (Transaction tx = ignite.transactions().txStart(concurrency, REPEATABLE_READ, 500, 0)) {
                            ThreadLocalRandom8 rnd = ThreadLocalRandom8.current();

                            for (int i = 0; i < 50; i++) {
                                int key = rnd.nextInt(50);

                                if (log.isDebugEnabled()) {
                                    log.info(">>> Performs put [node=" + ((IgniteKernal)ignite).localNode() +
                                        ", tx=" + tx + ", key=" + key + ']');
                                }

                                cache.put(key, 0);
                            }

                            tx.commit();
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, NODES_CNT * 3, "tx-thread");

                fut.get();
            }
        }
        finally {
            stop.set(true);

            if (restartFut != null)
                restartFut.get();

            checkDetectionFuts();
        }
    }

    /**
     *
     */
    private void checkDetectionFuts() {
        for (int i = 0; i < NODES_CNT ; i++) {
            Ignite ignite = ignite(i);

            IgniteTxManager txMgr = ((IgniteKernal)ignite).context().cache().context().tm();

            ConcurrentMap<Long, TxDeadlockDetection.TxDeadlockFuture> futs =
                GridTestUtils.getFieldValue(txMgr, IgniteTxManager.class, "deadlockDetectFuts");

            assertTrue(futs.isEmpty());
        }
    }

}
