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

package org.apache.ignite.math;

/**
 * Storage and operation cost characteristics.
 */
public interface StorageOpsKinds {
    /**
     * Checks if this implementation should be considered to be iterable in index order in an efficient way.
     *
     * @return
     */
    public boolean isSequentialAccess();

    /**
     * Checks if this implementation should be considered dense so that it explicitly
     * represents every value.
     *
     * @return
     */
    public boolean isDense();

    /**
     * Gets an estimate of the cost *in number of ops* it takes to lookup a random element in this vector.
     *
     * @return
     */
    public double getLookupCost();

    /**
     * Checks if adding a non-zero element to this vector is done in a constant time.
     *
     * @return
     */
    public boolean isAddConstantTime();

    /**
     * Checks if implementation is based on Java arrays.
     *
     * @return
     */
    public boolean isArrayBased();
}
