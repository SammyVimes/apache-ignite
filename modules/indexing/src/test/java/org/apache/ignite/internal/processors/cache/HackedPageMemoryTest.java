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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class HackedPageMemoryTest extends GridCommonAbstractTest {
    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        DataStorageConfiguration dsCfg = new DataStorageConfiguration()
            .setCheckpointThreads(1)
            .setDefaultDataRegionConfiguration(
                new DataRegionConfiguration()
                    .setPersistenceEnabled(true)
                    .setMaxSize(256 * 1024 * 1024))
            .setDataRegionConfigurations(
                new DataRegionConfiguration()
                    .setName("query")
                    .setPersistenceEnabled(true)
                    .setHacked(true).setMaxSize(8L * 1024 * 1024 * 1024));

        cfg.setDataStorageConfiguration(dsCfg);

        cfg.setCacheConfiguration(new CacheConfiguration("query").setDataRegionName("query").setIndexedTypes(Integer.class, Integer.class));

        return cfg;
    }

    public void testSimpleOps() throws Exception {
        cleanPersistenceDir();

        try {
            IgniteEx ig = startGrid(0);

            ig.cluster().active(true);

            for (int i = 0; i < 100_000; i++) {
                ig.cache("query").put(i, 1);

                U.debug("Done: " + i);
            }

            assertEquals(1, ig.cache("query").get(1));

            ig.context().cache().context().database().waitForCheckpoint("test");
        }
        finally {
            stopAllGrids();
        }
    }
}
