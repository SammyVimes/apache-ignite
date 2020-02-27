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

import java.util.UUID;

/**
 * Abstract workload.
 */
public abstract class PipelineWorkload {
    /** Session id. */
    protected final long sesId;

    /** Workload chain id. */
    protected final UUID workloadChainId;

    /**
     * @param sesId Session id.
     * @param workloadChainId Workload chain id.
     */
    public PipelineWorkload(long sesId, UUID workloadChainId) {
        this.sesId = sesId;
        this.workloadChainId = workloadChainId;
    }

    /**
     * @return ID of global workload session.
     */
    public long sessionId() {
        return sesId;
    }

    /**
     * @return Unique ID of workload chain.
     */
    public UUID workloadChainId() {
        return workloadChainId;
    }
}
