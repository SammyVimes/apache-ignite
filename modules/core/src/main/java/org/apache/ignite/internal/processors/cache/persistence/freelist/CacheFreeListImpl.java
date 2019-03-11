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

package org.apache.ignite.internal.processors.cache.persistence.freelist;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.pagemem.wal.IgniteWriteAheadLogManager;
import org.apache.ignite.internal.processors.cache.persistence.DataRegion;
import org.apache.ignite.internal.processors.cache.persistence.DataRegionMetricsImpl;
import org.apache.ignite.internal.processors.cache.persistence.Storable;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseList;
import org.apache.ignite.internal.stat.IoStatisticsHolder;

/**
 * FreeList implementation for cache.
 */
public class CacheFreeListImpl extends AbstractFreeList<Storable> {
    /**
     * @param cacheId Cache id.
     * @param name Name.
     * @param regionMetrics Region metrics.
     * @param dataRegion Data region.
     * @param reuseList Reuse list.
     * @param wal Wal.
     * @param metaPageId Meta page id.
     * @param initNew Initialize new.
     */
    public CacheFreeListImpl(int cacheId, String name, DataRegionMetricsImpl regionMetrics, DataRegion dataRegion,
        ReuseList reuseList,
        IgniteWriteAheadLogManager wal, long metaPageId, boolean initNew) throws IgniteCheckedException {
        super(cacheId, name, regionMetrics, dataRegion, reuseList, wal, metaPageId, initNew);
    }

    /** {@inheritDoc} */
    @Override public void insertDataRow(Storable row, IoStatisticsHolder statHolder) throws IgniteCheckedException {
        super.insertDataRow(row, statHolder);

//        assert row.key().partition() == PageIdUtils.partId(row.link()) :
//            "Constructed a link with invalid partition ID [partId=" + row.key().partition() +
//            ", link=" + U.hexLong(row.link()) + ']';
    }
}
