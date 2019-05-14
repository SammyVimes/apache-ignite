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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ignite.internal.processors.cache.persistence.tree.util.PageLockListener;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteFuture;

//TODO Calculate overhad and capacity for all structures.
//TODO Fast local get thread local.
//TODO Test deadlock
//TODO Dynamic enable/disable tracing.
//TODO Collect page content to dump. AG
//TODO Create dump by timeout.
/** */
public class SharedPageLockTracker implements PageLockListener, DumpSupported<ThreadDumpLocks> {
    /** */
    private static final int THREAD_LIMITS = 1000;

    /** */
    private final Map<Long, PageLockTracker> threadStacks = new HashMap<>();
    /** */
    private final Map<Long, Thread> threadIdToThreadRef = new HashMap<>();

    /** */
    private final Map<String, Integer> structureNameToId = new HashMap<>();

    /** Thread for clean terminated threads from map. */
    private final Cleaner cleaner = new Cleaner();

    /** */
    private int idGen;

    /** */
    private final ThreadLocal<PageLockTracker> lockTracker = ThreadLocal.withInitial(() -> {
        Thread thread = Thread.currentThread();

        String threadName = thread.getName();
        long threadId = thread.getId();

        PageLockTracker tracker = LockTrackerFactory.create("name=" + threadName);

        synchronized (this) {
            threadStacks.put(threadId, tracker);

            threadIdToThreadRef.put(threadId, thread);

            if (threadIdToThreadRef.size() > THREAD_LIMITS)
                cleanTerminatedThreads();
        }

        return tracker;
    });

    /** */
    void onStart() {
        cleaner.setDaemon(true);

        cleaner.start();
    }

    /** */
    public synchronized PageLockListener registrateStructure(String structureName) {
        Integer id = structureNameToId.get(structureName);

        if (id == null)
            structureNameToId.put(structureName, id = (++idGen));

        return new PageLockListenerIndexAdapter(id, this);
    }

    /** {@inheritDoc} */
    @Override public void onBeforeWriteLock(int structureId, long pageId, long page) {
        lockTracker.get().onBeforeWriteLock(structureId, pageId, page);
    }

    /** {@inheritDoc} */
    @Override public void onWriteLock(int structureId, long pageId, long page, long pageAddr) {
        lockTracker.get().onWriteLock(structureId, pageId, page, pageAddr);
    }

    /** {@inheritDoc} */
    @Override public void onWriteUnlock(int structureId, long pageId, long page, long pageAddr) {
        lockTracker.get().onWriteUnlock(structureId, pageId, page, pageAddr);
    }

    /** {@inheritDoc} */
    @Override public void onBeforeReadLock(int structureId, long pageId, long page) {
        lockTracker.get().onBeforeReadLock(structureId, pageId, page);
    }

    /** {@inheritDoc} */
    @Override public void onReadLock(int structureId, long pageId, long page, long pageAddr) {
        lockTracker.get().onReadLock(structureId, pageId, page, pageAddr);
    }

    /** {@inheritDoc} */
    @Override public void onReadUnlock(int structureId, long pageId, long page, long pageAddr) {
        lockTracker.get().onReadUnlock(structureId, pageId, page, pageAddr);
    }

    /** {@inheritDoc} */
    @Override public synchronized ThreadDumpLocks dump() {
        Collection<PageLockTracker> trackers = threadStacks.values();
        List<ThreadDumpLocks.ThreadState> threadStates = new ArrayList<>(threadStacks.size());

        for (PageLockTracker tracker : trackers) {
            boolean acquired = tracker.acquireSafePoint();

            //TODO
            assert acquired;
        }

        for (Map.Entry<Long, PageLockTracker> entry : threadStacks.entrySet()) {
            Long threadId = entry.getKey();
            Thread thread = threadIdToThreadRef.get(threadId);

            PageLockTracker<Dump> tracker = entry.getValue();

            try {
                Dump dump = tracker.dump();

                threadStates.add(
                    new ThreadDumpLocks.ThreadState(
                        threadId,
                        thread.getName(),
                        thread.getState(),
                        dump,
                        tracker.isInvalid() ? tracker.invalidContext() : null
                    )
                );
            }
            finally {
                tracker.releaseSafePoint();
            }
        }

        Map<Integer, String> idToStrcutureName0 =
            Collections.unmodifiableMap(
                structureNameToId.entrySet().stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getValue,
                        Map.Entry::getKey
                    ))
            );

        List<ThreadDumpLocks.ThreadState> threadStates0 =
            Collections.unmodifiableList(threadStates);

        // Get first thread dump time or current time is threadStates is empty.
        long time = !threadStates.isEmpty() ? threadStates.get(0).dump.time() : U.currentTimeMillis();

        return new ThreadDumpLocks(time, idToStrcutureName0, threadStates0);
    }

    /** */
    private synchronized void cleanTerminatedThreads() {
        Iterator<Map.Entry<Long, Thread>> it = threadIdToThreadRef.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Long, Thread> entry = it.next();

            long threadId = entry.getKey();
            Thread thread = entry.getValue();

            if (thread.getState() == Thread.State.TERMINATED) {
                PageLockTracker tracker = threadStacks.remove(threadId);

                if (tracker != null)
                    tracker.free();

                it.remove();
            }
        }
    }

    /** */
    private class Cleaner extends Thread {
        @Override public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    sleep(60_000);

                    cleanTerminatedThreads();
                }
            }
            catch (InterruptedException e) {
                // No-op.
            }
        }
    }

    /** */
    @Override public IgniteFuture<ThreadDumpLocks> dumpSync() {
        throw new UnsupportedOperationException();
    }

    /** */
    @Override public boolean acquireSafePoint() {
        throw new UnsupportedOperationException();
    }

    /** */
    @Override public boolean releaseSafePoint() {
        throw new UnsupportedOperationException();
    }
}
