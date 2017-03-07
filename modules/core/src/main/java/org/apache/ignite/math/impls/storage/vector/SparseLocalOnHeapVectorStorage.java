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

package org.apache.ignite.math.impls.storage.vector;

import it.unimi.dsi.fastutil.ints.*;
import org.apache.ignite.math.*;
import java.io.*;
import java.util.*;

/**
 * Sparse, local, on-heap vector storage.
 */
public class SparseLocalOnHeapVectorStorage implements VectorStorage, StorageConstants {
    private int size;
    private int acsMode;

    // Actual map storage.
    private Map<Integer, Double> sto;

    /**
     *
     */
    public SparseLocalOnHeapVectorStorage() {
        // No-op.
    }

    /**
     *
     * @param size
     * @param acsMode
     */
    public SparseLocalOnHeapVectorStorage(int size, int acsMode) {
        assert size > 0;
        assertAccessMode(acsMode);

        this.size  = size;
        this.acsMode = acsMode;

        if (acsMode == SEQUENTIAL_ACCESS_MODE)
            sto = new Int2DoubleRBTreeMap();
        else
            sto = new Int2DoubleOpenHashMap();
    }

    /**
     *
     * @return
     */
    public int getAccessMode() {
        return acsMode;
    }

    @Override public int size() {
        return size;
    }

    @Override public double get(int i) {
        return sto.get(i);
    }

    @Override public void set(int i, double v) {
        sto.put(i, v);
    }

    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(size);
        out.writeInt(acsMode);
        out.writeObject(sto);
    }

    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        size = in.readInt();
        acsMode = in.readInt();
        sto = (Map<Integer, Double>)in.readObject();
    }

    @Override public boolean isSequentialAccess() {
        return acsMode == SEQUENTIAL_ACCESS_MODE;
    }

    @Override public boolean isDense() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isRandomAccess() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean isDistributed() {
        return false;
    }

    @Override public boolean isArrayBased() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        SparseLocalOnHeapVectorStorage that = (SparseLocalOnHeapVectorStorage) o;

        return size == that.size && acsMode == that.acsMode && (sto != null ? sto.equals(that.sto) : that.sto == null);
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        int res = size;

        res = 31 * res + acsMode;
        res = 31 * res + (sto != null ? sto.hashCode() : 0);

        return res;
    }
}
