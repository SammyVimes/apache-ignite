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

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.util.GridLongList;
import org.jetbrains.annotations.Nullable;

/**
 * Partition update counter.
 *
 * TODO FIXME consider rolling bit set implementation.
 * TODO describe ITEM structure
 * TODO add debugging info
 * TODO non-blocking version ? BitSets instead of TreeSet ?
 * TODO cleanup and comment interface
 * TODO implement gaps iterator.
 * TODO detailed description (javadoc) for counter contract.
 */
public interface PartitionUpdateCounter {
    /**
     * @param initUpdCntr Initialize upd counter.
     * @param rawGapsData Raw gaps data.
     */
    public void init(long initUpdCntr, @Nullable byte[] rawGapsData);

    /** */
    public long initial();

    /**
     * TODO rename to lwm.
     */
    public long get();

    /** */
    public long next();

    /**
     * @param delta Delta.
     */
    public long next(long delta);

    /**
     * @param delta Delta.
     */
    public long reserve(long delta);

    /**
     * TODO rename to hwm.
     */
    public long reserved();

    /**
     * @param val Value.
     *
     * @throws IgniteCheckedException if counter cannot be set to passed value due to incompatibility with current state.
     */
    public void update(long val) throws IgniteCheckedException;

    /**
     * @param start Start.
     * @param delta Delta.
     */
    public boolean update(long start, long delta);

    /**
     * Reset counter internal state to zero.
     */
    public void reset();

    /**
     * @param cntr Counter.
     */
    public void updateInitial(long cntr);

    /**
     * Flushes pending update counters closing all possible gaps.
     *
     * TODO FIXME should not be here (implement by gaps iterator + update(s, d)
     *
     * @return Even-length array of pairs [start, end] for each gap.
     */
    public GridLongList finalizeUpdateCounters();

    /** */
    public @Nullable byte[] getBytes();

    /**
     * @return {@code True} if counter has no missed updates.
     */
    public boolean sequential();
}
