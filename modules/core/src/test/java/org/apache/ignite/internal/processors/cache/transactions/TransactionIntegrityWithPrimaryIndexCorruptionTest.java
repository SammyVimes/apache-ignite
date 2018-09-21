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

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import junit.framework.AssertionFailedError;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteIllegalStateException;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.util.PageHandler;
import org.apache.ignite.internal.processors.cache.tree.SearchRow;
import org.apache.ignite.testframework.GridTestUtils;

import static org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState.OWNING;

/**
 * Test cases that check transaction data integrity after transaction commit failed.
 */
public class TransactionIntegrityWithPrimaryIndexCorruptionTest extends AbstractTransactionIntergrityTest {
    /** Corruption enabled flag. */
    private static volatile boolean corruptionEnabled;

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        corruptionEnabled = false;

        super.afterTest();
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryColocatedThrowsError() throws Exception {
        doTestTransferAmount0(true, true, () -> new AssertionError("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryColocatedThrowsUnchecked() throws Exception {
        doTestTransferAmount0(true, true, () -> new RuntimeException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryColocatedThrowsChecked() throws Exception {
        doTestTransferAmount0(true, true, () -> new IgniteCheckedException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryNonColocatedThrowsError() throws Exception {
        doTestTransferAmount0(false, true, () -> new AssertionError("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryNonColocatedThrowsUnchecked() throws Exception {
        doTestTransferAmount0(false, true, () -> new RuntimeException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodePrimaryNonColocatedThrowsChecked() throws Exception {
        doTestTransferAmount0(false, true, () -> new IgniteCheckedException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupColocatedThrowsError() throws Exception {
        doTestTransferAmount0(true, false, () -> new AssertionError("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupColocatedThrowsUnchecked() throws Exception {
        doTestTransferAmount0(true, false, () -> new RuntimeException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupColocatedThrowsChecked() throws Exception {
        doTestTransferAmount0(true, false, () -> new IgniteCheckedException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupNonColocatedThrowsError() throws Exception {
        doTestTransferAmount0(false, false, () -> new AssertionError("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupNonColocatedThrowsUnchecked() throws Exception {
        doTestTransferAmount0(false, false, () -> new RuntimeException("Test"));
    }

    /** */
    public void testPrimaryIndexCorruptionDuringCommitOnPrimaryNodeBackupNonColocatedThrowsChecked() throws Exception {
        doTestTransferAmount0(false, false, () -> new IgniteCheckedException("Test"));
    }

    /**
     * Creates failover predicate which generates error during transaction commmit.
     *
     * @param failOnPrimary If {@code true} index should be failed on transaction primary node, otherwise on backup.
     * @param errorSupplier Supplier to create various errors.
     * @param errorConsumer Consumer to track unexpected errors while committing.
     */
    private BiFunction<IgniteEx, SearchRow, Throwable> failoverPredicate(
        boolean failOnPrimary,
        Supplier<Throwable> errorSupplier,
        Consumer<Throwable> errorConsumer
    ) {
        return (ignite, row) -> {
            try {
                int cacheId = row.cacheId();
                int partId = row.key().partition();

                GridDhtPartitionTopology top = ignite.context().cache().cacheGroup(cacheId).topology();

                GridDhtLocalPartition part = top.localPartition(partId);

                assertTrue("Illegal partition state for mapped tx: " + part, part != null && part.state() == OWNING);

                return part.primary(top.readyTopologyVersion()) == failOnPrimary ? errorSupplier.get() : null;
            }
            catch (Throwable e) {
                errorConsumer.accept(e);

                throw e;
            }
        };
    }

    /**
     * Index corruption failover scenario.
     */
    class IndexCorruptionFailoverScenario implements FailoverScenario {
        /** Failed node index. */
        static final int failedNodeIdx = 1;

        /** Is node stopping expected after failover. */
        private final boolean nodeStoppingExp;

        /**
         * Predicate that will choose an instance of {@link BPlusTree} and page operation to make further failover in
         * this tree using {@link #failoverPred}.
         */
        private final BiFunction<PageHandler, BPlusTree, Boolean> treeCorruptionPred;

        /** Function that may return error during row insertion into {@link BPlusTree}. */
        private final BiFunction<IgniteEx, SearchRow, Throwable> failoverPred;

        /**
         * @param nodeStoppingExp Node stopping expected.
         * @param treeCorruptionPred Tree corruption predicate.
         * @param failoverPred Failover predicate.
         */
        IndexCorruptionFailoverScenario(
            boolean nodeStoppingExp,
            BiFunction<PageHandler, BPlusTree, Boolean> treeCorruptionPred,
            BiFunction<IgniteEx, SearchRow, Throwable> failoverPred
        ) {
            this.nodeStoppingExp = nodeStoppingExp;
            this.treeCorruptionPred = treeCorruptionPred;
            this.failoverPred = failoverPred;
        }

        /** {@inheritDoc} */
        @Override public void beforeNodesStarted() {
            BPlusTree.pageHndWrapper = (tree, hnd) -> {
                final IgniteEx locIgnite = (IgniteEx)Ignition.localIgnite();

                if (!locIgnite.name().endsWith(String.valueOf(failedNodeIdx)))
                    return hnd;

                if (treeCorruptionPred.apply(hnd, tree)) {
                    log.info("Created corrupted tree handler for -> " + hnd + " " + tree);

                    PageHandler<Object, BPlusTree.Result> delegate = (PageHandler<Object, BPlusTree.Result>)hnd;

                    return new PageHandler<BPlusTree.Get, BPlusTree.Result>() {
                        @Override public BPlusTree.Result run(int cacheId, long pageId, long page, long pageAddr, PageIO io,
                            Boolean walPlc, BPlusTree.Get arg, int lvl) throws IgniteCheckedException {
                            log.info("Invoked " + " " + cacheId + " " + arg.toString() + " for BTree (" + corruptionEnabled + ") -> " + arg.row() + " / " + arg.row().getClass());

                            if (corruptionEnabled && (arg.row() instanceof SearchRow)) {
                                SearchRow row = (SearchRow)arg.row();

                                // Store cacheId to search row explicitly, as it can be zero if there is one cache in a group.
                                Throwable res = failoverPred.apply(locIgnite, new SearchRow(cacheId, row.key()));

                                if (res != null) {
                                    if (res instanceof Error)
                                        throw (Error)res;
                                    else if (res instanceof RuntimeException)
                                        throw (RuntimeException)res;
                                    else if (res instanceof IgniteCheckedException)
                                        throw (IgniteCheckedException)res;
                                }
                            }

                            return delegate.run(cacheId, pageId, page, pageAddr, io, walPlc, arg, lvl);
                        }

                        @Override public boolean releaseAfterWrite(int cacheId, long pageId, long page, long pageAddr,
                            BPlusTree.Get g, int lvl) {
                            return g.canRelease(pageId, lvl);
                        }
                    };
                }

                return hnd;
            };
        }

        /** {@inheritDoc} */
        @Override public void afterFirstTransaction() {
            // Enable BPlus tree corruption after first transactions have finished.
            corruptionEnabled = true;
        }

        /** {@inheritDoc} */
        @Override public void afterTransactionsFinished() throws Exception {
            // Disable index corruption.
            BPlusTree.pageHndWrapper = (tree, hnd) -> hnd;

            if (nodeStoppingExp) {
                // Wait until node with corrupted index will left cluster.
                GridTestUtils.waitForCondition(() -> {
                    try {
                        grid(failedNodeIdx);
                    }
                    catch (IgniteIllegalStateException e) {
                        return true;
                    }

                    return false;
                }, getTestTimeout());

                // Failed node should be stopped.
                GridTestUtils.assertThrows(log, () -> grid(failedNodeIdx), IgniteIllegalStateException.class, "");

                // Re-start failed node.
                startGrid(failedNodeIdx);

                awaitPartitionMapExchange();
            }
        }
    }

    /**
     * Throws a test {@link RuntimeException} during tx commit from {@link BPlusTree} and checks after that data is
     * consistent.
     *
     * @param colocatedAccount Colocated account.
     * @param failOnPrimary Fail on primary.
     * @param supplier Supplier.
     */
    private void doTestTransferAmount0(boolean colocatedAccount, boolean failOnPrimary, Supplier<Throwable> supplier) throws Exception {
        ErrorTracker t = new ErrorTracker();

        doTestTransferAmount(
            new IndexCorruptionFailoverScenario(
                true,
                (hnd, tree) -> hnd instanceof BPlusTree.Search,
                failoverPredicate(failOnPrimary, supplier, t)),
            colocatedAccount
        );

        for (Throwable throwable : t.errors())
            log.error("Recorded error", throwable);

        if (!t.errors().isEmpty())
            fail("Test run has error");
    }

    /** */
    private static class ErrorTracker implements Consumer<Throwable> {
        /** Queue. */
        private final Queue<Throwable> q = new ConcurrentLinkedQueue<>();

        /** {@inheritDoc} */
        @Override public void accept(Throwable throwable) {
            q.add(throwable);
        }

        /**
         * @return Recorded errors.
         */
        public Collection<Throwable> errors() {
            return q;
        }
    }
}
