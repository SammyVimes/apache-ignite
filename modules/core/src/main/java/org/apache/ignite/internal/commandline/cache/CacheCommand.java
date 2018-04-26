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
package org.apache.ignite.internal.commandline.cache;

import org.jetbrains.annotations.Nullable;

/**
 *
 */
public enum CacheCommand {
    /**
     * Prints out help for the cache command.
     */
    HELP("help"),

    /**
     * Checks consistency of primary and backup partitions assuming no concurrent updates are happening in the cluster.
     */
    IDLE_VERIFY("idle_verify"),
    IDLE_ANALYZE("idle_analyze"),

    /**
     * Get ignite sequence info.
     */
    SEQ("seq"),

    /**
     * Update ignite sequence.
     */
    UPDATE_SEQ("update_seq"),
    DESTROY_SEQ("destroy_seq"),

    /**
     * Print info regarding cache groups.
     */
    GROUPS("groups"),

    /**
     * Print info regarding cache affinity.
     */
    AFFINITY("affinity"),
    DESTROY("destroy"),

    /**
     * Validate indexes attempting to read each indexed entry.
     */
    VALIDATE_INDEXES("validate_indexes"),

    /**
     * Print info about contented keys.
     */
    CONT("cont");

    /** Enumerated values. */
    private static final CacheCommand[] VALS = values();

    /** Name. */
    private final String name;

    /**
     * @param name Name.
     */
    CacheCommand(String name) {
        this.name = name;
    }

    /**
     * @param text Command text.
     * @return Command for the text.
     */
    public static CacheCommand of(String text) {
        for (CacheCommand cmd : CacheCommand.values()) {
            if (cmd.text().equalsIgnoreCase(text))
                return cmd;
        }

        return null;
    }

    /**
     * @return Name.
     */
    public String text() {
        return name;
    }

    /**
     * Efficiently gets enumerated value from its ordinal.
     *
     * @param ord Ordinal value.
     * @return Enumerated value or {@code null} if ordinal out of range.
     */
    @Nullable public static CacheCommand fromOrdinal(int ord) {
        return ord >= 0 && ord < VALS.length ? VALS[ord] : null;
    }
}
