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

package org.apache.ignite.internal.processors.query;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.query.FieldsQueryCursor;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.managers.IgniteMBeansManager;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.cache.persistence.RootPage;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.query.schema.SchemaIndexCacheVisitor;
import org.apache.ignite.internal.util.GridAtomicLong;
import org.apache.ignite.internal.util.GridSpinBusyLock;
import org.apache.ignite.internal.util.lang.GridCloseableIterator;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.spi.indexing.IndexingQueryFilter;
import org.jetbrains.annotations.Nullable;

/**
 * Empty indexing class used in tests to simulate failures.
 */
@SuppressWarnings({"deprecation", "RedundantThrows"})
public class DummyQueryIndexing implements GridQueryIndexing {
    /** {@inheritDoc} */
    @Override public void start(GridKernalContext ctx, GridSpinBusyLock busyLock) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void stop() throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public SqlFieldsQuery generateFieldsQuery(String cacheName, SqlQuery qry) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public List<FieldsQueryCursor<List<?>>> querySqlFields(
        String schemaName,
        SqlFieldsQuery qry,
        SqlClientContext cliCtx,
        boolean keepBinary,
        boolean failOnMultipleStmts,
        GridQueryCancel cancel
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public FieldsQueryCursor<List<?>> queryLocalSqlFields(
        String schemaName,
        SqlFieldsQuery qry,
        boolean keepBinary,
        IndexingQueryFilter filter,
        GridQueryCancel cancel
    ) throws IgniteCheckedException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public boolean registerType(
        GridCacheContext cctx,
        GridQueryTypeDescriptor desc
    ) throws IgniteCheckedException {
        return false;
    }

    /** {@inheritDoc} */
    @Override public PreparedStatement prepareNativeStatement(String schemaName, String sql) throws SQLException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void checkStatementStreamable(PreparedStatement nativeStmt) {

    }

    /** {@inheritDoc} */
    @Override public long streamUpdateQuery(
        String schemaName,
        String qry,
        @Nullable Object[] params,
        IgniteDataStreamer<?, ?> streamer
    ) throws IgniteCheckedException {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public List<Long> streamBatchedUpdateQuery(
        String schemaName,
        String qry,
        List<Object[]> params,
        SqlClientContext cliCtx
    ) throws IgniteCheckedException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridCloseableIterator<IgniteBiTuple<K, V>> queryLocalText(
        String schemaName,
        String cacheName,
        String qry,
        String typeName,
        IndexingQueryFilter filter
    ) throws IgniteCheckedException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void dynamicIndexCreate(
        String schemaName,
        String tblName,
        QueryIndexDescriptorImpl idxDesc,
        boolean ifNotExists,
        SchemaIndexCacheVisitor cacheVisitor
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void dynamicIndexDrop(
        String schemaName,
        String idxName,
        boolean ifExists
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void dynamicAddColumn(
        String schemaName,
        String tblName,
        List<QueryField> cols,
        boolean ifTblExists,
        boolean ifColNotExists
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void dynamicDropColumn(
        String schemaName,
        String tblName,
        List<String> cols,
        boolean ifTblExists,
        boolean ifColExists
    ) throws IgniteCheckedException  {

    }

    
    /** {@inheritDoc} */
    @Override public void registerCache(
        String cacheName,
        String schemaName,
        GridCacheContext<?, ?> cacheInfo
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void unregisterCache(GridCacheContext cacheInfo, boolean rmvIdx) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void destroyOrphanIndex(
        RootPage page,
        String idxName,
        int grpId,
        PageMemory pageMemory,
        GridAtomicLong rmvId,
        ReuseList reuseList
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void store(
        GridCacheContext cctx,
        GridQueryTypeDescriptor type,
        CacheDataRow row,
        CacheDataRow prevRow,
        boolean prevRowAvailable
    ) throws IgniteCheckedException {

    }

    /** {@inheritDoc} */
    @Override public void remove(GridCacheContext cctx, GridQueryTypeDescriptor type, CacheDataRow row) {

    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> rebuildIndexesFromHash(GridCacheContext cctx) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void markAsRebuildNeeded(GridCacheContext cctx) {

    }

    /** {@inheritDoc} */
    @Override public IndexingQueryFilter backupFilter(AffinityTopologyVersion topVer, int[] parts) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void onDisconnected(IgniteFuture<?> reconnectFut) {

    }

    /** {@inheritDoc} */
    @Override public Collection<GridRunningQueryInfo> runningQueries(long duration) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void cancelQueries(Collection<Long> queries) {

    }

    /** {@inheritDoc} */
    @Override public void onKernalStop() {

    }

    /** {@inheritDoc} */
    @Override public String schema(String cacheName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public GridQueryRowCacheCleaner rowCacheCleaner(int cacheGrpId) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void registerMxBeans(IgniteMBeansManager mbMgr) {

    }
}