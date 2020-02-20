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

package org.apache.ignite.internal.processors.cache.checker.processor;

/**
 * Event listener factory for testing purposes.
 */
public class ReconciliationEventListenerFactory {
    /** Default instance. */
    private static volatile ReconciliationEventListener dfltInstance = (stage, workload) -> {
    };

    /**
     * @return Default implementation of ReconciliationEventListener.
     */
    public static ReconciliationEventListener defaultListenerInstance() {
        return dfltInstance;
    }

    /**
     * Sets a new instance of ReconciliationEventListener.
     *
     * @param dfltInstance New instance of ReconciliationEventListener.
     */
    public static void defaultListenerInstance(ReconciliationEventListener dfltInstance) {
        ReconciliationEventListenerFactory.dfltInstance = dfltInstance;
    }
}
