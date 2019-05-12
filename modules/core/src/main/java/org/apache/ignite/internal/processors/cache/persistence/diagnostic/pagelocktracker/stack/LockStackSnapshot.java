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

package org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.stack;

import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.Dump;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.DumpProcessor;

import static org.apache.ignite.internal.pagemem.PageIdUtils.pageId;

public class LockStackSnapshot implements Dump {
    public final String name;

    public final long time;

    public final int headIdx;

    public final long[] pageIdLocksStack;

    public final int nextOp;
    public final int nextOpStructureId;
    public final long nextOpPageId;

    public LockStackSnapshot(
        String name,
        long time,
        int headIdx,
        long[] pageIdLocksStack,
        int nextOp,
        int nextOpStructureId,
        long nextOpPageId
    ) {
        this.name = name;
        this.time = time;
        this.headIdx = headIdx;
        this.pageIdLocksStack = pageIdLocksStack;
        this.nextOp = nextOp;
        this.nextOpStructureId = nextOpStructureId;
        this.nextOpPageId = nextOpPageId;
    }

    /** {@inheritDoc} */
    @Override public void apply(DumpProcessor dumpProcessor) {
        dumpProcessor.processDump(this);
    }
}
