/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.db.filename;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.PersistentStoreConfiguration;
import org.apache.ignite.internal.processors.cache.persistence.filename.PdsConsistentIdGeneratingFoldersResolver;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridStringLogger;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.jetbrains.annotations.NotNull;

/**
 * Test for new and old style persistent storage folders generation
 */
public class IgniteUidAsConsistentIdMigrationTest extends GridCommonAbstractTest {

    /** Cache name for test. */
    public static final String CACHE_NAME = "dummy";

    private boolean deleteAfter = false;
    private boolean deleteBefore = true;
    private boolean failIfDeleteNotCompleted = true;

    /** Configured consistent id. */
    private String configuredConsistentId;

    /** Logger to accumulate messages, null will cause logger won't be customized */
    private GridStringLogger stringLogger;

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        stopAllGrids();

        if (deleteBefore)
            deleteWorkFiles();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        if (deleteAfter)
            deleteWorkFiles();
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void deleteWorkFiles() throws IgniteCheckedException {
        boolean ok = true;
        ok &= deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
        ok &= deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "binary_meta", false));
        if (failIfDeleteNotCompleted)
            assertTrue(ok);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        final IgniteConfiguration cfg = super.getConfiguration(gridName);
        if (configuredConsistentId != null)
            cfg.setConsistentId(configuredConsistentId);
        final PersistentStoreConfiguration psCfg = new PersistentStoreConfiguration();
        cfg.setPersistentStoreConfiguration(psCfg);

        final MemoryConfiguration memCfg = new MemoryConfiguration();
        final MemoryPolicyConfiguration memPolCfg = new MemoryPolicyConfiguration();
        memPolCfg.setMaxSize(32 * 1024 * 1024); // we don't need much memory for this test
        memCfg.setMemoryPolicies(memPolCfg);
        cfg.setMemoryConfiguration(memCfg);

        if (stringLogger != null)
            cfg.setGridLogger(stringLogger);
        return cfg;
    }

    /**
     * Checks start on empty PDS folder, in that case node 0 should start with random UUID.
     *
     * @throws Exception if failed.
     */
    public void testNewStyleIdIsGenerated() throws Exception {
        final Ignite ignite = startActivateFillDataGrid(0);
        //test UUID is parsaable from consistent ID test
        UUID.fromString(ignite.cluster().localNode().consistentId().toString());
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite));
        stopGrid(0);
    }

    /**
     * Checks start on empty PDS folder using configured ConsistentId. We should start using this ID in compatible mode.
     *
     * @throws Exception if failed.
     */
    public void testPreconfiguredConsitentIdIsApplied() throws Exception {
        this.configuredConsistentId = "someConfiguredConsistentId";
        Ignite ignite = startActivateFillDataGrid(0);

        assertPdsDirsDefaultExist(configuredConsistentId);
        stopGrid(0);
    }

    /**
     * Checks start on configured ConsistentId with same value as default, this emulate old style folder is already
     * available. We should restart using this folder.
     *
     * @throws Exception if failed
     */
    public void testRestartOnExistingOldStyleId() throws Exception {
        final String expDfltConsistentId = "127.0.0.1:47500";
        this.configuredConsistentId = expDfltConsistentId; //this is for create old node folder

        final Ignite igniteEx = startActivateGrid(0);

        final String expVal = "there is compatible mode with old style folders!";

        igniteEx.getOrCreateCache(CACHE_NAME).put("hi", expVal);

        assertPdsDirsDefaultExist(U.maskForFileName(configuredConsistentId));
        stopGrid(0);

        this.configuredConsistentId = null; //now set up grid on existing folder

        final Ignite igniteRestart = startActivateGrid(0);

        assertEquals(expDfltConsistentId, igniteRestart.cluster().localNode().consistentId());
        final IgniteCache<Object, Object> cache = igniteRestart.cache(CACHE_NAME);

        assertNotNull("Expected to have cache [" + CACHE_NAME + "] using [" + expDfltConsistentId + "] as PDS folder", cache);
        final Object valFromCache = cache.get("hi");

        assertNotNull("Expected to load data from cache using [" + expDfltConsistentId + "] as PDS folder", valFromCache);
        assertTrue(expVal.equals(valFromCache));
        stopGrid(0);
    }

    /**
     * Start stop grid without activation should cause lock to be released and restarted node should have index 0
     *
     * @throws Exception if failed
     */
    public void testStartWithoutActivate() throws Exception {
        //start stop grid without activate
        startGrid(0);
        stopGrid(0);

        Ignite igniteRestart = startActivateFillDataGrid(0);
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, igniteRestart));
        stopGrid(0);
    }

    /**
     * Checks start on empty PDS folder, in that case node 0 should start with random UUID
     *
     * @throws Exception if failed
     */
    public void testRestartOnSameFolderWillCauseSameUuidGeneration() throws Exception {
        final UUID uuid;
        {
            final Ignite ignite = startActivateFillDataGrid(0);

            assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite));

            uuid = (UUID)ignite.cluster().localNode().consistentId();
            stopGrid(0);
        }

        {
            final Ignite igniteRestart = startActivateGrid(0);
            assertTrue("there!".equals(igniteRestart.cache(CACHE_NAME).get("hi")));

            final Object consIdRestart = igniteRestart.cluster().localNode().consistentId();
            assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, igniteRestart));
            stopGrid(0);

            assertEquals(uuid, consIdRestart);
        }
    }

    /**
     * This test starts node, activates, deactivates node, and then start second node.
     * Expected behaviour is following: second node will join topology with separate node folder
     *
     * @throws Exception if failed
     */
    public void testStartNodeAfterDeactivate() throws Exception {
        final UUID uuid;
        {
            final Ignite ignite = startActivateFillDataGrid(0);

            assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite));

            uuid = (UUID)ignite.cluster().localNode().consistentId();
            ignite.active(false);
        }
        {
            final Ignite igniteRestart = startActivateGrid(1);
            grid(0).active(true);
            final Object consIdRestart = igniteRestart.cluster().localNode().consistentId();

            assertPdsDirsDefaultExist(genNewStyleSubfolderName(1, igniteRestart));

            awaitPartitionMapExchange();
            assertTrue("there!".equals(igniteRestart.cache(CACHE_NAME).get("hi")));
            stopGrid(1);
            assertFalse(consIdRestart.equals(uuid));
        }
        stopGrid(0);
        assertNodeIndexesInFolder(0, 1);
    }

    @NotNull private Ignite startActivateFillDataGrid(int idx) throws Exception {
        final Ignite ignite = startActivateGrid(idx);
        ignite.getOrCreateCache(CACHE_NAME).put("hi", "there!");
        return ignite;
    }

    /**
     * Starts and activates new grid with given index.
     *
     * @param idx Index of the grid to start.
     * @return Started and activated grid.
     * @throws Exception If anything failed.
     */
    @NotNull private Ignite startActivateGrid(int idx) throws Exception {
        final Ignite ignite = startGrid(idx);
        ignite.active(true);
        return ignite;
    }

    /**
     * Generates folder name in new style using constant prefix and UUID
     *
     * @param nodeIdx expected node index to check
     * @param ignite ignite instance
     * @return name of storage related subfolders
     */
    @NotNull private String genNewStyleSubfolderName(final int nodeIdx, final Ignite ignite) {
        final Object consistentId = ignite.cluster().localNode().consistentId();
        assertTrue("For new style folders consistent ID should be UUID," +
                " but actual class is " + (consistentId == null ? null : consistentId.getClass()),
            consistentId instanceof UUID);
        return PdsConsistentIdGeneratingFoldersResolver.genNewStyleSubfolderName(nodeIdx, (UUID)consistentId);
    }

    /**
     * test two nodes started at the same db root folder, second node should get index 1
     *
     * @throws Exception if failed
     */
    public void testNodeIndexIncremented() throws Exception {
        final Ignite ignite0 = startGrid(0);
        final Ignite ignite1 = startGrid(1);

        ignite0.active(true);

        ignite0.getOrCreateCache(CACHE_NAME).put("hi", "there!");
        ignite1.getOrCreateCache(CACHE_NAME).put("hi1", "there!");

        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite0));
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(1, ignite1));

        stopGrid(0);
        stopGrid(1);
        assertNodeIndexesInFolder(0, 1);
    }

    /**
     * Test verified that new style folder is taken always with lowest index
     *
     * @throws Exception if failed
     */
    public void testNewStyleAlwaysSmallestNodeIndexIsCreated() throws Exception {
        final Ignite ignite0 = startGrid(0);
        final Ignite ignite1 = startGrid(1);
        final Ignite ignite2 = startGrid(2);
        final Ignite ignite3 = startGrid(3);

        ignite0.active(true);

        ignite0.getOrCreateCache(CACHE_NAME).put("hi", "there!");
        ignite3.getOrCreateCache(CACHE_NAME).put("hi1", "there!");

        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite0));
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(1, ignite1));
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(2, ignite2));
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(3, ignite3));

        assertNodeIndexesInFolder(0, 1, 2, 3);
        stopAllGrids();

        //this grid should take folder with index 0 as unlocked
        final Ignite ignite4Restart = startActivateGrid(3);
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite4Restart));

        assertNodeIndexesInFolder(0, 1, 2, 3);
        stopAllGrids();
    }

    /**
     * Test verified that new style folder is taken always with lowest index
     *
     * @throws Exception if failed
     */
    public void testNewStyleAlwaysSmallestNodeIndexIsCreatedMultithreaded() throws Exception {
        final Ignite ignite0 = startGridsMultiThreaded(11);

        ignite0.active(true);

        ignite0.getOrCreateCache(CACHE_NAME).put("hi", "there!");
        ignite0.getOrCreateCache(CACHE_NAME).put("hi1", "there!");

        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite0));

        assertNodeIndexesInFolder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        stopAllGrids();

        //this grid should take folder with index 0 as unlocked
        final Ignite ignite4Restart = startActivateGrid(4);
        assertPdsDirsDefaultExist(genNewStyleSubfolderName(0, ignite4Restart));
        stopAllGrids();

        assertNodeIndexesInFolder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    /**
     * Test start two nodes with predefined conistent ID (emulate old fashion node). Then restart two nodes. Expected
     * both nodes will get its own old folders
     *
     * @throws Exception if failed.
     */
    public void testStartTwoOldStyleNodes() throws Exception {
        final String expDfltConsistentId1 = "127.0.0.1:47500";
        this.configuredConsistentId = expDfltConsistentId1; //this is for create old node folder
        final Ignite ignite = startGrid(0);

        final String expDfltConsistentId2 = "127.0.0.1:47501";
        this.configuredConsistentId = expDfltConsistentId2; //this is for create old node folder
        final Ignite ignite2 = startGrid(1);
        ignite.active(true);

        final String expVal = "there is compatible mode with old style folders!";

        ignite2.getOrCreateCache(CACHE_NAME).put("hi", expVal);

        assertPdsDirsDefaultExist(U.maskForFileName(expDfltConsistentId1));
        assertPdsDirsDefaultExist(U.maskForFileName(expDfltConsistentId2));
        stopAllGrids();

        this.configuredConsistentId = null; //now set up grid on existing folder

        final Ignite igniteRestart = startGrid(0);
        final Ignite igniteRestart2 = startGrid(1);
        igniteRestart2.active(true);

        assertEquals(expDfltConsistentId1, igniteRestart.cluster().localNode().consistentId());
        assertEquals(expDfltConsistentId2, igniteRestart2.cluster().localNode().consistentId());

        final IgniteCache<Object, Object> cache = igniteRestart.cache(CACHE_NAME);
        assertNotNull("Expected to have cache [" + CACHE_NAME + "] using [" + expDfltConsistentId1 + "] as PDS folder", cache);
        final Object valFromCache = cache.get("hi");

        assertNotNull("Expected to load data from cache using [" + expDfltConsistentId1 + "] as PDS folder", valFromCache);
        assertTrue(expVal.equals(valFromCache));

        assertNodeIndexesInFolder(); //no new style nodes should be found
        stopGrid(0);
    }

    /**
     * Test case If there are no matching folders,
     * but the directory constains old-style consistent IDs
     * Ignite should print out a warning
     *
     * @throws Exception
     */
    public void testOldStyleNodeWithUnexpectedPort() throws Exception {
        //emulated old-style node with not appropriate consistent ID
        final String expDfltConsistentId1 = "127.0.0.1:49999";
        this.configuredConsistentId = expDfltConsistentId1; //this is for create old node folder
        final Ignite ignite = startActivateFillDataGrid(0);
        final String prevVerFolder = U.maskForFileName(ignite.cluster().localNode().consistentId().toString());
        final String path = new File(new File(U.defaultWorkDirectory(), "db"), prevVerFolder).getCanonicalPath();

        assertPdsDirsDefaultExist(prevVerFolder);
        stopAllGrids();

        this.configuredConsistentId = null;
        this.stringLogger = new GridStringLogger();
        startActivateGrid(0);
        assertNodeIndexesInFolder(0); //one 0 index folder is created

        final String wholeNodeLog = stringLogger.toString();
        stopAllGrids();

        assertTrue("Expected to warn user on existence of old style path",
            wholeNodeLog.contains("There is other non-empty storage folder under storage base directory"));

        assertTrue("Expected to warn user on existence of old style path [" + path + "]",
            wholeNodeLog.contains(path));

        stringLogger = null;
        startActivateGrid(0);
        assertNodeIndexesInFolder(0); //one 0 index folder is created
        stopAllGrids();
    }

    /**
     * @param indexes expected new style node indexes in folders
     * @throws IgniteCheckedException if failed
     */
    private void assertNodeIndexesInFolder(Integer... indexes) throws IgniteCheckedException {
        assertEquals(new TreeSet<>(Arrays.asList(indexes)), getAllNodeIndexesInFolder());
    }

    /**
     * @return set of all indexes of nodes found in work folder
     * @throws IgniteCheckedException if failed.
     */
    @NotNull private Set<Integer> getAllNodeIndexesInFolder() throws IgniteCheckedException {
        final File curFolder = new File(U.defaultWorkDirectory(), PdsConsistentIdGeneratingFoldersResolver.DB_DEFAULT_FOLDER);
        final Set<Integer> indexes = new TreeSet<>();
        final File[] files = curFolder.listFiles(PdsConsistentIdGeneratingFoldersResolver.DB_SUBFOLDERS_NEW_STYLE_FILTER);
        for (File file : files) {
            final PdsConsistentIdGeneratingFoldersResolver.NodeIndexAndUid uid
                = PdsConsistentIdGeneratingFoldersResolver.parseSubFolderName(file, log);
            if (uid != null)
                indexes.add(uid.nodeIndex());
        }
        return indexes;
    }

    /**
     * Checks existence of all storage-related directories
     *
     * @param subDirName sub directories name expected
     * @throws IgniteCheckedException if IO error occur
     */
    private void assertPdsDirsDefaultExist(String subDirName) throws IgniteCheckedException {
        assertDirectoryExist("binary_meta", subDirName);
        assertDirectoryExist(PersistentStoreConfiguration.DFLT_WAL_STORE_PATH, subDirName);
        assertDirectoryExist(PersistentStoreConfiguration.DFLT_WAL_ARCHIVE_PATH, subDirName);
        assertDirectoryExist(PdsConsistentIdGeneratingFoldersResolver.DB_DEFAULT_FOLDER, subDirName);
    }

    /**
     * Checks one folder existence
     *
     * @param subFolderNames subfolders array to touch
     * @throws IgniteCheckedException if IO error occur
     */
    private void assertDirectoryExist(String... subFolderNames) throws IgniteCheckedException {
        File curFolder = new File(U.defaultWorkDirectory());
        for (String name : subFolderNames) {
            curFolder = new File(curFolder, name);
        }
        final String path;
        try {
            path = curFolder.getCanonicalPath();
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to convert path: [" + curFolder.getAbsolutePath() + "]", e);
        }
        assertTrue("Directory " + Arrays.asList(subFolderNames).toString()
            + " is expected to exist [" + path + "]", curFolder.exists() && curFolder.isDirectory());
    }

}
