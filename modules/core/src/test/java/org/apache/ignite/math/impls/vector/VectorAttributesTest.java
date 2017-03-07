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

package org.apache.ignite.math.impls.vector;

import org.apache.ignite.math.*;
import org.apache.ignite.math.Vector;
import org.junit.*;
import java.util.*;
import java.util.function.*;

import static org.junit.Assert.*;

/** */
public class VectorAttributesTest {
    /** */ @Test
    public void isDenseTest() {
        alwaysTrueAttributeTest(StorageOpsMetrics::isDense);
    }

    /** */ @Test
    public void isSequentialAccessTest() {
        alwaysTrueAttributeTest(StorageOpsMetrics::isSequentialAccess);
    }

    /** */ @Test
    public void guidTest() {
        alwaysTrueAttributeTest(v -> v.guid() != null);
    }

    /** */
    private void alwaysTrueAttributeTest(Predicate<Vector> pred) {
        boolean expECaught = false;

        try {
            assertTrue("Null map args.",
                pred.test(new DenseLocalOnHeapVector(0)));
        } catch (AssertionError e) {
            expECaught = true;
        }

        assertTrue("Default constructor expect exception at this predicate.", expECaught);

        assertTrue("Size from args.",
            pred.test(new DenseLocalOnHeapVector(new HashMap<String, Object>(){{ put("size", 99); }})));

        expECaught = false;

        try {
            assertTrue("Null array shallow copy.",
                pred.test(new DenseLocalOnHeapVector(null, true)));
        } catch (AssertionError e) {
            expECaught = true;
        }

        expECaught = false;

        try {
            assertTrue("0 size, off heap vector.",
                pred.test(new DenseLocalOffHeapVector(new double[0])));
        } catch (AssertionError e) {
            expECaught = true;
        }

        assertTrue("Default constructor expect exception at this predicate.", expECaught);

        final double[] test = new double[99];

        assertTrue("Size from array in args.",
            pred.test(new DenseLocalOnHeapVector(new HashMap<String, Object>(){{
                put("arr", test);
                put("copy", false);
            }})));

        assertTrue("Size from array in args, shallow copy.",
            pred.test(new DenseLocalOnHeapVector(new HashMap<String, Object>(){{
                put("arr", test);
                put("copy", true);
            }})));

        assertTrue("0 size shallow copy.",
            pred.test(new DenseLocalOnHeapVector(new double[0], true)));

        assertTrue("0 size.",
            pred.test(new DenseLocalOnHeapVector(new double[0], false)));

        assertTrue("1 size shallow copy.",
            pred.test(new DenseLocalOnHeapVector(new double[1], true)));

        assertTrue("1 size.",
            pred.test(new DenseLocalOnHeapVector(new double[1], false)));

        assertTrue("0 size default copy.",
            pred.test(new DenseLocalOnHeapVector(new double[0])));

        assertTrue("1 size default copy.",
            pred.test(new DenseLocalOnHeapVector(new double[1])));

        assertTrue("Size from args, off heap vector.",
            pred.test(new DenseLocalOffHeapVector(new HashMap<String, Object>(){{ put("size", 99); }})));

        assertTrue("Size from array in args, off heap vector.",
            pred.test(new DenseLocalOffHeapVector(new HashMap<String, Object>(){{
                put("arr", test);
                put("copy", false);
            }})));

        assertTrue("Size from array in args, shallow copy, off heap vector.",
            pred.test(new DenseLocalOffHeapVector(new HashMap<String, Object>(){{
                put("arr", test);
                put("copy", true);
            }})));

        assertTrue("1 size, off heap vector.",
            pred.test(new DenseLocalOffHeapVector(new double[1])));

    }
}
