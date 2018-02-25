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

package org.apache.ignite.internal.processors.cache.tree;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.mvcc.MvccProcessor;
import org.apache.ignite.internal.processors.cache.mvcc.MvccSnapshot;
import org.apache.ignite.internal.processors.cache.mvcc.MvccVersion;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRowAdapter;
import org.apache.ignite.internal.processors.cache.persistence.CacheSearchRow;
import org.apache.ignite.internal.processors.cache.persistence.RowStore;
import org.apache.ignite.internal.processors.cache.persistence.freelist.CacheFreeList;
import org.apache.ignite.internal.processors.cache.tree.mvcc.data.MvccDataRow;
import org.apache.ignite.internal.util.typedef.internal.CU;

/**
 *
 */
public class CacheDataRowStore extends RowStore {
    /** */
    private final int partId;

    /** */
    private final CacheGroupContext grp;

    /** */
    private final CacheFreeList cacheFreeList;

    /**
     * @param grp Cache group.
     * @param freeList Free list.
     * @param partId Partition number.
     */
    public CacheDataRowStore(CacheGroupContext grp, CacheFreeList freeList, int partId) {
        super(grp, freeList);

        this.partId = partId;
        this.grp = grp;
        this.cacheFreeList = freeList;
    }

    /**
     * Marks row as removed.
     *
     * @param link Link.
     * @param newVer Remove operation version.
     * @throws IgniteCheckedException If failed.
     */
    public void mvccMarkRemoved(long link, MvccVersion newVer) throws IgniteCheckedException {
        if (!persistenceEnabled)
            cacheFreeList.mvccMarkRemoved(link, newVer);
        else {
            ctx.database().checkpointReadLock();

            try {
                cacheFreeList.mvccMarkRemoved(link, newVer);
            }
            finally {
                ctx.database().checkpointReadUnlock();
            }
        }
    }

    /**
     * @param cacheId Cache ID.
     * @param hash Hash code.
     * @param link Link.
     * @return Search row.
     */
    CacheSearchRow keySearchRow(int cacheId, int hash, long link) {
        DataRow dataRow = new DataRow(grp, hash, link, partId, CacheDataRowAdapter.RowData.KEY_ONLY);

        initDataRow(dataRow, cacheId);

        return dataRow;
    }

    /**
     * @param cacheId Cache ID.
     * @param hash Hash code.
     * @param link Link.
     * @param rowData Required row data.
     * @param crdVer Mvcc coordinator version.
     * @param mvccCntr Mvcc counter.
     * @return Search row.
     * @throws IgniteCheckedException If failed.
     */
    MvccDataRow mvccRow(int cacheId, int hash, long link, CacheDataRowAdapter.RowData rowData, long crdVer, long mvccCntr)
        throws IgniteCheckedException {
        MvccDataRow dataRow = new MvccDataRow(grp,
            hash,
            link,
            partId,
            rowData,
            crdVer,
            mvccCntr);

        initDataRow(dataRow, cacheId);

        return dataRow;
    }

    /**
     * Checks if new version available.
     *
     * @param io Row link IO.
     * @param pageAddr Page address.
     * @param idx Index.
     * @return {@code True} if a newer version is available for the given row.
     * @throws IgniteCheckedException If failed.
     */
    public boolean isRemoved(RowLinkIO io, long pageAddr, int idx)
        throws IgniteCheckedException {
        long link = io.getLink(pageAddr, idx);

        return MvccProcessor.isRemoved(grp, link);
    }

    /**
     * Checks if row is visible for snapshot.
     *
     * @param io Row link IO.
     * @param pageAddr Page address.
     * @param idx Index.
     * @return {@code True} if a newer version is available for the given row.
     * @throws IgniteCheckedException If failed.
     */
    public boolean isVisible(RowLinkIO io, long pageAddr, int idx, MvccSnapshot snapshot)
        throws IgniteCheckedException {
        long link = io.getLink(pageAddr, idx);

        return MvccProcessor.isVisible(grp, link, snapshot);
    }

    /**
     * @param dataRow Data row.
     * @param cacheId Cache ID.
     */
    private void initDataRow(DataRow dataRow, int cacheId) {
        if (dataRow.cacheId() == CU.UNDEFINED_CACHE_ID && grp.sharedGroup())
            dataRow.cacheId(cacheId);
    }

    /**
     * @param cacheId Cache ID.
     * @param hash Hash code.
     * @param link Link.
     * @param rowData Required row data.
     * @return Data row.
     */
    CacheDataRow dataRow(int cacheId, int hash, long link, CacheDataRowAdapter.RowData rowData) {
        DataRow dataRow = new DataRow(grp, hash, link, partId, rowData);

        if (dataRow.cacheId() == CU.UNDEFINED_CACHE_ID && grp.sharedGroup())
            dataRow.cacheId(cacheId);

        return dataRow;
    }
}
