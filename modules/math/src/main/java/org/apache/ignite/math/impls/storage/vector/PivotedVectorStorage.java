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

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import org.apache.ignite.math.VectorStorage;

/**
 * Pivoted (index mapped) view over another vector storage implementation.
 */
public class PivotedVectorStorage implements VectorStorage {
    private VectorStorage sto;
    private int[] pivot;
    private int[] unpivot;

    /**
     * @param pivot
     */
    private static int[] reverse(int[] pivot) {
        int[] res = new int[pivot.length];

        for (int i = 0; i < pivot.length; i++)
            res[pivot[i]] = i;

        return res;
    }

    /**
     *
     *
     */
    public int[] pivot() {
        return pivot;
    }

    /**
     *
     *
     */
    public int[] unpivot() {
        return unpivot;
    }

    /**
     * @param sto
     * @param pivot Mapping from external index to internal.
     * @param unpivot Mapping from internal index to external.
     */
    public PivotedVectorStorage(VectorStorage sto, int[] pivot, int[] unpivot) {
        assert sto != null;
        assert pivot != null;
        assert unpivot != null;

        this.sto = sto;
        this.pivot = pivot;
        this.unpivot = unpivot;
    }

    /**
     * @param sto
     * @param pivot Mapping from external index to internal.
     */
    public PivotedVectorStorage(VectorStorage sto, int[] pivot) {
        this(sto, pivot, reverse(pivot));
    }

    /**
     *
     */
    public PivotedVectorStorage() {
        // No-op.
    }

    @Override public int size() {
        return sto.size();
    }

    @Override public double get(int i) {
        return sto.get(pivot[i]);
    }

    @Override public void set(int i, double v) {
        sto.set(pivot[i], v);
    }

    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(sto);
        out.writeObject(pivot);
        out.writeObject(unpivot);
    }

    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        sto = (VectorStorage)in.readObject();
        pivot = (int[])in.readObject();
        unpivot = (int[])in.readObject();
    }

    @Override public boolean isSequentialAccess() {
        return sto.isSequentialAccess();
    }

    @Override public boolean isDense() {
        return sto.isDense();
    }

    /** {@inheritDoc} */
    @Override public boolean isRandomAccess() {
        return sto.isRandomAccess();
    }

    /** {@inheritDoc} */
    @Override public boolean isDistributed() {
        return sto.isDistributed();
    }

    @Override public boolean isArrayBased() {
        return sto.isArrayBased();
    }

    /** {@inheritDoc} */
    @Override public double[] data() {
        return isArrayBased() ? sto.data() : null;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        PivotedVectorStorage that = (PivotedVectorStorage)o;

        return (sto != null ? sto.equals(that.sto) : that.sto == null) && Arrays.equals(pivot, that.pivot)
            && Arrays.equals(unpivot, that.unpivot);
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        int res = sto != null ? sto.hashCode() : 0;

        res = 31 * res + Arrays.hashCode(pivot);
        res = 31 * res + Arrays.hashCode(unpivot);

        return res;
    }
}
