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

package org.apache.ignite.internal.processors.cache.transactions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.ignite.internal.processors.cache.PartitionUpdateCounter;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class PartitionUpdateCounterTest extends GridCommonAbstractTest {
    public void testPrimaryMode() {
        for (int i = 0; i < 1000; i++)
            doTestPrimaryMode(2, 6, 2, 10, 3, 1, 5, 4);
    }

    private void doTestPrimaryMode(long... reservations) {
        PartitionUpdateCounter pc = new PartitionUpdateCounter(log);

        long[] ctrs = new long[reservations.length];

        for (int i = 0; i < reservations.length; i++)
            ctrs[i] = pc.reserve(reservations[i]);

        List<T2<Long, Long>> tmp = new ArrayList<>();

        for (int i = 0; i < ctrs.length; i++)
            tmp.add(new T2<>(ctrs[i], reservations[i]));

        Collections.shuffle(tmp);

        for (T2<Long, Long> objects : tmp)
            pc.release(objects.get1(), objects.get2());

        assertEquals(pc.get(), pc.reserved());

        assertEquals(Arrays.stream(reservations).sum(), pc.get());
    }
}
