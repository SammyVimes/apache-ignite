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

package org.apache.ignite.math.impls;

import org.apache.ignite.cluster.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.math.*;
import org.apache.ignite.math.Vector;
import java.util.*;
import java.util.function.*;

/**
 * Basic implementation for vector.
 *
 * This is a trivial implementation for vector assuming dense logic, local on-heap JVM storage
 * based on <code>double[]</code> array. It is only suitable for data sets where
 * local, non-distributed execution is satisfactory and on-heap JVM storage is enough
 * to keep the entire data set.
 */
public class DenseLocalOnHeapVector implements Vector {
    /**
     *
     */
    public DenseLocalOnHeapVector() {
        this(100);
    }

    /**
     *
     * @param size Vector cardinality.
     */
    public DenseLocalOnHeapVector(int size) {
        // TODO.
    }

    /**
     *
     * @param arr
     * @param shallowCopy
     */
    public DenseLocalOnHeapVector(double[] arr, boolean shallowCopy) {
       // TODO.
    }

    /**
     *
     * @param arr
     */
    public DenseLocalOnHeapVector(double[] arr) {
        this(arr, false);
    }

    @Override
    public int size() {
        return 0; // TODO
    }

    @Override
    public boolean isDense() {
        return false; // TODO
    }

    @Override
    public boolean isSequentialAccess() {
        return false; // TODO
    }

    @Override
    public Vector clone() {
        return null; // TODO
    }

    @Override
    public Iterable<Element> all() {
        return null; // TODO
    }

    @Override
    public Iterable<Element> nonZeroes() {
        return null; // TODO
    }

    @Override
    public Element getElement(int idx) {
        return null; // TODO
    }

    @Override
    public Vector assign(double val) {
        return null; // TODO
    }

    @Override
    public Vector assign(double[] vals) {
        return null; // TODO
    }

    @Override
    public Vector assign(Vector vec) {
        return null; // TODO
    }

    @Override
    public Vector map(DoubleFunction<Double> fun) {
        return null; // TODO
    }

    @Override
    public Vector map(Vector vec, DoubleFunction<Double> fun) {
        return null; // TODO
    }

    @Override
    public Vector map(BiFunction<Double, Double, Double> fun, double y) {
        return null; // TODO
    }

    @Override
    public Vector divide(double x) {
        return null; // TODO
    }

    @Override
    public double dot(Vector vec) {
        return 0; // TODO
    }

    @Override
    public double get(int idx) {
        return 0; // TODO
    }

    @Override
    public double getX(int idx) {
        return 0; // TODO
    }

    @Override
    public Vector like(int crd) {
        return null; // TODO
    }

    @Override
    public Vector minus(Vector vec) {
        return null; // TODO
    }

    @Override
    public Vector normalize() {
        return null; // TODO
    }

    @Override
    public Vector normalize(double power) {
        return null; // TODO
    }

    @Override
    public Vector logNormalize() {
        return null; // TODO
    }

    @Override
    public Vector logNormalize(double power) {
        return null; // TODO
    }

    @Override
    public double norm(double power) {
        return 0; // TODO
    }

    @Override
    public Element minValue() {
        return null; // TODO
    }

    @Override
    public double maxValue() {
        return 0; // TODO
    }

    @Override
    public Vector plus(double x) {
        return null; // TODO
    }

    @Override
    public Vector plus(Vector vec) {
        return null; // TODO
    }

    @Override
    public Vector set(int idx, double val) {
        return null; // TODO
    }

    @Override
    public Vector setX(int idx, double val) {
        return null; // TODO
    }

    @Override
    public void incrementX(int idx, double val) {
        // TODO
    }

    @Override
    public int nonDefaultElements() {
        return 0; // TODO
    }

    @Override
    public int nonZeroElements() {
        return 0; // TODO
    }

    @Override
    public Vector times(double x) {
        return null; // TODO
    }

    @Override
    public Vector times(Vector x) {
        return null; // TODO
    }

    @Override
    public Vector viewPart(int offset, int length) {
        return null; // TODO
    }

    @Override
    public double sum() {
        return 0; // TODO
    }

    @Override
    public Matrix cross(Vector vec) {
        return null; // TODO
    }

    @Override
    public <T> T foldMap(BiFunction<T, Double, T> foldFun, DoubleFunction mapFun) {
        return null; // TODO
    }

    @Override
    public double getLengthSquared() {
        return 0; // TODO
    }

    @Override
    public double getDistanceSquared(Vector vec) {
        return 0; // TODO
    }

    @Override
    public double getLookupCost() {
        return 0; // TODO
    }

    @Override
    public boolean isAddConstantTime() {
        return false; // TODO
    }

    @Override
    public Optional<ClusterGroup> clusterGroup() {
        return null; // TODO
    }

    @Override
    public IgniteUuid guid() {
        return null; // TODO
    }
}
