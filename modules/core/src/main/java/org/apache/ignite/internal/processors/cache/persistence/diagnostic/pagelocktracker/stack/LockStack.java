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

import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.PageLockTracker;

public abstract class LockStack extends PageLockTracker<LockStackSnapshot> {
    protected int headIdx;

    protected LockStack(String name, int capacity) {
        super(name, capacity);
    }

    /** {@inheritDoc} */
    @Override public void onWriteLock0(int structureId, long pageId, long page, long pageAddr) {
        push(structureId, pageId, WRITE_LOCK);
    }

    /** {@inheritDoc} */
    @Override public void onWriteUnlock0(int structureId, long pageId, long page, long pageAddr) {
        pop(structureId, pageId, WRITE_UNLOCK);
    }

    /** {@inheritDoc} */
    @Override public void onReadLock0(int structureId, long pageId, long page, long pageAddr) {
        push(structureId, pageId, READ_LOCK);
    }

    /** {@inheritDoc} */
    @Override public void onReadUnlock0(int structureId, long pageId, long page, long pageAddr) {
        pop(structureId, pageId, READ_UNLOCK);
    }

    private void push(int structureId, long pageId, int op) {
        if (!validateOperation(structureId, pageId, op))
            return;

        reset();

        if (headIdx + 1 > capacity()) {
            invalid("Stack overflow, size=" + capacity() +
                ", headIdx=" + headIdx + " " + argsToString(structureId, pageId, op));

            return;
        }

        long pageId0 = getByIndex(headIdx);

        if (pageId0 != 0L) {
            invalid("Head element should be empty, headIdx=" + headIdx +
                ", pageIdOnHead=" + pageId0 + " " + argsToString(structureId, pageId, op));

            return;
        }

        setByIndex(headIdx, pageId);

        headIdx++;
    }

    private void pop(int structureId, long pageId, int op) {
        if (!validateOperation(structureId, pageId, op))
            return;

        reset();

        if (headIdx > 1) {
            int last = headIdx - 1;

            long val = getByIndex(last);

            if (val == pageId) {
                setByIndex(last, 0);

                //Reset head to the first not empty element.
                do {
                    headIdx--;
                }
                while (headIdx > 0 && getByIndex(headIdx - 1) == 0);
            }
            else {
                for (int idx = last - 1; idx >= 0; idx--) {
                    if (getByIndex(idx) == pageId) {
                        setByIndex(idx, 0);

                        return;
                    }
                }

                invalid("Can not find pageId in stack, headIdx=" + headIdx + " "
                    + argsToString(structureId, pageId, op));
            }
        }
        else {
            if (headIdx < 0) {
                invalid("HeadIdx can not be less, headIdx="
                    + headIdx + ", " + argsToString(structureId, pageId, op));

                return;
            }

            long val = getByIndex(0);

            if (val == 0) {
                invalid("Stack is empty, can not pop elemnt" + argsToString(structureId, pageId, op));

                return;
            }

            if (val == pageId) {
                setByIndex(0, 0);

                headIdx = 0;
            }
            else
                invalid("Can not find pageId in stack, headIdx=" + headIdx + " "
                    + argsToString(structureId, pageId, op));
        }
    }

    private void reset() {
        nextOpPageId = 0;
        nextOp = 0;
        nextOpStructureId = 0;
    }
}
