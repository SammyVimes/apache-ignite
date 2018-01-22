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

package org.apache.ignite.cache.store.cassandra.session;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.cache.Cache;
import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.querybuilder.Batch;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.store.cassandra.common.CassandraHelper;
import org.apache.ignite.cache.store.cassandra.common.RandomSleeper;
import org.apache.ignite.cache.store.cassandra.persistence.KeyValuePersistenceSettings;
import org.apache.ignite.cache.store.cassandra.session.pool.SessionPool;
import org.apache.ignite.cache.store.cassandra.session.transaction.Mutation;
import org.apache.ignite.internal.processors.cache.CacheEntryImpl;

/**
 * Implementation for {@link org.apache.ignite.cache.store.cassandra.session.CassandraSession}.
 */
public class CassandraSessionImpl implements CassandraSession {
    /** Number of CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPTS_COUNT = 20;

    /** Min timeout between CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPT_MIN_TIMEOUT = 100;

    /** Max timeout between CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT = 500;

    /** Timeout increment for CQL query execution attempts. */
    private static final int CQL_ATTEMPTS_TIMEOUT_INCREMENT = 100;

    /** Cassandra cluster builder. */
    private volatile Cluster.Builder builder;

    /** Cassandra driver session. */
    private volatile Session ses;

    /** Number of references to Cassandra driver session (for multithreaded environment). */
    private volatile int refCnt;

    /** Storage for the session prepared statements */
    private static final Map<String, PreparedStatement> sesStatements = new HashMap<>();

    /** Number of records to immediately fetch in CQL statement execution. */
    private Integer fetchSize;

    /** Consistency level for Cassandra READ operations (select). */
    private ConsistencyLevel readConsistency;

    /** Consistency level for Cassandra WRITE operations (insert/update/delete). */
    private ConsistencyLevel writeConsistency;

    /** Expiration timeout. */
    private long expirationTimeout;

    /** Logger. */
    private IgniteLogger log;

    /** Table absence error handlers counter. */
    private final AtomicInteger tblAbsenceHandlersCnt = new AtomicInteger(-1);

    /** Prepared statement cluster disconnection error handlers counter. */
    private final AtomicInteger prepStatementHandlersCnt = new AtomicInteger(-1);

    /**
     * Creates instance of Cassandra driver session wrapper.
     *
     * @param builder Builder for Cassandra cluster.
     * @param fetchSize Number of rows to immediately fetch in CQL statement execution.
     * @param readConsistency Consistency level for Cassandra READ operations (select).
     * @param writeConsistency Consistency level for Cassandra WRITE operations (insert/update/delete).
     * @param log Logger.
     */
    public CassandraSessionImpl(Cluster.Builder builder, Integer fetchSize, ConsistencyLevel readConsistency,
        ConsistencyLevel writeConsistency, long expirationTimeout, IgniteLogger log) {
        this.builder = builder;
        this.fetchSize = fetchSize;
        this.readConsistency = readConsistency;
        this.writeConsistency = writeConsistency;
        this.expirationTimeout = expirationTimeout;
        this.log = log;
    }

    /** {@inheritDoc} */
    @Override public <V> V execute(ExecutionAssistant<V> assistant) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to execute Cassandra CQL statement: " + assistant.getStatement();

        RandomSleeper sleeper = newSleeper();

        incrementSessionRefs();

        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                error = null;

                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra CQL statement: " +
                            assistant.getStatement());
                }

                try {
                    PreparedStatement preparedSt = prepareStatement(assistant.getTable(), assistant.getStatement(),
                        assistant.getPersistenceSettings(), assistant.tableExistenceRequired());

                    if (preparedSt == null)
                        return null;

                    Statement statement = tuneStatementExecutionOptions(assistant.bindStatement(preparedSt));
                    ResultSet res = session().execute(statement);

                    Row row = res == null || !res.iterator().hasNext() ? null : res.iterator().next();

                    return row == null ? null : assistant.process(row);
                }
                catch (Throwable e) {
                    error = e;

                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (!assistant.tableExistenceRequired()) {
                            log.warning(errorMsg, e);
                            return null;
                        }

                        handleTableAbsenceError(assistant.getTable(), assistant.getPersistenceSettings());
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e))
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    else if (CassandraHelper.isPreparedStatementClusterError(e))
                        handlePreparedStatementClusterError(e);
                    else
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                }

                if (!CassandraHelper.isTableAbsenceError(error))
                    sleeper.sleep();

                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }

        log.error(errorMsg, error);

        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public <R, V> R execute(BatchExecutionAssistant<R, V> assistant, Iterable<? extends V> data) {
        if (data == null || !data.iterator().hasNext())
            return assistant.processedData();

        int attempt = 0;
        String errorMsg = "Failed to execute Cassandra " + assistant.operationName() + " operation";
        Throwable error = new IgniteException(errorMsg);

        RandomSleeper sleeper = newSleeper();

        int dataSize = 0;

        incrementSessionRefs();

        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra batch " +
                            assistant.operationName() + " operation to process rest " +
                            (dataSize - assistant.processedCount()) + " of " + dataSize + " elements");
                }

                //clean errors info before next communication with Cassandra
                Throwable unknownEx = null;
                Throwable tblAbsenceEx = null;
                Throwable hostsAvailEx = null;
                Throwable prepStatEx = null;

                List<Cache.Entry<Integer, ResultSetFuture>> futResults = new LinkedList<>();

                PreparedStatement preparedSt = prepareStatement(assistant.getTable(), assistant.getStatement(),
                    assistant.getPersistenceSettings(), assistant.tableExistenceRequired());

                if (preparedSt == null)
                    return null;

                int seqNum = 0;

                for (V obj : data) {
                    if (!assistant.alreadyProcessed(seqNum)) {
                        try {
                            Statement statement = tuneStatementExecutionOptions(assistant.bindStatement(preparedSt, obj));
                            ResultSetFuture fut = session().executeAsync(statement);
                            futResults.add(new CacheEntryImpl<>(seqNum, fut));
                        }
                        catch (Throwable e) {
                            if (CassandraHelper.isTableAbsenceError(e)) {
                                // If there are table absence error and it is not required for the operation we can return.
                                if (!assistant.tableExistenceRequired())
                                    return assistant.processedData();

                                tblAbsenceEx = e;
                                handleTableAbsenceError(assistant.getTable(), assistant.getPersistenceSettings());
                            }
                            else if (CassandraHelper.isHostsAvailabilityError(e)) {
                                hostsAvailEx = e;

                                // Handle host availability only once.
                                if (hostsAvailEx == null)
                                    handleHostsAvailabilityError(e, attempt, errorMsg);
                            }
                            else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                                prepStatEx = e;
                                handlePreparedStatementClusterError(e);
                                preparedSt = prepareStatement(assistant.getTable(), assistant.getStatement(),
                                        assistant.getPersistenceSettings(), assistant.tableExistenceRequired());
                            }
                            else
                                unknownEx = e;
                        }
                    }

                    seqNum++;
                }

                dataSize = seqNum;

                // For an error which we don't know how to handle, we will not try next attempts and terminate.
                if (unknownEx != null)
                    throw new IgniteException(errorMsg, unknownEx);

                // Remembering any of last errors.
                if (tblAbsenceEx != null)
                    error = tblAbsenceEx;
                else if (hostsAvailEx != null)
                    error = hostsAvailEx;
                else if (prepStatEx != null)
                    error = prepStatEx;
                
                // Clean errors info before next communication with Cassandra.
                unknownEx = null;
                tblAbsenceEx = null;
                hostsAvailEx = null;
                prepStatEx = null;

                for (Cache.Entry<Integer, ResultSetFuture> futureResult : futResults) {
                    try {
                        ResultSet resSet = futureResult.getValue().getUninterruptibly();
                        Row row = resSet != null && resSet.iterator().hasNext() ? resSet.iterator().next() : null;

                        if (row != null)
                            assistant.process(row, futureResult.getKey());
                    }
                    catch (Throwable e) {
                        if (CassandraHelper.isTableAbsenceError(e))
                            tblAbsenceEx = e;
                        else if (CassandraHelper.isHostsAvailabilityError(e))
                            hostsAvailEx = e;
                        else if (CassandraHelper.isPreparedStatementClusterError(e))
                            prepStatEx = e;
                        else
                            unknownEx = e;
                    }
                }

                // For an error which we don't know how to handle, we will not try next attempts and terminate.
                if (unknownEx != null)
                    throw new IgniteException(errorMsg, unknownEx);

                // If there are no errors occurred it means that operation successfully completed and we can return.
                if (tblAbsenceEx == null && hostsAvailEx == null && prepStatEx == null && assistant.processedCount() == dataSize)
                    return assistant.processedData();

                if (tblAbsenceEx != null) {
                    // If there are table absence error and it is not required for the operation we can return.
                    if (!assistant.tableExistenceRequired())
                        return assistant.processedData();

                    error = tblAbsenceEx;
                    handleTableAbsenceError(assistant.getTable(), assistant.getPersistenceSettings());
                }

                if (hostsAvailEx != null) {
                    error = hostsAvailEx;
                    handleHostsAvailabilityError(hostsAvailEx, attempt, errorMsg);
                }

                if (prepStatEx != null) {
                    error = prepStatEx;
                    handlePreparedStatementClusterError(prepStatEx);
                }

                if (!CassandraHelper.isTableAbsenceError(error))
                    sleeper.sleep();

                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }

        errorMsg = "Failed to process " + (dataSize - assistant.processedCount()) +
            " of " + dataSize + " elements, during " + assistant.operationName() +
            " operation with Cassandra";

        log.error(errorMsg, error);

        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public void execute(BatchLoaderAssistant assistant) {
        int attempt = 0;
        String errorMsg = "Failed to execute Cassandra " + assistant.operationName() + " operation";
        Throwable error = new IgniteException(errorMsg);

        RandomSleeper sleeper = newSleeper();

        incrementSessionRefs();

        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                if (attempt != 0)
                    log.warning("Trying " + (attempt + 1) + " attempt to load Ignite cache");

                Statement statement = tuneStatementExecutionOptions(assistant.getStatement());

                try {
                    ResultSetFuture fut = session().executeAsync(statement);
                    ResultSet resSet = fut.getUninterruptibly();

                    if (resSet == null || !resSet.iterator().hasNext())
                        return;

                    for (Row row : resSet)
                        assistant.process(row);

                    return;
                }
                catch (Throwable e) {
                    error = e;

                    if (CassandraHelper.isTableAbsenceError(e))
                        return;
                    else if (CassandraHelper.isHostsAvailabilityError(e))
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    else if (CassandraHelper.isPreparedStatementClusterError(e))
                        handlePreparedStatementClusterError(e);
                    else
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                }

                sleeper.sleep();

                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }

        log.error(errorMsg, error);

        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public void execute(List<Mutation> mutations) {
        if (mutations == null || mutations.isEmpty())
            return;

        Throwable error = null;
        String errorMsg = "Failed to apply " + mutations.size() + " mutations performed withing Ignite " +
                "transaction into Cassandra";

        int attempt = 0;
        boolean tableExistenceRequired = false;
        Map<String, PreparedStatement> statements = new HashMap<>();
        Map<String, KeyValuePersistenceSettings> tableSettings = new HashMap<>();
        RandomSleeper sleeper = newSleeper();

        incrementSessionRefs();

        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                error = null;

                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to apply " + mutations.size() + " mutations " +
                            "performed withing Ignite transaction into Cassandra");
                }

                try {
                    BatchStatement batch = new BatchStatement();

                    // accumulating all the mutations into one Cassandra logged batch
                    for (Mutation mutation : mutations) {
                        String key = mutation.getTable() + mutation.getClass().getName();
                        PreparedStatement st = statements.get(key);

                        if (st == null) {
                            st = prepareStatement(mutation.getTable(), mutation.getStatement(),
                                    mutation.getPersistenceSettings(), mutation.tableExistenceRequired());

                            if (st != null)
                                statements.put(key, st);
                        }

                        if (st != null)
                            batch.add(mutation.bindStatement(st));

                        if (attempt == 0) {
                            if (mutation.tableExistenceRequired()) {
                                tableExistenceRequired = true;

                                if (!tableSettings.containsKey(mutation.getTable()))
                                    tableSettings.put(mutation.getTable(), mutation.getPersistenceSettings());
                            }
                        }
                    }

                    // committing logged batch into Cassandra
                    if (batch.size() > 0)
                        session().execute(tuneStatementExecutionOptions(batch));

                    return;
                } catch (Throwable e) {
                    error = e;

                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (tableExistenceRequired) {
                            for (Map.Entry<String, KeyValuePersistenceSettings> entry : tableSettings.entrySet())
                                handleTableAbsenceError(entry.getKey(), entry.getValue());
                        }
                        else
                            return;
                    } else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        if (handleHostsAvailabilityError(e, attempt, errorMsg))
                            statements.clear();
                    } else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                        handlePreparedStatementClusterError(e);
                        statements.clear();
                    } else {
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                    }
                }

                if (!CassandraHelper.isTableAbsenceError(error))
                    sleeper.sleep();

                attempt++;
            }
        } catch (Throwable e) {
            error = e;
        } finally {
            decrementSessionRefs();
        }

        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public synchronized void close() throws IOException {
        if (decrementSessionRefs() == 0 && ses != null) {
            SessionPool.put(this, ses, expirationTimeout);

            ses = null;
        }
    }

    /**
     * Recreates Cassandra driver session.
     */
    private synchronized void refresh() {
        //make sure that session removed from the pool
        SessionPool.get(this);

        //closing and reopening session
        CassandraHelper.closeSession(ses);
        ses = null;
        session();

        synchronized (sesStatements) {
            sesStatements.clear();
        }
    }

    /**
     * @return Cassandra driver session.
     */
    private synchronized Session session() {
        if (ses != null)
            return ses;

        ses = SessionPool.get(this);

        if (ses != null)
            return ses;

        synchronized (sesStatements) {
            sesStatements.clear();
        }

        try {
            return ses = builder.build().connect();
        }
        catch (Throwable e) {
            throw new IgniteException("Failed to establish session with Cassandra database", e);
        }
    }

    /**
     * Increments number of references to Cassandra driver session (required for multithreaded environment).
     */
    private synchronized void incrementSessionRefs() {
        refCnt++;
    }

    /**
     * Decrements number of references to Cassandra driver session (required for multithreaded environment).
     */
    private synchronized int decrementSessionRefs() {
        if (refCnt != 0)
            refCnt--;

        return refCnt;
    }

    /**
     * Prepares CQL statement using current Cassandra driver session.
     *
     * @param statement CQL statement.
     * @param settings Persistence settings.
     * @param tblExistenceRequired Flag indicating if table existence is required for the statement.
     * @return Prepared statement.
     */
    private PreparedStatement prepareStatement(String table, String statement, KeyValuePersistenceSettings settings,
        boolean tblExistenceRequired) {

        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to prepare Cassandra CQL statement: " + statement;

        RandomSleeper sleeper = newSleeper();

        incrementSessionRefs();

        try {
            synchronized (sesStatements) {
                if (sesStatements.containsKey(statement))
                    return sesStatements.get(statement);
            }

            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                try {
                    PreparedStatement prepStatement = session().prepare(statement);

                    synchronized (sesStatements) {
                        sesStatements.put(statement, prepStatement);
                    }

                    return prepStatement;
                }
                catch (Throwable e) {
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (!tblExistenceRequired)
                            return null;

                        handleTableAbsenceError(table, settings);
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e))
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    else
                        throw new IgniteException(errorMsg, e);

                    error = e;
                }

                if (!CassandraHelper.isTableAbsenceError(error))
                    sleeper.sleep();

                attempt++;
            }
        }
        finally {
            decrementSessionRefs();
        }

        throw new IgniteException(errorMsg, error);
    }

    /**
     * Creates Cassandra keyspace.
     *
     * @param settings Persistence settings.
     */
    private void createKeyspace(KeyValuePersistenceSettings settings) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to create Cassandra keyspace '" + settings.getKeyspace() + "'";

        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                log.info("-----------------------------------------------------------------------");
                log.info("Creating Cassandra keyspace '" + settings.getKeyspace() + "'");
                log.info("-----------------------------------------------------------------------\n\n" +
                    settings.getKeyspaceDDLStatement() + "\n");
                log.info("-----------------------------------------------------------------------");
                session().execute(settings.getKeyspaceDDLStatement());
                log.info("Cassandra keyspace '" + settings.getKeyspace() + "' was successfully created");
                return;
            }
            catch (AlreadyExistsException ignored) {
                log.info("Cassandra keyspace '" + settings.getKeyspace() + "' already exist");
                return;
            }
            catch (Throwable e) {
                if (!CassandraHelper.isHostsAvailabilityError(e))
                    throw new IgniteException(errorMsg, e);

                handleHostsAvailabilityError(e, attempt, errorMsg);

                error = e;
            }

            attempt++;
        }

        throw new IgniteException(errorMsg, error);
    }

    /**
     * Creates Cassandra table.
     *
     * @param settings Persistence settings.
     */
    private void createTable(String table, KeyValuePersistenceSettings settings) {
        int attempt = 0;
        Throwable error = null;
        String tableFullName = settings.getKeyspace() + "." + table;
        String errorMsg = "Failed to create Cassandra table '" + tableFullName + "'";

        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                log.info("-----------------------------------------------------------------------");
                log.info("Creating Cassandra table '" + tableFullName + "'");
                log.info("-----------------------------------------------------------------------\n\n" +
                        settings.getTableDDLStatement(table) + "\n");
                log.info("-----------------------------------------------------------------------");
                session().execute(settings.getTableDDLStatement(table));
                log.info("Cassandra table '" + tableFullName + "' was successfully created");
                return;
            }
            catch (AlreadyExistsException ignored) {
                log.info("Cassandra table '" + tableFullName + "' already exist");
                return;
            }
            catch (Throwable e) {
                if (!CassandraHelper.isHostsAvailabilityError(e) && !CassandraHelper.isKeyspaceAbsenceError(e))
                    throw new IgniteException(errorMsg, e);

                if (CassandraHelper.isKeyspaceAbsenceError(e)) {
                    log.warning("Failed to create Cassandra table '" + tableFullName +
                        "' cause appropriate keyspace doesn't exist", e);
                    createKeyspace(settings);
                }
                else if (CassandraHelper.isHostsAvailabilityError(e))
                    handleHostsAvailabilityError(e, attempt, errorMsg);

                error = e;
            }

            attempt++;
        }

        throw new IgniteException(errorMsg, error);
    }

    /**
     * Creates Cassandra table indexes.
     *
     * @param settings Persistence settings.
     */
    private void createTableIndexes(String table, KeyValuePersistenceSettings settings) {
        List<String> indexDDLStatements = settings.getIndexDDLStatements(table);

        if (indexDDLStatements == null || indexDDLStatements.isEmpty())
            return;

        int attempt = 0;
        Throwable error = null;
        String tableFullName = settings.getKeyspace() + "." + table;
        String errorMsg = "Failed to create indexes for Cassandra table " + tableFullName;

        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                log.info("-----------------------------------------------------------------------");
                log.info("Creating indexes for Cassandra table '" + tableFullName + "'");
                log.info("-----------------------------------------------------------------------");

                for (String statement : indexDDLStatements) {
                    try {
                        log.info(statement);
                        log.info("-----------------------------------------------------------------------");
                        session().execute(statement);
                    }
                    catch (AlreadyExistsException ignored) {
                    }
                    catch (Throwable e) {
                        if (!(e instanceof InvalidQueryException) || !"Index already exists".equals(e.getMessage()))
                            throw new IgniteException(errorMsg, e);
                    }
                }

                log.info("Indexes for Cassandra table '" + tableFullName + "' were successfully created");

                return;
            }
            catch (Throwable e) {
                if (CassandraHelper.isHostsAvailabilityError(e))
                    handleHostsAvailabilityError(e, attempt, errorMsg);
                else if (CassandraHelper.isTableAbsenceError(e))
                    createTable(table, settings);
                else
                    throw new IgniteException(errorMsg, e);

                error = e;
            }

            attempt++;
        }

        throw new IgniteException(errorMsg, error);
    }

    /**
     * Tunes CQL statement execution options (consistency level, fetch option and etc.).
     *
     * @param statement Statement.
     * @return Modified statement.
     */
    private Statement tuneStatementExecutionOptions(Statement statement) {
        String qry = "";

        if (statement instanceof BoundStatement)
            qry = ((BoundStatement)statement).preparedStatement().getQueryString().trim().toLowerCase();
        else if (statement instanceof PreparedStatement)
            qry = ((PreparedStatement)statement).getQueryString().trim().toLowerCase();

        boolean readStatement = qry.startsWith("select");
        boolean writeStatement = statement instanceof Batch || statement instanceof BatchStatement ||
            qry.startsWith("insert") || qry.startsWith("delete") || qry.startsWith("update");

        if (readStatement && readConsistency != null)
            statement.setConsistencyLevel(readConsistency);

        if (writeStatement && writeConsistency != null)
            statement.setConsistencyLevel(writeConsistency);

        if (fetchSize != null)
            statement.setFetchSize(fetchSize);

        return statement;
    }

    /**
     * Handles situation when Cassandra table doesn't exist.
     *
     * @param settings Persistence settings.
     */
    private void handleTableAbsenceError(String table, KeyValuePersistenceSettings settings) {
        int hndNum = tblAbsenceHandlersCnt.incrementAndGet();

        String tableFullName = settings.getKeyspace() + "." + table;

        try {
            synchronized (tblAbsenceHandlersCnt) {
                // Oooops... I am not the first thread who tried to handle table absence problem.
                if (hndNum != 0) {
                    log.warning("Table " + tableFullName + " absence problem detected. " +
                            "Another thread already fixed it.");
                    return;
                }

                log.warning("Table " + tableFullName + " absence problem detected. " +
                        "Trying to create table.");

                IgniteException error = new IgniteException("Failed to create Cassandra table " + tableFullName);

                int attempt = 0;

                while (error != null && attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                    error = null;

                    try {
                        createKeyspace(settings);
                        createTable(table, settings);
                        createTableIndexes(table, settings);
                    }
                    catch (Throwable e) {
                        if (CassandraHelper.isHostsAvailabilityError(e))
                            handleHostsAvailabilityError(e, attempt, null);
                        else
                            throw new IgniteException("Failed to create Cassandra table " + tableFullName, e);

                        error = (e instanceof IgniteException) ? (IgniteException)e : new IgniteException(e);
                    }

                    attempt++;
                }

                if (error != null)
                    throw error;
            }
        }
        finally {
            if (hndNum == 0)
                tblAbsenceHandlersCnt.set(-1);
        }
    }

    /**
     * Handles situation when prepared statement execution failed cause session to the cluster was released.
     *
     */
    private void handlePreparedStatementClusterError(Throwable e) {
        int hndNum = prepStatementHandlersCnt.incrementAndGet();

        try {
            synchronized (prepStatementHandlersCnt) {
                // Oooops... I am not the first thread who tried to handle prepared statement problem.
                if (hndNum != 0) {
                    log.warning("Prepared statement cluster error detected, another thread already fixed the problem", e);
                    return;
                }

                log.warning("Prepared statement cluster error detected, refreshing Cassandra session", e);

                refresh();

                log.warning("Cassandra session refreshed");
            }
        }
        finally {
            if (hndNum == 0)
                prepStatementHandlersCnt.set(-1);
        }
    }

    /**
     * Handles situation when Cassandra host which is responsible for CQL query execution became unavailable.
     *
     * @param e Exception to handle.
     * @param attempt Number of attempts.
     * @param msg Error message.
     * @return {@code true} if host unavailability was successfully handled.
     */
    private boolean handleHostsAvailabilityError(Throwable e, int attempt, String msg) {
        if (attempt >= CQL_EXECUTION_ATTEMPTS_COUNT) {
            log.error("Host availability problem detected. " +
                    "Number of CQL execution attempts reached maximum " + CQL_EXECUTION_ATTEMPTS_COUNT +
                    ", exception will be thrown to upper execution layer.", e);
            throw msg == null ? new IgniteException(e) : new IgniteException(msg, e);
        }

        if (attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 4  ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 2  ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 2 + CQL_EXECUTION_ATTEMPTS_COUNT / 4  ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT - 1) {
            log.warning("Host availability problem detected, CQL execution attempt  " + (attempt + 1) + ", " +
                    "refreshing Cassandra session", e);

            refresh();

            log.warning("Cassandra session refreshed");

            return true;
        }

        log.warning("Host availability problem detected, CQL execution attempt " + (attempt + 1) + ", " +
                "sleeping extra " + CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT + " milliseconds", e);

        try {
            Thread.sleep(CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT);
        }
        catch (InterruptedException ignored) {
        }

        log.warning("Sleep completed");

        return false;
    }

    /**
     * @return New random sleeper.
     */
    private RandomSleeper newSleeper() {
        return new RandomSleeper(CQL_EXECUTION_ATTEMPT_MIN_TIMEOUT,
                CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT,
                CQL_ATTEMPTS_TIMEOUT_INCREMENT, log);
    }
}
