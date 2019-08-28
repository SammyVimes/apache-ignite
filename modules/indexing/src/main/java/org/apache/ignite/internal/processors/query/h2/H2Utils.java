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

package org.apache.ignite.internal.processors.query.h2;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.internal.processors.cache.QueryCursorImpl;
import org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode;
import org.apache.ignite.internal.processors.query.GridQueryFieldMetadata;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.processors.query.h2.opt.GridH2IndexBase;
import org.apache.ignite.internal.processors.query.h2.opt.GridH2RowDescriptor;
import org.apache.ignite.internal.processors.query.h2.opt.GridH2Table;
import org.apache.ignite.internal.util.GridStringBuilder;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.h2.engine.Session;
import org.h2.jdbc.JdbcConnection;
import org.h2.result.SortOrder;
import org.h2.table.IndexColumn;
import org.h2.util.JdbcUtils;
import org.h2.util.Utils;
import org.h2.value.DataType;
import org.h2.value.Value;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.query.h2.IgniteH2Indexing.UPDATE_RESULT_META;

/**
 * H2 utility methods.
 */
public class H2Utils {
    /**
     * The default precision for a char/varchar value.
     */
    public static final int STRING_DEFAULT_PRECISION = Integer.MAX_VALUE;

    /**
     * The default precision for a decimal value.
     */
    public static final int DECIMAL_DEFAULT_PRECISION = 65535;

    /**
     * The default scale for a decimal value.
     */
    public static final int DECIMAL_DEFAULT_SCALE = 32767;

    /** Spatial index class name. */
    private static final String SPATIAL_IDX_CLS =
        "org.apache.ignite.internal.processors.query.h2.opt.GridH2SpatialIndex";

    /** Quotation character. */
    private static final char ESC_CH = '\"';

    /**
     * @param c1 First column.
     * @param c2 Second column.
     * @return {@code true} If they are the same.
     */
    public static boolean equals(IndexColumn c1, IndexColumn c2) {
        return c1.column.getColumnId() == c2.column.getColumnId();
    }

    /**
     * @param cols Columns list.
     * @param col Column to find.
     * @return {@code true} If found.
     */
    public static boolean containsColumn(List<IndexColumn> cols, IndexColumn col) {
        for (int i = cols.size() - 1; i >= 0; i--) {
            if (equals(cols.get(i), col))
                return true;
        }

        return false;
    }

    /**
     * Check whether columns list contains key or key alias column.
     *
     * @param desc Row descriptor.
     * @param cols Columns list.
     * @return Result.
     */
    public static boolean containsKeyColumn(GridH2RowDescriptor desc, List<IndexColumn> cols) {
        for (int i = cols.size() - 1; i >= 0; i--) {
            if (desc.isKeyColumn(cols.get(i).column.getColumnId()))
                return true;
        }

        return false;
    }

    /**
     * Generate {@code CREATE INDEX} SQL statement for given params.
     * @param fullTblName Fully qualified table name.
     * @param h2Idx H2 index.
     * @param ifNotExists Quietly skip index creation if it exists.
     * @return Statement string.
     */
    public static String indexCreateSql(String fullTblName, GridH2IndexBase h2Idx, boolean ifNotExists) {
        boolean spatial = F.eq(SPATIAL_IDX_CLS, h2Idx.getClass().getName());

        GridStringBuilder sb = new SB("CREATE ")
            .a(spatial ? "SPATIAL " : "")
            .a("INDEX ")
            .a(ifNotExists ? "IF NOT EXISTS " : "")
            .a(withQuotes(h2Idx.getName()))
            .a(" ON ")
            .a(fullTblName)
            .a(" (");

        sb.a(indexColumnsSql(h2Idx.getIndexColumns()));

        sb.a(')');

        return sb.toString();
    }

    /**
     * Generate String represenation of given indexed columns.
     *
     * @param idxCols Indexed columns.
     * @return String represenation of given indexed columns.
     */
    public static String indexColumnsSql(IndexColumn[] idxCols) {
        GridStringBuilder sb = new SB();

        boolean first = true;

        for (IndexColumn col : idxCols) {
            if (first)
                first = false;
            else
                sb.a(", ");

            sb.a(withQuotes(col.columnName)).a(" ").a(col.sortType == SortOrder.ASCENDING ? "ASC" : "DESC");
        }

        return sb.toString();
    }

    /**
     * Generate {@code CREATE INDEX} SQL statement for given params.
     * @param schemaName <b>Quoted</b> schema name.
     * @param idxName Index name.
     * @param ifExists Quietly skip index drop if it exists.
     * @return Statement string.
     */
    public static String indexDropSql(String schemaName, String idxName, boolean ifExists) {
        return "DROP INDEX " + (ifExists ? "IF EXISTS " : "") + withQuotes(schemaName) + '.' + withQuotes(idxName);
    }

    /**
     * @param desc Row descriptor.
     * @param cols Columns list.
     * @param keyCol Primary key column.
     * @param affCol Affinity key column.
     * @return The same list back.
     */
    public static List<IndexColumn> treeIndexColumns(GridH2RowDescriptor desc, List<IndexColumn> cols,
        IndexColumn keyCol, IndexColumn affCol) {
        assert keyCol != null;

        if (!containsKeyColumn(desc, cols))
            cols.add(keyCol);

        if (affCol != null && !containsColumn(cols, affCol))
            cols.add(affCol);

        return cols;
    }

    /**
     * Create spatial index.
     *
     * @param tbl Table.
     * @param idxName Index name.
     * @param cols Columns.
     */
    public static GridH2IndexBase createSpatialIndex(GridH2Table tbl, String idxName, IndexColumn[] cols) {
        try {
            Class<?> cls = Class.forName(SPATIAL_IDX_CLS);

            Constructor<?> ctor = cls.getConstructor(
                GridH2Table.class,
                String.class,
                Integer.TYPE,
                IndexColumn[].class);

            if (!ctor.isAccessible())
                ctor.setAccessible(true);

            final int segments = tbl.rowDescriptor().context().config().getQueryParallelism();

            return (GridH2IndexBase)ctor.newInstance(tbl, idxName, segments, cols);
        }
        catch (Exception e) {
            throw new IgniteException("Failed to instantiate: " + SPATIAL_IDX_CLS, e);
        }
    }

    /**
     * Add quotes around the name.
     *
     * @param str String.
     * @return String with quotes.
     */
    public static String withQuotes(String str) {
        return ESC_CH + str + ESC_CH;
    }

    /**
     * @param rsMeta Metadata.
     * @return List of fields metadata.
     * @throws SQLException If failed.
     */
    public static List<GridQueryFieldMetadata> meta(ResultSetMetaData rsMeta) throws SQLException {
        List<GridQueryFieldMetadata> meta = new ArrayList<>(rsMeta.getColumnCount());

        for (int i = 1; i <= rsMeta.getColumnCount(); i++) {
            String schemaName = rsMeta.getSchemaName(i);
            String typeName = rsMeta.getTableName(i);
            String name = rsMeta.getColumnLabel(i);
            String type = rsMeta.getColumnClassName(i);

            if (type == null) // Expression always returns NULL.
                type = Void.class.getName();

            meta.add(new H2SqlFieldMetadata(schemaName, typeName, name, type));
        }

        return meta;
    }

    /**
     * @param c Connection.
     * @return Session.
     */
    public static Session session(Connection c) {
        return (Session)((JdbcConnection)c).getSession();
    }

    /**
     * @param conn Connection to use.
     * @param distributedJoins If distributed joins are enabled.
     * @param enforceJoinOrder Enforce join order of tables.
     */
    public static void setupConnection(Connection conn, boolean distributedJoins, boolean enforceJoinOrder) {
        setupConnection(conn,distributedJoins, enforceJoinOrder, false);
    }

    /**
     * @param conn Connection to use.
     * @param distributedJoins If distributed joins are enabled.
     * @param enforceJoinOrder Enforce join order of tables.
     * @param lazy Lazy query execution mode.
     */
    public static void setupConnection(
        Connection conn,
        boolean distributedJoins,
        boolean enforceJoinOrder,
        boolean lazy
    ) {
        Session s = session(conn);

        s.setForceJoinOrder(enforceJoinOrder);
        s.setJoinBatchEnabled(distributedJoins);
        s.setLazyQueryExecution(lazy);
    }

    /**
     * Convert value to column's expected type by means of H2.
     *
     * @param val Source value.
     * @param desc Row descriptor.
     * @param type Expected column type to convert to.
     * @return Converted object.
     * @throws IgniteCheckedException if failed.
     */
    public static Object convert(Object val, GridH2RowDescriptor desc, int type) throws IgniteCheckedException {
        if (val == null)
            return null;

        int objType = getTypeFromClass(val.getClass());

        if (objType == type)
            return val;

        Value h2Val = desc.wrap(val, objType);

        return h2Val.convertTo(type).getObject();
    }

    /**
     * Private constructor.
     */
    private H2Utils() {
        // No-op.
    }

    /**
     * @return Single-column, single-row cursor with 0 as number of updated records.
     */
    @SuppressWarnings("unchecked")
    public static QueryCursorImpl<List<?>> zeroCursor() {
        QueryCursorImpl<List<?>> resCur = (QueryCursorImpl<List<?>>)new QueryCursorImpl(Collections.singletonList
            (Collections.singletonList(0L)), null, false);

        resCur.fieldsMeta(UPDATE_RESULT_META);

        return resCur;
    }

    /**
     * Maps java class on H2's Value type that is supported by Ignite. <p/> Note that Ignite supports fewer types than
     * H2.
     *
     * @param x java class to map on H2's type.
     * @return H2 type if Ignite supports this type or {@link Value#JAVA_OBJECT} otherwise.
     * @see Value
     * @see DataType#getTypeFromClass(Class)
     */
    public static int getTypeFromClass(Class<?> x) {
        if (x == null || Void.TYPE == x)
            return Value.NULL;

        x = Utils.getNonPrimitiveClass(x);

        if (String.class == x)
            return Value.STRING;
        else if (Integer.class == x)
            return Value.INT;
        else if (Long.class == x)
            return Value.LONG;
        else if (Boolean.class == x)
            return Value.BOOLEAN;
        else if (Double.class == x)
            return Value.DOUBLE;
        else if (Byte.class == x)
            return Value.BYTE;
        else if (Short.class == x)
            return Value.SHORT;
        else if (Character.class == x)
            throw new IgniteSQLException("Character type is not supported.",
                IgniteQueryErrorCode.UNSUPPORTED_OPERATION);
        else if (Float.class == x)
            return Value.FLOAT;
        else if (byte[].class == x)
            return Value.BYTES;
        else if (UUID.class == x)
            return Value.UUID;
        else if (Void.class == x)
            return Value.NULL;
        else if (BigDecimal.class.isAssignableFrom(x))
            return Value.DECIMAL;
        else if (Date.class.isAssignableFrom(x))
            return Value.DATE;
        else if (Time.class.isAssignableFrom(x))
            return Value.TIME;
        else if (Timestamp.class.isAssignableFrom(x))
            return Value.TIMESTAMP;
        else if (java.util.Date.class.isAssignableFrom(x))
            return Value.TIMESTAMP;
        else if (Object[].class.isAssignableFrom(x)) // Array of any type.
            return Value.ARRAY;
        else if (QueryUtils.isGeometryClass(x))
            return Value.GEOMETRY;
        else {
            if (JdbcUtils.customDataTypesHandler != null)
                return JdbcUtils.customDataTypesHandler.getTypeIdFromClass(x);

            return Value.JAVA_OBJECT;
        }
    }

    /**
     * Binds parameters to prepared statement.
     *
     * @param stmt Prepared statement.
     * @param params Parameters collection.
     * @throws IgniteCheckedException If failed.
     */
    public static void bindParameters(PreparedStatement stmt,
        @Nullable Collection<Object> params) throws IgniteCheckedException {
        if (!F.isEmpty(params)) {
            int idx = 1;

            for (Object arg : params)
                bindObject(stmt, idx++, arg);
        }
    }

    /**
     * Binds object to prepared statement.
     *
     * @param stmt SQL statement.
     * @param idx Index.
     * @param obj Value to store.
     * @throws IgniteCheckedException If failed.
     */
    public static void bindObject(PreparedStatement stmt, int idx, @Nullable Object obj) throws IgniteCheckedException {
        try {
            if (obj == null)
                stmt.setNull(idx, Types.VARCHAR);
            else if (obj instanceof BigInteger)
                stmt.setObject(idx, obj, Types.JAVA_OBJECT);
            else if (obj instanceof BigDecimal)
                stmt.setObject(idx, obj, Types.DECIMAL);
            else if (obj.getClass() == Instant.class)
                stmt.setObject(idx, obj, Types.JAVA_OBJECT);
            else
                stmt.setObject(idx, obj);
        }
        catch (SQLException e) {
            throw new IgniteCheckedException("Failed to bind parameter [idx=" + idx + ", obj=" + obj + ", stmt=" +
                stmt + ']', e);
        }
    }
}
