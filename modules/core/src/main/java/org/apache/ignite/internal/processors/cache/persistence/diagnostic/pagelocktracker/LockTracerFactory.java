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

package org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker;

import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.log.HeapArrayLockLog;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.log.OffHeapLockLog;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.stack.HeapArrayLockStack;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.stack.OffHeapLockStack;

import static java.lang.String.valueOf;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_PAGE_LOCK_TRACKER_CAPACITY;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_PAGE_LOCK_TRACKER_TYPE;
import static org.apache.ignite.IgniteSystemProperties.getInteger;

public final class LockTracerFactory {
    public static final int HEAP_STACK = 1;
    public static final int HEAP_LOG = 2;
    public static final int OFF_HEAP_STACK = 3;
    public static final int OFF_HEAP_LOG = 4;

    public static final int DEFAULT_CAPACITY = getInteger(IGNITE_PAGE_LOCK_TRACKER_CAPACITY, 128);
    public static final int DEFAULT_TYPE = getInteger(IGNITE_PAGE_LOCK_TRACKER_TYPE, 1);

    public static PageLockTracker create(String name) {
        return create(DEFAULT_TYPE, name);
    }

    public static PageLockTracker create(int type, String name) {
        return create(type, name, DEFAULT_CAPACITY);
    }

    public static PageLockTracker create(int type, String name, int size) {
        switch (type) {
            case HEAP_STACK:
                return new HeapArrayLockStack(name, size);
            case HEAP_LOG:
                return new HeapArrayLockLog(name, size);
            case OFF_HEAP_STACK:
                return new OffHeapLockStack(name, size);
            case OFF_HEAP_LOG:
                return new OffHeapLockLog(name, size);

            default:
                throw new IllegalArgumentException(valueOf(type));
        }
    }
}
