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

package org.apache.ignite.internal.processors.cache.msgtimelogging;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTxPrepareResponse;
import org.apache.ignite.internal.processors.cache.distributed.dht.atomic.GridNearAtomicUpdateResponse;
import org.apache.ignite.internal.processors.metric.impl.HistogramMetric;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationMetricsListener;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpiMBean;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.transactions.Transaction;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_COMM_SPI_TIME_HIST_BOUNDS;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_ENABLE_MESSAGES_TIME_LOGGING;
import static org.apache.ignite.cache.CacheAtomicityMode.ATOMIC;
import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_ASYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.PRIMARY_SYNC;
import static org.apache.ignite.spi.communication.tcp.TcpCommunicationMetricsListener.DEFAULT_HIST_BOUNDS;
import static org.apache.ignite.transactions.TransactionConcurrency.PESSIMISTIC;
import static org.apache.ignite.transactions.TransactionIsolation.READ_COMMITTED;

/**
 * Tests for CommunicationSpi time metrics.
 */
public class CacheMessagesTimeLoggingTest extends GridCacheMessagesTimeLoggingAbstractTest {
    /** */
    public void testGridDhtTxPrepareRequestTimeLogging() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache = grid(0).cache(DEFAULT_CACHE_NAME);

        populateCache(cache);

        checkTimeLoggableMsgsConsistancy();
    }

    /**
     * Near node sends requests to primary node but gets responses from backup node
     * for atomic caches with full sync mode.
     * Time logging must be disabled for this case.
     */
    public void testAtomicFullSyncCache() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache0 = grid(0).createCache(new CacheConfiguration<Integer, Integer>()
                                                                            .setName("fs_cache")
                                                                            .setBackups(1)
                                                                            .setAtomicityMode(ATOMIC)
                                                                            .setWriteSynchronizationMode(FULL_SYNC));

        IgniteCache<Integer, Integer> cache1 = grid(0).createCache(new CacheConfiguration<Integer, Integer>()
                                                                            .setName("fa_cache")
                                                                            .setBackups(1)
                                                                            .setAtomicityMode(ATOMIC)
                                                                            .setWriteSynchronizationMode(FULL_ASYNC));

        populateCache(cache0);
        populateCache(cache1);

        TcpCommunicationSpiMBean mbean = mbean(0);

        Map<UUID, Map<String, HistogramMetric>> nodeMap = mbean.getOutMetricsByNodeByMsgClass();

        assertNotNull(nodeMap);

        int size = nodeMap.get(grid(1).localNode().id()).size();
        assertEquals("Unexpected nodeMap size: " + size, size, 2);

        checkTimeLoggableMsgsConsistancy();
    }

    /** */
    public void testGridNearAtomicUpdateLogging() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache0 = grid(0).createCache(new CacheConfiguration<Integer, Integer>()
                                                                            .setName("some_cache_0")
                                                                            .setBackups(1)
                                                                            .setAtomicityMode(ATOMIC)
                                                                            .setWriteSynchronizationMode(PRIMARY_SYNC));

        populateCache(cache0);

        checkTimeLoggableMsgsConsistancy();
    }

    /** */
    public void testTransactions() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache0 = grid(0).createCache(new CacheConfiguration<Integer, Integer>()
                                                                            .setName("some_cache_0")
                                                                            .setAtomicityMode(TRANSACTIONAL));

        try (Transaction tx = grid(0).transactions().txStart(PESSIMISTIC, READ_COMMITTED)) {
            populateCache(cache0);

            tx.commit();
        }

        checkTimeLoggableMsgsConsistancy();
    }

    /**
     * @throws Exception if failed to start grid.
     */
    public void testMetricBounds() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache = grid(0).cache(DEFAULT_CACHE_NAME);

        populateCache(cache);

        HistogramMetric metric = getMetric(0, 1, GridDhtTxPrepareResponse.class);

        assertNotNull(metric);

        assertEquals(DEFAULT_HIST_BOUNDS.length + 1, metric.value().length);

        // Checking custom metrics bound.
        System.setProperty(IGNITE_COMM_SPI_TIME_HIST_BOUNDS, "1,10,100");

        IgniteEx grid3 = startGrid(GRID_CNT);

        IgniteCache<Integer, Integer> cache3 = grid3.createCache(new CacheConfiguration<Integer, Integer>()
                                                                        .setName("cache3")
                                                                        .setBackups(2)
                                                                        .setBackups(GRID_CNT));

        cache3.put(1, 1);

        HistogramMetric metric3 = getMetric(GRID_CNT, 1, GridNearAtomicUpdateResponse.class);
        assertNotNull(metric3);

        assertEquals(4, metric3.value().length);

        // Checking invalid custom metrics bound.
        System.setProperty(IGNITE_COMM_SPI_TIME_HIST_BOUNDS, "wrong_val");

        IgniteEx grid4 = startGrid(GRID_CNT + 1);

        IgniteCache<Integer, Integer> cache4 = grid4.createCache(new CacheConfiguration<Integer, Integer>()
                                                                        .setName("cache4")
                                                                        .setBackups(2)
                                                                        .setBackups(GRID_CNT + 1));

        cache4.put(1, 1);

        HistogramMetric metric4 = getMetric(GRID_CNT + 1, 1, GridNearAtomicUpdateResponse.class);
        assertNotNull(metric4);

        assertEquals(DEFAULT_HIST_BOUNDS.length + 1, metric4.value().length);

        checkTimeLoggableMsgsConsistancy();
    }

    /**
     * @throws Exception if test failed.
     */
    public void testMetricClearOnNodeLeaving() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache = grid(0).cache(DEFAULT_CACHE_NAME);

        populateCache(cache);

        UUID leavingNodeId = grid(1).localNode().id();

        HistogramMetric metric = getMetric(grid(0).name(), leavingNodeId, GridDhtTxPrepareResponse.class);

        assertNotNull(metric);

        stopGrid(1);

        awaitPartitionMapExchange();

        HistogramMetric metricAfterNodeStop = getMetric(grid(0).name(), leavingNodeId, GridDhtTxPrepareResponse.class);

        assertNull(metricAfterNodeStop);

        checkTimeLoggableMsgsConsistancy();
    }

    /**
     * Tests metrics disabling
     */
    public void testDisabledMetric() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "not boolean value");

        startGrids(GRID_CNT);

        IgniteCache<Integer, Integer> cache = grid(0).cache(DEFAULT_CACHE_NAME);

        populateCache(cache);

        HistogramMetric metric = getMetric(0, 1, GridDhtTxPrepareResponse.class);

        assertNull("Metrics unexpectedly enabled", metric);
    }

    /**
     * Checks correctness of metrics values.
     */
    public void testAccuracy() throws Exception {
        System.setProperty(IGNITE_ENABLE_MESSAGES_TIME_LOGGING, "true");
        System.setProperty(IGNITE_COMM_SPI_TIME_HIST_BOUNDS, "1,100, 250, 350");

        startGrids(GRID_CNT);

        final int entriesNum = 5;
        final TcpCommunicationMetricsListener sml = new SleepingMetricsListener(300);
        final int targetNodeNum = 1;
        final int srcNodeNum = 0;

        GridTestUtils.setFieldValue(grid(targetNodeNum).configuration().getCommunicationSpi(), "metricsLsnr", sml);

        IgniteCache<Integer, Integer> cache = grid(srcNodeNum).cache(DEFAULT_CACHE_NAME);

        for (int i = 0; i < entriesNum; i++)
            cache.put(i, i);

        HistogramMetric metric = getMetric(srcNodeNum, targetNodeNum, GridDhtTxPrepareResponse.class);

        RecordingSpi spi = (RecordingSpi)grid(targetNodeNum).configuration().getCommunicationSpi();
        int respNum = spi.respClsMap.get((grid(srcNodeNum).localNode().id())).get(GridDhtTxPrepareResponse.class);

        assertNotNull(metric);

        long[] val = metric.value();
        assertEquals("Unexpected metric value: " + Arrays.toString(val) + "; RespNum=" + respNum, val[3], respNum);

        checkTimeLoggableMsgsConsistancy();
    }
}
