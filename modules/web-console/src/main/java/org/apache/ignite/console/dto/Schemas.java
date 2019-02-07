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

package org.apache.ignite.console.dto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Registry with DTO objects metadata.
 */
public class Schemas {
    /** Singleton instance to use. */
    private static final Schemas INSTANCE = new Schemas();

    /** */
    private final Map<Class, Properties> schemas = new HashMap<>();

    /**
     * Register schemas.
     */
    private Schemas() {
        Properties notebookSchema = new Properties()
            .addUuid("_id")
            .addString("space")
            .addString("name")
            .addArray("expandedParagraphs")
            .addChild("paragraphs", new Properties()
                .addString("name")
                .addString("query")
                .addString("result")
                .addNumber("pageSize")
                .addString("timeLineSpan")
                .addNumber("maxPages")
                .addString("cacheName")
                .addBoolean("useAsDefaultSchema")
                .addChild("chartsOptions", new Properties()
                    .addChild("barChart", new Properties()
                        .addBoolean("stacked"))
                    .addChild("areaChart", new Properties()
                        .addString("style")))
                .addChild("rate", new Properties()
                    .addNumber("value")
                    .addNumber("unit"))
                .addString("qryType")
                .addBoolean("nonCollocatedJoins")
                .addBoolean("enforceJoinOrder")
                .addBoolean("lazy")
                .addBoolean("collocated"));

        schemas.put(Notebook.class, notebookSchema);
    }

    /**
     * @param cls DTO class.
     * @return Schema descriptor.
     */
    public Properties schema(Class cls) {
        return schemas.get(cls);
    }

    /**
     * Sanitize raw data.
     *
     * @param schema Schema.
     * @param rawData Data object.
     * @return Sanitized object.
     */
    private static JsonObject sanitize0(Properties schema, JsonObject rawData) {
        Set<String> rawFlds = new HashSet<>(rawData.fieldNames());

        for (String fld : rawFlds) {
            if (schema.hasPropery(fld)) {
                Properties childSchema = schema.childSchema(fld);

                if (childSchema != null) {
                    Object child = rawData.getValue(fld);

                    if (child instanceof JsonArray) {
                        JsonArray rawItems = (JsonArray)child;
                        JsonArray sanitizedItems = new JsonArray();

                        rawItems.forEach(item -> sanitizedItems.add(sanitize0(childSchema, (JsonObject)item)));
                        rawData.put(fld, sanitizedItems);
                    }
                    else if (child instanceof JsonObject)
                        rawData.put(fld, sanitize0(childSchema, (JsonObject)child));
                    else
                        throw new IllegalStateException("Expected array or object, but found: " +
                            (child != null ? child.getClass().getName() : "null"));
                }
            }
            else
                rawData.remove(fld);
        }

        return rawData;
    }

    /**
     * Sanitize raw data.
     *
     * @param cls Class of data object.
     * @param rawData Data object.
     * @return Sanitized object.
     */
    public static JsonObject sanitize(Class cls, JsonObject rawData) {
        Properties schema = INSTANCE.schema(Notebook.class);

        return sanitize0(schema, rawData);
    }
}
