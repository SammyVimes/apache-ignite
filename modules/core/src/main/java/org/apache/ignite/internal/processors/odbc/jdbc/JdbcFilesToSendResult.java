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

package org.apache.ignite.internal.processors.odbc.jdbc;

import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.internal.binary.BinaryReaderExImpl;
import org.apache.ignite.internal.binary.BinaryWriterExImpl;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * A request from server (in form of reply) to send files from client to server,
 * which is sent as a response to SQL COPY command (see IGNITE-6917 for details).
 *
 */
public class JdbcFilesToSendResult extends JdbcResult {

    /** Query ID for matching this command on server in further {@link JdbcBulkLoadFileBatchRequest} commands. */
    private long queryId;

    /** Local name of the file to send to server */
    private String locFileName;

    public JdbcFilesToSendResult() {
        super(BULK_LOAD_BATCH);
        queryId = 0;
        locFileName = null;
    }

    /**
     * Constructs a request from server (in form of reply) to send files from client to server.
     *
     * @param locFileName the local name of file to send.
     */
    public JdbcFilesToSendResult(long queryId, String locFileName) {
        super(BULK_LOAD_BATCH);
        this.queryId = queryId;
        this.locFileName = locFileName;
    }

    /**
     * Returns the query ID.
     *
     * @return Query ID.
     */
    public long queryId() {
        return queryId;
    }

    /**
     * Returns the local name of file to send.
     *
     * @return locFileName the local name of file to send.
     */
    public String localFileName() {
        return locFileName;
    }

    /** {@inheritDoc} */
    @Override public void writeBinary(BinaryWriterExImpl writer) throws BinaryObjectException {
        super.writeBinary(writer);

        writer.writeLong(queryId);
        writer.writeString(locFileName);
    }

    /** {@inheritDoc} */
    @Override public void readBinary(BinaryReaderExImpl reader) throws BinaryObjectException {
        super.readBinary(reader);

        queryId = reader.readLong();
        locFileName = reader.readString();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(JdbcFilesToSendResult.class, this);
    }
}
