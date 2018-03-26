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

package org.apache.ignite.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTxLocalAdapter;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxAdapter;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearTxLocal;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.mxbean.TransactionsMXBean;
import org.apache.ignite.transactions.TransactionState;

/**
 * Transactions MXBean implementation.
 */
public class TransactionsMXBeanImpl implements TransactionsMXBean {
    /** Grid kernal context. */
    private final GridKernalContextImpl gridKernalCtx;

    /**
     * @param ctx Context.
     */
    TransactionsMXBeanImpl(GridKernalContextImpl ctx) {
        this.gridKernalCtx = ctx;
    }

    /** {@inheritDoc} */
    @Override public Map<String, String> getAllLocalTxs() {
        return getLocalTxs(0);
    }

    /** {@inheritDoc} */
    @Override public Map<String, String> getLongRunningLocalTxs(final int duration) {
        return getLocalTxs(duration);
    }

    /**
     * @param duration Duration.
     */
    private Map<String, String> getLocalTxs(long duration) {
        final Collection<IgniteTxAdapter> transactions = localTransactions(duration);
        final Map<UUID, ClusterNode> nodes = nodes();

        final HashMap<String, String> res = new HashMap<>(transactions.size());

        for (IgniteTxAdapter transaction : transactions)
            res.put(transaction.xid().toString(), composeTx(nodes, transaction));

        return res;
    }

    /** {@inheritDoc} */
    @Override public void stopTransaction(String txId) {
        final Collection<IgniteTxAdapter> transactions = localTransactions(0);
        if (!F.isEmpty(txId))
            for (IgniteTxAdapter transaction : transactions)
                if (transaction.xid().toString().equals(txId)) {
                    if (transaction instanceof GridNearTxLocal)
                        ((GridNearTxLocal)transaction).proxy().close();
                    else
                        throw new RuntimeException("Cant't stop non-near transaction " + txId);
                    return;
                }
        throw new RuntimeException("Transaction with id " + txId + " is not found");
    }

    /**
     * @param nodes Nodes.
     * @param id Id.
     */
    private String composeNodeInfo(final Map<UUID, ClusterNode> nodes, final UUID id) {
        final ClusterNode node = nodes.get(id);
        if (node == null)
            return "";

        return String.format("%s %s",
            node.consistentId(),
            node.hostNames());
    }

    /**
     * @param nodes Nodes.
     * @param ids Ids.
     */
    private String composeNodeInfo(final Map<UUID, ClusterNode> nodes, final List<UUID> ids) {
        final StringBuilder builder = new StringBuilder();

        builder.append("[");

        String delim = "";

        for (UUID id : ids) {
            builder
                .append(delim)
                .append(composeNodeInfo(nodes, id));
            delim = ", ";
        }

        builder.append("]");

        return builder.toString();
    }

    /**
     * @param nodes Nodes.
     * @param tx Transaction.
     */
    private String composeTx(final Map<UUID, ClusterNode> nodes, final IgniteTxAdapter tx) {
        final UUID node = tx.nodeId();
        final UUID originating = tx.originatingNodeId();
        final TransactionState txState = tx.state();

        String topology = txState + ", ";

        if (!node.equals(originating))
            topology += "ORIGINATING: " + composeNodeInfo(nodes, tx.originatingNodeId()) + ", ";
        else
            topology += "NEAR, ";

        if (tx instanceof GridDhtTxLocalAdapter) {
            final List<UUID> primaryNodes;
            if (txState == TransactionState.PREPARING) {
                if (!(primaryNodes = ((GridDhtTxLocalAdapter)tx).dhtPrimaryNodes(nodes)).isEmpty())
                    topology += "PRIMARY: " + composeNodeInfo(nodes, primaryNodes) + ", ";
            }
        }

        final Long duration = System.currentTimeMillis() - tx.startTime();

        return topology + "DURATION: " + duration;
    }

    /**
     *
     */
    private Map<UUID, ClusterNode> nodes() {
        final Collection<ClusterNode> nodesColl = gridKernalCtx.config().getDiscoverySpi().getRemoteNodes();
        final ClusterNode locNode = gridKernalCtx.config().getDiscoverySpi().getLocalNode();
        final HashMap<UUID, ClusterNode> nodes = new HashMap<>(nodesColl.size() + 1);

        nodes.put(locNode.id(), locNode);

        if (F.isEmpty(nodesColl))
            return nodes;

        for (ClusterNode clusterNode : nodesColl)
            nodes.put(clusterNode.id(), clusterNode);

        return nodes;
    }

    /**
     *
     */
    private Collection<IgniteTxAdapter> localTransactions(long duration) {
        final long start = System.currentTimeMillis();
        IgniteClosure<IgniteInternalTx, IgniteTxAdapter> c = new IgniteClosure<IgniteInternalTx, IgniteTxAdapter>() {
            @Override public IgniteTxAdapter apply(IgniteInternalTx tx) {
                return ((IgniteTxAdapter)tx);
            }
        };

        IgnitePredicate<IgniteInternalTx> pred = new IgnitePredicate<IgniteInternalTx>() {
            @Override public boolean apply(IgniteInternalTx tx) {
                return tx.local() && start - tx.startTime() >= duration;
            }
        };

        return F.viewReadOnly(gridKernalCtx.cache().context().tm().activeTransactions(), c, pred);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(TransactionsMXBeanImpl.class, this);
    }

}


