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

package org.apache.ignite.internal.processors.cache.query.continuous;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.internal.managers.deployment.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.continuous.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import javax.cache.event.*;
import javax.cache.event.EventType;
import java.io.*;
import java.util.*;

import static org.apache.ignite.events.EventType.*;

/**
 * Continuous query handler.
 */
class CacheContinuousQueryHandler<K, V> implements GridContinuousHandler {
    /** */
    private static final long serialVersionUID = 0L;

    /** Cache name. */
    private String cacheName;

    /** Topic for ordered messages. */
    private Object topic;

    /** Local listener. */
    private transient CacheEntryUpdatedListener<K, V> locLsnr;

    /** Remote filter. */
    private CacheEntryEventFilter<K, V> rmtFilter;

    /** Deployable object for filter. */
    private DeployableObject rmtFilterDep;

    /** Internal flag. */
    private boolean internal;

    /** Notify existing flag. */
    private boolean notifyExisting;

    /** Old value required flag. */
    private boolean oldValRequired;

    /** Synchronous flag. */
    private boolean sync;

    /** Ignore expired events flag. */
    private boolean ignoreExpired;

    /** Task name hash code. */
    private int taskHash;

    /** Whether to skip primary check for REPLICATED cache. */
    private transient boolean skipPrimaryCheck;

    /**
     * Required by {@link Externalizable}.
     */
    public CacheContinuousQueryHandler() {
        // No-op.
    }

    /**
     * Constructor.
     *
     * @param cacheName Cache name.
     * @param topic Topic for ordered messages.
     * @param locLsnr Local listener.
     * @param rmtFilter Remote filter.
     * @param internal Internal flag.
     * @param notifyExisting Notify existing flag.
     * @param oldValRequired Old value required flag.
     * @param sync Synchronous flag.
     * @param ignoreExpired Ignore expired events flag.
     * @param skipPrimaryCheck Whether to skip primary check for REPLICATED cache.
     * @param taskHash Task name hash code.
     */
    public CacheContinuousQueryHandler(
        String cacheName,
        Object topic,
        CacheEntryUpdatedListener<K, V> locLsnr,
        CacheEntryEventFilter<K, V> rmtFilter,
        boolean internal,
        boolean notifyExisting,
        boolean oldValRequired,
        boolean sync,
        boolean ignoreExpired,
        int taskHash,
        boolean skipPrimaryCheck) {
        assert topic != null;
        assert locLsnr != null;

        this.cacheName = cacheName;
        this.topic = topic;
        this.locLsnr = locLsnr;
        this.rmtFilter = rmtFilter;
        this.internal = internal;
        this.notifyExisting = notifyExisting;
        this.oldValRequired = oldValRequired;
        this.sync = sync;
        this.ignoreExpired = ignoreExpired;
        this.taskHash = taskHash;
        this.skipPrimaryCheck = skipPrimaryCheck;
    }

    /** {@inheritDoc} */
    @Override public boolean isForEvents() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isForMessaging() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isForQuery() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean register(final UUID nodeId, final UUID routineId, final GridKernalContext ctx)
        throws IgniteCheckedException {
        assert nodeId != null;
        assert routineId != null;
        assert ctx != null;

        if (ctx.cache().internalCache(cacheName) == null)
            return false;

        if (locLsnr != null)
            ctx.resource().injectGeneric(locLsnr);

        if (rmtFilter != null)
            ctx.resource().injectGeneric(rmtFilter);

        final boolean loc = nodeId.equals(ctx.localNodeId());

        CacheContinuousQueryListener<K, V> lsnr = new CacheContinuousQueryListener<K, V>() {
            @Override public void onExecution() {
                if (ctx.event().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                    ctx.event().record(new CacheQueryExecutedEvent<>(
                        ctx.discovery().localNode(),
                        "Continuous query executed.",
                        EVT_CACHE_QUERY_EXECUTED,
                        CacheQueryType.CONTINUOUS,
                        cacheName,
                        null,
                        null,
                        null,
                        rmtFilter,
                        null,
                        nodeId,
                        taskName()
                    ));
                }
            }

            @Override public void onEntryUpdated(CacheContinuousQueryEvent<K, V> evt, boolean primary,
                boolean recordIgniteEvt) {
                if (ignoreExpired && evt.getEventType() == EventType.EXPIRED)
                    return;

                GridCacheContext<K, V> cctx = cacheContext(ctx);

                if (cctx.isReplicated() && !skipPrimaryCheck && !primary)
                    return;

                boolean notify = true;

                if (rmtFilter != null) {
                    CacheFlag[] f = cctx.forceLocalRead();

                    try {
                        notify = rmtFilter.evaluate(evt);
                    }
                    finally {
                        cctx.forceFlags(f);
                    }
                }

                if (notify) {
                    if (loc)
                        locLsnr.onUpdated(F.<CacheEntryEvent<? extends K, ? extends V>>asList(evt));
                    else {
                        try {
                            ClusterNode node = ctx.discovery().node(nodeId);

                            if (ctx.config().isPeerClassLoadingEnabled() && node != null &&
                                U.hasCache(node, cacheName)) {
                                evt.entry().prepareMarshal(cctx);

                                GridCacheDeploymentManager depMgr =
                                    ctx.cache().internalCache(cacheName).context().deploy();

                                depMgr.prepare(evt.entry());
                            }
                            else
                                evt.entry().prepareMarshal(cctx);

                            ctx.continuous().addNotification(nodeId, routineId, evt.entry(), topic, sync, true);
                        }
                        catch (IgniteCheckedException ex) {
                            U.error(ctx.log(getClass()), "Failed to send event notification to node: " + nodeId, ex);
                        }
                    }

                    if (recordIgniteEvt) {
                        ctx.event().record(new CacheQueryReadEvent<>(
                            ctx.discovery().localNode(),
                            "Continuous query executed.",
                            EVT_CACHE_QUERY_OBJECT_READ,
                            CacheQueryType.CONTINUOUS,
                            cacheName,
                            null,
                            null,
                            null,
                            rmtFilter,
                            null,
                            nodeId,
                            taskName(),
                            evt.getKey(),
                            evt.getValue(),
                            evt.getOldValue(),
                            null
                        ));
                    }
                }
            }

            @Override public void onUnregister() {
                if (rmtFilter instanceof CacheContinuousQueryFilterEx)
                    ((CacheContinuousQueryFilterEx)rmtFilter).onQueryUnregister();
            }

            @Override public boolean oldValueRequired() {
                return oldValRequired;
            }

            @Override public boolean notifyExisting() {
                return notifyExisting;
            }

            private String taskName() {
                return ctx.security().enabled() ? ctx.task().resolveTaskName(taskHash) : null;
            }
        };

        return manager(ctx).registerListener(routineId, lsnr, internal);
    }

    /** {@inheritDoc} */
    @Override public void onListenerRegistered(UUID routineId, GridKernalContext ctx) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void unregister(UUID routineId, GridKernalContext ctx) {
        assert routineId != null;
        assert ctx != null;

        manager(ctx).unregisterListener(internal, routineId);
    }

    /**
     * @param ctx Kernal context.
     * @return Continuous query manager.
     */
    private CacheContinuousQueryManager manager(GridKernalContext ctx) {
        return cacheContext(ctx).continuousQueries();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void notifyCallback(UUID nodeId, UUID routineId, Collection<?> objs, GridKernalContext ctx) {
        assert nodeId != null;
        assert routineId != null;
        assert objs != null;
        assert ctx != null;

        Collection<CacheContinuousQueryEntry> entries = (Collection<CacheContinuousQueryEntry>)objs;

        final GridCacheContext cctx = cacheContext(ctx);

        for (CacheContinuousQueryEntry e : entries) {
            GridCacheDeploymentManager depMgr = cctx.deploy();

            ClassLoader ldr = depMgr.globalLoader();

            if (ctx.config().isPeerClassLoadingEnabled()) {
                GridDeploymentInfo depInfo = e.deployInfo();

                if (depInfo != null) {
                    depMgr.p2pContext(nodeId, depInfo.classLoaderId(), depInfo.userVersion(), depInfo.deployMode(),
                        depInfo.participants(), depInfo.localDeploymentOwner());
                }
            }

            try {
                e.unmarshal(cctx, ldr);
            }
            catch (IgniteCheckedException ex) {
                U.error(ctx.log(getClass()), "Failed to unmarshal entry.", ex);
            }
        }

        final IgniteCache cache = cctx.kernalContext().cache().jcache(cctx.name());

        Iterable<CacheEntryEvent<? extends K, ? extends V>> evts = F.viewReadOnly(entries,
            new C1<CacheContinuousQueryEntry, CacheEntryEvent<? extends K, ? extends V>>() {
                @Override public CacheEntryEvent<? extends K, ? extends V> apply(CacheContinuousQueryEntry e) {
                    return new CacheContinuousQueryEvent<K, V>(cache, cctx, e);
                };
            }
        );

        locLsnr.onUpdated(evts);
    }

    /** {@inheritDoc} */
    @Override public void p2pMarshal(GridKernalContext ctx) throws IgniteCheckedException {
        assert ctx != null;
        assert ctx.config().isPeerClassLoadingEnabled();

        if (rmtFilter != null && !U.isGrid(rmtFilter.getClass()))
            rmtFilterDep = new DeployableObject(rmtFilter, ctx);
    }

    /** {@inheritDoc} */
    @Override public void p2pUnmarshal(UUID nodeId, GridKernalContext ctx) throws IgniteCheckedException {
        assert nodeId != null;
        assert ctx != null;
        assert ctx.config().isPeerClassLoadingEnabled();

        if (rmtFilterDep != null)
            rmtFilter = rmtFilterDep.unmarshal(nodeId, ctx);
    }

    /** {@inheritDoc} */
    @Nullable @Override public Object orderedTopic() {
        return topic;
    }

    /** {@inheritDoc} */
    @Override public GridContinuousHandler clone() {
        try {
            return (GridContinuousHandler)super.clone();
        }
        catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        U.writeString(out, cacheName);
        out.writeObject(topic);

        boolean b = rmtFilterDep != null;

        out.writeBoolean(b);

        if (b)
            out.writeObject(rmtFilterDep);
        else
            out.writeObject(rmtFilter);

        out.writeBoolean(internal);
        out.writeBoolean(notifyExisting);
        out.writeBoolean(oldValRequired);
        out.writeBoolean(sync);
        out.writeBoolean(ignoreExpired);
        out.writeInt(taskHash);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        cacheName = U.readString(in);
        topic = in.readObject();

        boolean b = in.readBoolean();

        if (b)
            rmtFilterDep = (DeployableObject)in.readObject();
        else
            rmtFilter = (CacheEntryEventFilter<K, V>)in.readObject();

        internal = in.readBoolean();
        notifyExisting = in.readBoolean();
        oldValRequired = in.readBoolean();
        sync = in.readBoolean();
        ignoreExpired = in.readBoolean();
        taskHash = in.readInt();
    }

    /**
     * @param ctx Kernal context.
     * @return Cache context.
     */
    private GridCacheContext<K, V> cacheContext(GridKernalContext ctx) {
        assert ctx != null;

        return ctx.cache().<K, V>internalCache(cacheName).context();
    }

    /**
     * Deployable object.
     */
    private static class DeployableObject implements Externalizable {
        /** */
        private static final long serialVersionUID = 0L;

        /** Serialized object. */
        private byte[] bytes;

        /** Deployment class name. */
        private String clsName;

        /** Deployment info. */
        private GridDeploymentInfo depInfo;

        /**
         * Required by {@link Externalizable}.
         */
        public DeployableObject() {
            // No-op.
        }

        /**
         * @param obj Object.
         * @param ctx Kernal context.
         * @throws IgniteCheckedException In case of error.
         */
        private DeployableObject(Object obj, GridKernalContext ctx) throws IgniteCheckedException {
            assert obj != null;
            assert ctx != null;

            Class cls = U.detectClass(obj);

            clsName = cls.getName();

            GridDeployment dep = ctx.deploy().deploy(cls, U.detectClassLoader(cls));

            if (dep == null)
                throw new IgniteDeploymentCheckedException("Failed to deploy object: " + obj);

            depInfo = new GridDeploymentInfoBean(dep);

            bytes = ctx.config().getMarshaller().marshal(obj);
        }

        /**
         * @param nodeId Node ID.
         * @param ctx Kernal context.
         * @return Deserialized object.
         * @throws IgniteCheckedException In case of error.
         */
        <T> T unmarshal(UUID nodeId, GridKernalContext ctx) throws IgniteCheckedException {
            assert ctx != null;

            GridDeployment dep = ctx.deploy().getGlobalDeployment(depInfo.deployMode(), clsName, clsName,
                depInfo.userVersion(), nodeId, depInfo.classLoaderId(), depInfo.participants(), null);

            if (dep == null)
                throw new IgniteDeploymentCheckedException("Failed to obtain deployment for class: " + clsName);

            return ctx.config().getMarshaller().unmarshal(bytes, dep.classLoader());
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeByteArray(out, bytes);
            U.writeString(out, clsName);
            out.writeObject(depInfo);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            bytes = U.readByteArray(in);
            clsName = U.readString(in);
            depInfo = (GridDeploymentInfo)in.readObject();
        }
    }
}
