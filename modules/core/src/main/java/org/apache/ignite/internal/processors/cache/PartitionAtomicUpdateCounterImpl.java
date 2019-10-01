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

package org.apache.ignite.internal.processors.cache;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.util.GridEmptyIterator;
import org.apache.ignite.internal.util.GridLongList;
import org.jetbrains.annotations.Nullable;

/**
 * Partition update counter for non-tx scenarios without support for tracking missed updates.
 * Currently used for atomic, mixed tx-atomic and in-memory cache groups.
 * TODO FIXME https://issues.apache.org/jira/browse/IGNITE-11797
 */
public class PartitionAtomicUpdateCounterImpl implements PartitionUpdateCounter {
    /** Counter of applied updates in partition. */
    private final AtomicLong cntr = new AtomicLong();

    /**
     * Initial counter is set to update with max sequence number after WAL recovery.
     */
    private long initCntr;

    /** */
    private final CacheGroupContext grp;

    /** */
    private final int partId;

    /** */
    private final IgniteLogger log;

    public PartitionAtomicUpdateCounterImpl(CacheGroupContext grp, int partId) {
        this.grp = grp;
        this.partId = partId;
        if (grp != null)
            this.log = grp.shared().logger(getClass());
        else
            this.log = null;
    }

    /** {@inheritDoc} */
    @Override public void init(long initUpdCntr, @Nullable byte[] cntrUpdData) {
        cntr.set(initUpdCntr);

        initCntr = initUpdCntr;
    }

    /** {@inheritDoc} */
    @Override public long initial() {
        return initCntr;
    }

    /** {@inheritDoc} */
    @Override public long get() {
        return cntr.get();
    }

    /** {@inheritDoc} */
    @Override public long next() {
        return cntr.incrementAndGet();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("StatementWithEmptyBody")
    @Override public void update(long val) {
        long cur;

        while(val > (cur = cntr.get()) && !cntr.compareAndSet(cur, val));

        if (log != null)
            log.info("CNTR: set readyVer=" + grp.topology().topologyVersionFuture().initialVersion() +
                ", grpId=" + grp.groupId() +
                ", grpName=" + grp.name() +
                ", partId=" + partId +
                ", old=" + cur +
                ", new=" + val +
                ", updated=" + (val > cur));
    }

    /** {@inheritDoc} */
    @Override public boolean update(long start, long delta) {
        long cur, val = start + delta;

        while(true) {
            if (val <= (cur = cntr.get()))
                return false;

            if (cntr.compareAndSet(cur, val))
                return true;
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized void updateInitial(long start, long delta) {
        update(start + delta);

        initCntr = get();

        if (log != null)
            log.info("CNTR: initial readyVer=" + grp.topology().topologyVersionFuture().initialVersion() +
                ", grpId=" + grp.groupId() +
                ", grpName=" + grp.name() +
                ", partId=" + partId +
                ", initCntr=" + initCntr);
    }

    /** {@inheritDoc} */
    @Override public GridLongList finalizeUpdateCounters() {
        if (log != null)
            log.info("CNTR: finalize readyVer=" + grp.topology().topologyVersionFuture().initialVersion() +
                ", grpId=" + grp.groupId() +
                ", grpName=" + grp.name() +
                ", partId=" + partId +
                ", cntr=" + get());

        return new GridLongList();
    }

    /** {@inheritDoc} */
    @Override public long reserve(long delta) {
        return next(delta);
    }

    /** {@inheritDoc} */
    @Override public long next(long delta) {
        return cntr.getAndAdd(delta);
    }

    /** {@inheritDoc} */
    @Override public boolean sequential() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public @Nullable byte[] getBytes() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public synchronized void reset() {
        initCntr = 0;

        cntr.set(0);
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        PartitionAtomicUpdateCounterImpl cntr = (PartitionAtomicUpdateCounterImpl)o;

        return this.cntr.get() == cntr.cntr.get();
    }

    /** {@inheritDoc} */
    @Override public long reserved() {
        return get();
    }

    /** {@inheritDoc} */
    @Override public boolean empty() {
        return get() == 0;
    }

    /** {@inheritDoc} */
    @Override public Iterator<long[]> iterator() {
        return new GridEmptyIterator<>();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return "Counter [init=" + initCntr + ", val=" + get() + ']';
    }
}
