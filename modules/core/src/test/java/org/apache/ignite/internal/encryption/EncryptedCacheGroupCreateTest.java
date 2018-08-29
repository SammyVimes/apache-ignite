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

package org.apache.ignite.internal.encryption;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.managers.encryption.GridEncryptionManager;
import org.apache.ignite.internal.processors.cache.IgniteInternalCache;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.spi.encryption.aes.AESEncryptionKey;
import org.apache.ignite.testframework.GridTestUtils;
import sun.security.krb5.EncryptionKey;

/**
 */
public class EncryptedCacheGroupCreateTest extends AbstractEncryptionTest {
    /** */
    public static final String ENCRYPTED_GROUP = "encrypted-group";

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        cleanPersistenceDir();

        IgniteEx igniteEx = startGrid(0);

        startGrid(1);

        igniteEx.cluster().active(true);

        awaitPartitionMapExchange();
    }

    /** @throws Exception If failed. */
    public void testCreateEncryptedCacheGroup() throws Exception {
        AESEncryptionKey key = createEncryptedCache(ENCRYPTED_CACHE, ENCRYPTED_GROUP);

        CacheConfiguration<Long, String> ccfg = new CacheConfiguration<>(ENCRYPTED_CACHE + "2");

        ccfg.setEncryptionEnabled(true);
        ccfg.setGroupName(ENCRYPTED_GROUP);

        IgniteEx grid = grid(0);

        grid.createCache(ccfg);

        IgniteInternalCache<Object, Object> encrypted2 = grid.cachex(ENCRYPTED_CACHE + "2");

        GridEncryptionManager encMgr = encrypted2.context().kernalContext().encryption();

        AESEncryptionKey key2 = (AESEncryptionKey)encMgr.groupKey(CU.cacheGroupId(ENCRYPTED_CACHE, ENCRYPTED_GROUP));

        assertNotNull(key2);
        assertNotNull(key2.key());

        assertEquals(key.key(), key2.key());
    }

    /** @throws Exception If failed. */
    public void testCreateNotEncryptedCacheInEncryptedGroupFails() throws Exception {
        createEncryptedCache(ENCRYPTED_CACHE + "3", ENCRYPTED_GROUP + "3");

        IgniteEx grid = grid(0);

        GridTestUtils.assertThrowsWithCause(() -> {
            grid.createCache(new CacheConfiguration<>(ENCRYPTED_CACHE + "4")
                .setEncryptionEnabled(false)
                .setGroupName(ENCRYPTED_GROUP + "3"));
        }, IgniteCheckedException.class);
    }

    /** */
    private AESEncryptionKey createEncryptedCache(String cacheName, String grpName) {
        CacheConfiguration<Long, String> ccfg = new CacheConfiguration<>(cacheName);

        ccfg.setEncryptionEnabled(true);
        ccfg.setGroupName(grpName);

        IgniteEx grid = grid(0);

        grid.createCache(ccfg);

        IgniteInternalCache<Object, Object> enc = grid.cachex(cacheName);

        assertNotNull(enc);

        AESEncryptionKey key =
            (AESEncryptionKey)grid.context().encryption().groupKey(CU.cacheGroupId(cacheName, grpName));

        assertNotNull(key);
        assertNotNull(key.key());

        return key;
    }
}
