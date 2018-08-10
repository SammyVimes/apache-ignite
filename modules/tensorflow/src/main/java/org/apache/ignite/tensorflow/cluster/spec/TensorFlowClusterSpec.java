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

package org.apache.ignite.tensorflow.cluster.spec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.Ignite;

/**
 * TensorFlow cluster specification.
 */
public class TensorFlowClusterSpec implements Serializable {
    /** */
    private static final long serialVersionUID = 1428581667528448091L;

    /** TensorFlow cluster jobs. */
    private final Map<String, List<TensorFlowServerAddressSpec>> jobs = new HashMap<>();

    /**
     * Adds new task to the cluster specification.
     *
     * @param jobName Job name.
     * @param nodeId Node identifier.
     * @param port Port number.
     * @return This instance of TensorFlow cluster specification.
     */
    public TensorFlowClusterSpec addTask(String jobName, UUID nodeId, int port) {
        jobs.putIfAbsent(jobName, new ArrayList<>());

        List<TensorFlowServerAddressSpec> tasks = jobs.get(jobName);

        tasks.add(new TensorFlowServerAddressSpec(nodeId, port));

        return this;
    }

    /**
     * Formats cluster specification so that TensorFlow accepts it.
     *
     * @param ignite Ignite instance.
     * @return Formatted cluster specification.
     */
    public String format(Ignite ignite) {
        StringBuilder builder = new StringBuilder();

        builder.append("{\n");

        for (Map.Entry<String, List<TensorFlowServerAddressSpec>> entry : jobs.entrySet()) {
            builder
                .append("\t\"")
                .append(entry.getKey())
                .append("\" : [ ");

            for (TensorFlowServerAddressSpec address : entry.getValue()) {
                builder
                    .append("\n\t\t\"")
                    .append(address.format(ignite))
                    .append("\", ");
            }

            if (!entry.getValue().isEmpty())
                builder.delete(builder.length() - 2, builder.length());

            builder.append("\n\t],\n");
        }

        if (!jobs.isEmpty())
            builder.delete(builder.length() - 2, builder.length() - 1);

        builder.append('}');

        return builder.toString();
    }

    /** */
    public Map<String, List<TensorFlowServerAddressSpec>> getJobs() {
        return jobs;
    }
}
