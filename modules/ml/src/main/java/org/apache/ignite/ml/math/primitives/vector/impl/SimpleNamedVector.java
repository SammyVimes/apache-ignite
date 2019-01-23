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

package org.apache.ignite.ml.math.primitives.vector.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.ignite.ml.math.exceptions.MathIllegalArgumentException;
import org.apache.ignite.ml.math.primitives.matrix.Matrix;
import org.apache.ignite.ml.math.primitives.matrix.impl.DenseMatrix;
import org.apache.ignite.ml.math.primitives.vector.AbstractVector;
import org.apache.ignite.ml.math.primitives.vector.NamedVector;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.primitives.vector.storage.DenseVectorStorage;

/**
 * Simple implementation of {@link NamedVector}.
 */
public class SimpleNamedVector extends AbstractVector<SimpleNamedVector> implements NamedVector {
    /** Map that maps field names on field indexes. */
    private final Map<String, Integer> map;

    /**
     * Constructs a new simple named vector.
     *
     * @param map Map that maps field names on field indexes.
     */
    public SimpleNamedVector(Map<String, Integer> map) {
        this.map = map;

        setStorage(new DenseVectorStorage(map.size()));
    }

    /** {@inheritDoc} */
    @Override public SimpleNamedVector like(int crd) {
        if (crd != size())
            throw new IllegalArgumentException("...");

        return new SimpleNamedVector(map);
    }

    /** {@inheritDoc} */
    @Override public Matrix likeMatrix(int rows, int cols) {
        return new DenseMatrix(rows, cols);
    }

    /** {@inheritDoc} */
    @Override public double get(String idx) {
        int intIdx = Objects.requireNonNull(map.get(idx), "Index not found [name='" + idx + "']");

        return get(intIdx);
    }

    /** {@inheritDoc} */
    @Override public NamedVector set(String idx, double val) {
        int intIdx = Objects.requireNonNull(map.get(idx), "Index not found [name='" + idx + "']");

        set(intIdx, val);

        return this;
    }

    /** {@inheritDoc} */
    @Override public Set<String> getKeys() {
        return map.keySet();
    }

    /** {@inheritDoc} */
    @Override protected void checkCardinality(Vector vec) {
        if (!(vec instanceof SimpleNamedVector))
            throw new MathIllegalArgumentException("Vector is not a named vector");

        if (!map.equals(((SimpleNamedVector) vec).map))
            throw new MathIllegalArgumentException("Named vector mappings are not equal");

        super.checkCardinality(vec);
    }
}
