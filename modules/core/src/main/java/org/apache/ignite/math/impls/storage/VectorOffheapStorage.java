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

package org.apache.ignite.math.impls.storage;

import org.apache.ignite.internal.util.GridUnsafe;
import org.apache.ignite.math.*;
import java.io.*;

/**
 * TODO: add description.
 */
public class VectorOffheapStorage implements VectorStorage {

    private int len;
    /** */
    private long ptr;

    public VectorOffheapStorage(){

    }

    public VectorOffheapStorage(int size){
        len = size;

        ptr = GridUnsafe.allocateMemory(pointerOffset(size));
    }

    /** {@inheritDoc */
    @Override
    public int size() {
        return len;
    }

    /** {@inheritDoc */
    @Override
    public double get(int i) {
        return GridUnsafe.getDouble(pointerOffset(i));
    }

    /** {@inheritDoc */
    @Override
    public void set(int i, double v) {
        GridUnsafe.putDouble(pointerOffset(i), v);
    }

    /** {@inheritDoc */
    @Override
    public boolean isArrayBased() {
        return false;
    }

    /** {@inheritDoc */
    @Override
    public double[] data() {
        return null;
    }

    /** {@inheritDoc */
    @Override
    public boolean isSequentialAccess() {
        return true;
    }

    /** {@inheritDoc */
    @Override
    public boolean isDense() {
        return true;
    }

    /** {@inheritDoc */
    @Override
    public double getLookupCost() {
        return 0;
    }

    /** {@inheritDoc */
    @Override
    public boolean isAddConstantTime() {
        return true;
    }

    /** {@inheritDoc */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(ptr);

        out.writeInt(len);
    }

    /** {@inheritDoc */
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ptr = in.readLong();

        len = in.readInt();
    }

    /** {@inheritDoc */
    @Override
    public void destroy() {
        GridUnsafe.freeMemory(ptr);
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && getClass().equals(obj.getClass()) && (ptr == ((VectorOffheapStorage)obj).ptr && len == ((VectorOffheapStorage)obj).len);
    }

    /**
     * Pointer offset for specific index
     *
     * @param i Offset index.
     */
    private long pointerOffset(int i) {
        return ptr + i * Double.BYTES;
    }
}
