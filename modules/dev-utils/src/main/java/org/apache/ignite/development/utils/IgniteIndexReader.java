/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.development.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.processors.cache.persistence.AllocatedPageTracker;
import org.apache.ignite.internal.processors.cache.persistence.IndexStorageImpl;
import org.apache.ignite.internal.processors.cache.persistence.IndexStorageImpl.IndexItem;
import org.apache.ignite.internal.processors.cache.persistence.IndexStorageImpl.MetaStoreLeafIO;
import org.apache.ignite.internal.processors.cache.persistence.file.AsyncFileIOFactory;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStore;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreFactory;
import org.apache.ignite.internal.processors.cache.persistence.file.FileVersionCheckingFactory;
import org.apache.ignite.internal.processors.cache.persistence.freelist.io.PagesListMetaIO;
import org.apache.ignite.internal.processors.cache.persistence.freelist.io.PagesListNodeIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.AbstractDataPageIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusInnerIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusLeafIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusMetaIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.DataPagePayload;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageMetaIO;
import org.apache.ignite.internal.processors.cache.tree.PendingRowIO;
import org.apache.ignite.internal.processors.query.h2.database.io.H2ExtrasInnerIO;
import org.apache.ignite.internal.processors.query.h2.database.io.H2ExtrasLeafIO;
import org.apache.ignite.internal.processors.query.h2.database.io.H2InnerIO;
import org.apache.ignite.internal.processors.query.h2.database.io.H2LeafIO;
import org.apache.ignite.internal.processors.query.h2.database.io.H2RowLinkIO;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.GridStringBuilder;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_DATA;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_IDX;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.INDEX_PARTITION;
import static org.apache.ignite.internal.pagemem.PageIdUtils.flag;
import static org.apache.ignite.internal.pagemem.PageIdUtils.itemId;
import static org.apache.ignite.internal.pagemem.PageIdUtils.pageId;
import static org.apache.ignite.internal.pagemem.PageIdUtils.pageIndex;
import static org.apache.ignite.internal.pagemem.PageIdUtils.partId;
import static org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreManager.INDEX_FILE_NAME;
import static org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreManager.PART_FILE_TEMPLATE;
import static org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO.getPageIO;
import static org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO.getType;
import static org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO.getVersion;
import static org.apache.ignite.internal.util.GridUnsafe.allocateBuffer;
import static org.apache.ignite.internal.util.GridUnsafe.bufferAddress;
import static org.apache.ignite.internal.util.GridUnsafe.freeBuffer;
import static org.apache.ignite.internal.util.GridUnsafe.reallocateBuffer;

/**
 *
 */
public class IgniteIndexReader {
    /** */
    private static final String META_TREE_NAME = "MetaTree";

    static {
        PageIO.registerH2(H2InnerIO.VERSIONS, H2LeafIO.VERSIONS);

        H2ExtrasInnerIO.register();
        H2ExtrasLeafIO.register();
    }

    /** */
    private final int pageSize;

    /** */
    private final int partCnt;

    /** */
    private final File cacheWorkDir;

    /** */
    private final DataStorageConfiguration dsCfg;

    /** */
    private final FilePageStoreFactory storeFactory;

    /** */
    private final AllocatedPageTracker allocatedTracker = AllocatedPageTracker.NO_OP;

    /** */
    private final PrintStream outStream;

    /** */
    private final PrintStream outErrStream;

    /** */
    private final File idxFile;

    /** */
    private final FilePageStore idxStore;

    /** */
    private final FilePageStore[] partStores;

    /** */
    private final long pagesNum;

    /** */
    private final Set<Integer> missingPartitions = new HashSet<>();

    /** */
    private PageIOProcessor innerPageIOProcessor = new InnerPageIOProcessor();

    /** */
    private PageIOProcessor leafPageIOProcessor = new LeafPageIOProcessor();

    /** */
    private final Map<Class, PageIOProcessor> ioProcessorsMap = new HashMap<Class, PageIOProcessor>() {{
        put(BPlusMetaIO.class, new MetaPageIOProcessor());
        put(BPlusInnerIO.class, innerPageIOProcessor);
        put(H2ExtrasInnerIO.class, innerPageIOProcessor);
        put(IndexStorageImpl.MetaStoreInnerIO.class, innerPageIOProcessor);
        put(BPlusLeafIO.class, leafPageIOProcessor);
        put(H2ExtrasLeafIO.class, leafPageIOProcessor);
        put(MetaStoreLeafIO.class, leafPageIOProcessor);
    }};

    /** */
    public IgniteIndexReader(String cacheWorkDirPath, int pageSize, int partCnt, int filePageStoreVer, String outputFile)
        throws IgniteCheckedException {
        this.pageSize = pageSize;
        this.partCnt = partCnt;
        this.dsCfg = new DataStorageConfiguration().setPageSize(pageSize);
        this.cacheWorkDir = new File(cacheWorkDirPath);
        this.storeFactory = new FileVersionCheckingFactory(new AsyncFileIOFactory(), dsCfg, 0) {
            /** {@inheritDoc} */
            @Override public int latestVersion() {
                return filePageStoreVer;
            }
        };

        if (outputFile == null) {
            outStream = System.out;
            outErrStream = System.out;
        }
        else {
            try {
                this.outStream = new PrintStream(new FileOutputStream(outputFile));
                this.outErrStream = outStream;
            }
            catch (FileNotFoundException e) {
                throw new IgniteException(e.getMessage(), e);
            }
        }

        idxFile = getFile(INDEX_PARTITION);

        if (idxFile == null)
            throw new RuntimeException("index.bin file not found");

        idxStore = storeFactory.createPageStore(FLAG_IDX, idxFile, allocatedTracker);

        pagesNum = (idxFile.length() - idxStore.headerSize()) / pageSize;

        partStores = new FilePageStore[partCnt];

        for (int i = 0; i < partCnt; i++) {
            final File file = getFile(i);

            // Some of array members will be null if node doesn't have all partition files locally.
            if (file != null)
                partStores[i] = storeFactory.createPageStore(FLAG_DATA, file, allocatedTracker);
        }
    }

    /** */
    private void print(String s) {
        outStream.println(s);
    }

    /** */
    private void printErr(String s) {
        outErrStream.println(s);
    }

    /** */
    private void printStackTrace(Throwable e) {
        OutputStream os = new StringBuilderOutputStream();

        e.printStackTrace(new PrintStream(os));

        printErr(os.toString());
    }

    /** */
    private static long normalizePageId(long pageId) {
        return pageId(partId(pageId), flag(pageId), pageIndex(pageId));
    }

    /** */
    private File getFile(int partId) {
        File file = new File(cacheWorkDir, partId == INDEX_PARTITION ? INDEX_FILE_NAME : String.format(PART_FILE_TEMPLATE, partId));

        if (!file.exists())
            return null;
        else if (partId == -1)
            print("Analyzing file: " + file.getPath());

        return file;
    }

    /** */
    private void readIdx() {
        long partPageStoresNum = Arrays.stream(partStores)
            .filter(Objects::nonNull)
            .count();

        print("Partitions files num: " + partPageStoresNum);

        Map<Class<? extends PageIO>, Long> pageClasses = new HashMap<>();

        print("Going to check " + pagesNum + " pages.");

        Map<Class, Set<Long>> pageIoIds = new HashMap<>();

        Map<String, TreeTraversalInfo> treeInfo = null;

        Map<String, TreeTraversalInfo> lvlInfo = null;

        AtomicReference<PageListsInfo> pageListsInfo = new AtomicReference<>();

        List<Throwable> errors = new LinkedList<>();

        ProgressPrinter progressPrinter = new ProgressPrinter(System.out, "Reading pages sequentially", pagesNum);

        for (int i = 0; i < pagesNum; i++) {
            ByteBuffer buf = allocateBuffer(pageSize);

            try {
                progressPrinter.printProgress();

                long addr = bufferAddress(buf);

                long pageId = PageIdUtils.pageId(INDEX_PARTITION, FLAG_IDX, i);

                //We got int overflow here on sber dataset.
                final long off = (long)i * pageSize + idxStore.headerSize();

                idxStore.readByOffset(off, buf, false);

                PageIO io = getPageIO(addr);

                pageClasses.merge(io.getClass(), 1L, (oldVal, newVal) -> ++oldVal);

                if (io instanceof PageMetaIO) {
                    PageMetaIO pageMetaIO = (PageMetaIO)io;

                    long metaTreeRootPageId = pageMetaIO.getTreeRoot(addr);

                    treeInfo = traverseAllTrees(metaTreeRootPageId);

                    treeInfo.forEach((name, info) -> {
                        info.innerPageIds.forEach(id -> {
                            Class cls = name.equals(META_TREE_NAME)
                                ? IndexStorageImpl.MetaStoreInnerIO.class
                                : H2ExtrasInnerIO.class;

                            pageIoIds.computeIfAbsent(cls, k -> new HashSet<>()).add(id);
                        });

                        pageIoIds.computeIfAbsent(BPlusMetaIO.class, k -> new HashSet<>()).add(info.rootPageId);
                    });

                    lvlInfo = traverseTreesByLvl(metaTreeRootPageId);

                    print("");
                }
                else if (io instanceof PagesListMetaIO)
                    pageListsInfo.set(getPageListsMetaInfo(pageId));
                else {
                    ofNullable(pageIoIds.get(io.getClass())).ifPresent((pageIds) -> {
                        if (!pageIds.contains(pageId)) {
                            boolean foundInList =
                                (pageListsInfo.get() != null && pageListsInfo.get().allPages.contains(pageId));

                            throw new IgniteException(
                                "Possibly orphan " + io.getClass().getSimpleName() + " page, pageId=" + pageId +
                                    (foundInList ? ", it has been found in page list." : "")
                            );
                        }
                    });
                }
            }
            catch (Throwable e) {
                String err = "Exception occurred on step " + i + ": " + e.getMessage() + "; page=" + U.toHexString(buf);

                errors.add(new IgniteException(err, e));
            }
            finally {
                freeBuffer(buf);
            }
        }

        if (treeInfo == null)
            printErr("No tree meta info found.");
        else
            printTraversalResults(treeInfo, "\nTree traversal results: ");

        if (lvlInfo == null)
            printErr("No tree meta info by lvl found.");
        else
            printTraversalResults(lvlInfo, "\nTree traversal by lvl results: ");

        if (pageListsInfo.get() == null)
            printErr("No page lists meta info found.");
        else
            printPagesListsInfo(pageListsInfo.get());

        printTraversesDiff(treeInfo, lvlInfo);

        print("\n---These pages types were encountered during sequential scan:");
        pageClasses.forEach((key, val) -> print(key.getSimpleName() + ": " + val));

        if (!errors.isEmpty()) {
            printErr("---");
            printErr("Errors:");

            errors.forEach(e -> printErr(e.toString()));
        }

        print("---");
        print("Total pages encountered during sequential scan: " + pageClasses.values().stream().mapToLong(a -> a).sum());
        print("Total errors occurred during sequential scan: " + errors.size());
        print("Note that some pages can be occupied by meta info, tracking info, etc., so total page count can differ " +
            "from count of pages found in index trees and page lists.");
    }

    private void printTraversesDiff(Map<String, TreeTraversalInfo> classicTraverse, Map<String, TreeTraversalInfo> lvlTraverse) {
        print("Printing diff for classic an level traversals");

        if (classicTraverse == null) {
            print("classicTraverse is null");

            return;
        }

        if (lvlTraverse == null) {
            print("lvlTraverse is null");

            return;
        }

        Set<String> classicUniqueIndexes = new HashSet<>(classicTraverse.keySet());
        Set<String> levelUniqueIndexes = new HashSet<>(lvlTraverse.keySet());

        classicUniqueIndexes.removeAll(lvlTraverse.keySet());
        levelUniqueIndexes.removeAll(classicTraverse.keySet());

        if (!classicUniqueIndexes.isEmpty()) {
            print("Classic traversal unique indexes:");
            classicUniqueIndexes.forEach(this::print);
            print("");
        }

        if (!levelUniqueIndexes.isEmpty()) {
            print("Level traversal unique indexes:");
            levelUniqueIndexes.forEach(this::print);
            print("");
        }

        classicTraverse.forEach((classicIdxName, classicTraversalInfo) -> {
            TreeTraversalInfo lvlTraversalInfo = lvlTraverse.get(classicIdxName);

            if (lvlTraversalInfo == null)
                return;

            if (!classicTraversalInfo.compareIoStats(lvlTraversalInfo))
                print("Different iostats for classic and level traversal for index: " + classicIdxName);
        });


        print("Finished printing diff for classic an level traversals");
    }

    /** */
    private PageListsInfo getPageListsMetaInfo(long metaPageListId) {
        Map<IgniteBiTuple<Long, Integer>, List<Long>> bucketsData = new HashMap<>();

        Set<Long> allPages = new HashSet<>();

        Map<Class, AtomicLong> pageListStat = new HashMap<>();

        Map<Long, Throwable> errors = new HashMap<>();

        long nextMetaId = metaPageListId;

        while (nextMetaId != 0) {
            ByteBuffer buf = allocateBuffer(pageSize);

            try {
                long addr = bufferAddress(buf);

                idxStore.read(nextMetaId, buf, false);

                PagesListMetaIO io = getPageIO(addr);

                Map<Integer, GridLongList> data = new HashMap<>();

                io.getBucketsData(addr, data);

                final long fNextMetaId = nextMetaId;

                data.forEach((k, v) -> {
                    List<Long> listIds = LongStream.of(v.array()).map(IgniteIndexReader::normalizePageId).boxed().collect(toList());

                    listIds.forEach(listId -> allPages.addAll(getPageList(listId, pageListStat)));

                    bucketsData.put(new IgniteBiTuple<>(fNextMetaId, k), listIds);
                });

                nextMetaId = io.getNextMetaPageId(addr);
            }
            catch (Exception e) {
                errors.put(nextMetaId, e);

                nextMetaId = 0;
            }
            finally {
                freeBuffer(buf);
            }
        }

        return new PageListsInfo(bucketsData, allPages, pageListStat, errors);
    }

    /** */
    private List<Long> getPageList(long pageListStartId, Map<Class, AtomicLong> pageStat) {
        List<Long> res = new LinkedList<>();

        long nextNodeId = pageListStartId;

        while (nextNodeId != 0) {
            ByteBuffer buf = allocateBuffer(pageSize);

            try {
                long addr = bufferAddress(buf);

                idxStore.read(nextNodeId, buf, false);

                PagesListNodeIO io = getPageIO(addr);

                for (int i = 0; i < io.getCount(addr); i++) {
                    long pageId = normalizePageId(io.getAt(addr, i));

                    res.add(pageId);

                    ByteBuffer pageBuf = allocateBuffer(pageSize);

                    try {
                        long pageAddr = bufferAddress(pageBuf);

                        idxStore.read(pageId, pageBuf, false);

                        PageIO pageIO = getPageIO(pageAddr);

                        pageStat.computeIfAbsent(pageIO.getClass(), k -> new AtomicLong(0)).incrementAndGet();
                    }
                    finally {
                        freeBuffer(pageBuf);
                    }
                }

                nextNodeId = io.getNextId(addr);
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e.getMessage(), e);
            }
            finally {
                freeBuffer(buf);
            }
        }

        return res;
    }

    /** */
    private Map<String, TreeTraversalInfo> traverseAllTrees(long metaTreeRootPageId) {
        Map<String, TreeTraversalInfo> treeInfos = new HashMap<>();

        TreeTraversalInfo metaTreeTraversalInfo = traverseTree(metaTreeRootPageId, true);

        treeInfos.put(META_TREE_NAME, metaTreeTraversalInfo);

        ProgressPrinter progressPrinter = new ProgressPrinter(System.out, "Index trees traversal", metaTreeTraversalInfo.idxItems.size());

        metaTreeTraversalInfo.idxItems.forEach(item -> {
            progressPrinter.printProgress();

            IndexItem idxItem = (IndexItem)item;

            TreeTraversalInfo treeTraversalInfo = traverseTree(idxItem.pageId(), false);

            treeInfos.put(idxItem.toString(), treeTraversalInfo);
        });

        return treeInfos;
    }

    /** */
    private Map<String, TreeTraversalInfo> traverseTreesByLvl(long metaTreeRootPageId) {
        Map<String, TreeTraversalInfo> res = new HashMap<>();

        TreeTraversalInfo metaTreeTraversalInfo = traverseTree(metaTreeRootPageId, true);

        ByteBuffer buf = allocateBuffer(pageSize);
        try {
            for (Object indexItemObj : metaTreeTraversalInfo.idxItems) {
                IndexItem indexItem = (IndexItem)indexItemObj;

                if (indexItem == null) {
                    print("Unexpected indexItem class: " + indexItem.getClass().getSimpleName());

                    return null;
                }

                Map<Class, AtomicLong> ioStat = new HashMap<>();
                AtomicLong cnt = new AtomicLong();

                buf = reallocateBuffer(buf, pageSize);
                idxStore.read(indexItem.pageId(), buf, false);

                long addr = bufferAddress(buf);
                PageIO io = getPageIO(addr);

                ioStat.computeIfAbsent(io.getClass(), cls -> new AtomicLong()).incrementAndGet();

                if (!BPlusMetaIO.class.isInstance(io))
                    continue;

                BPlusMetaIO bPlusMetaIO = (BPlusMetaIO)io;
                int lvlCnt = bPlusMetaIO.getLevelsCount(addr);

                ByteBuffer pageBuf = allocateBuffer(pageSize);
                try {
                    for (int i = 0; i < lvlCnt; i++) {
                        long pageId = bPlusMetaIO.getFirstPageId(addr, i);

                        while (pageId != 0) {
                            pageBuf = reallocateBuffer(pageBuf, pageSize);
                            idxStore.read(pageId, pageBuf, false);

                            long pageAddr = bufferAddress(pageBuf);
                            PageIO pageIO = getPageIO(pageAddr);

                            ioStat.computeIfAbsent(pageIO.getClass(), cls -> new AtomicLong()).incrementAndGet();

                            if (!BPlusIO.class.isInstance(pageIO))
                                continue;

                            BPlusIO bPlusIO = (BPlusIO)pageIO;

                            if (bPlusIO.isLeaf())
                                cnt.addAndGet(bPlusIO.getCount(pageAddr));

                            pageId = bPlusIO.getForward(pageAddr);
                        }
                    }
                }
                finally {
                    freeBuffer(pageBuf);
                }

                res.put(
                    indexItem.toString(),
                    new TreeTraversalInfo(ioStat, emptyMap(), null, indexItem.pageId(), cnt.get())
                );
            }
        }
        catch (IgniteCheckedException e) {
            e.printStackTrace();
            return null;
        }
        finally {
            freeBuffer(buf);
        }
        return res;
    }

    /**
     * Method doesn't work if number of indexes is big enough.
     */
    private List<IndexItem> indexItems(long metaTreeRootPageId) {
        ByteBuffer buf = allocateBuffer(pageSize);
        try {
            idxStore.read(metaTreeRootPageId, buf, false);

            long addr = bufferAddress(buf);
            PageIO io = getPageIO(addr);

            if (!BPlusMetaIO.class.isInstance(io))
                return emptyList();

            BPlusMetaIO bPlusMetaIO = (BPlusMetaIO)io;

            int rootLvl = bPlusMetaIO.getRootLevel(addr);
            long rootId = bPlusMetaIO.getFirstPageId(addr, rootLvl);

            buf = reallocateBuffer(buf, pageSize);
            idxStore.read(rootId, buf, false);

            long rootAddr = bufferAddress(buf);
            PageIO rootIo = getPageIO(rootAddr);

            // Always true for big trees
            if (!MetaStoreLeafIO.class.isInstance(rootIo))
                return emptyList();

            MetaStoreLeafIO metaStoreLeafIO = (MetaStoreLeafIO)rootIo;
            int cnt = metaStoreLeafIO.getCount(rootAddr);

            List<IndexItem> indexItems = new ArrayList<>(cnt);

            for (int i = 0; i < cnt; i++)
                indexItems.add(metaStoreLeafIO.getLookupRow(null, rootAddr, i));

            return indexItems;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
        finally {
            freeBuffer(buf);
        }
    }

    /** */
    private void printTraversalResults(Map<String, TreeTraversalInfo> treeInfos, String header) {
        print(header);

        Map<Class, AtomicLong> totalStat = new HashMap<>();

        AtomicInteger totalErr = new AtomicInteger(0);

        treeInfos.forEach((idxName, validationInfo) -> {
            print("-----");
            print("Index tree: " + idxName);
            print("-- Page stat:");

            validationInfo.ioStat.forEach((cls, cnt) -> {
                print(cls.getSimpleName() + ": " + cnt.get());

                totalStat.computeIfAbsent(cls, k -> new AtomicLong(0)).addAndGet(cnt.get());
            });

            print("-- Count of items found in leaf pages: " + validationInfo.itemsCnt);

            if (validationInfo.errors.size() > 0) {
                print("-- Errors:");

                validationInfo.errors.forEach((id, errors) -> {
                    print("Page id=" + id + ", exceptions:");

                    errors.forEach(this::printStackTrace);

                    totalErr.addAndGet(errors.size());
                });
            }
            else
                print("No errors occurred while traversing.");
        });

        print("---");
        print("Total page stat collected during trees traversal:");

        totalStat.forEach((cls, cnt) -> print(cls.getSimpleName() + ": " + cnt.get()));

        print("");
        print("Total trees: " + treeInfos.keySet().size());
        print("Total pages found in trees: " + totalStat.values().stream().mapToLong(AtomicLong::get).sum());
        print("Total errors during trees traversal: " + totalErr.get());
        print("------------------");
    }

    /** */
    private void printPagesListsInfo(PageListsInfo pageListsInfo) {
        print("\n---Page lists info.");

        if (pageListsInfo.bucketsData.size() > 0)
            print("---Printing buckets data:");

        pageListsInfo.bucketsData.forEach((bucket, bucketData) -> {
            GridStringBuilder sb = new GridStringBuilder()
                .a("List meta id=")
                .a(bucket.get1())
                .a(", bucket number=")
                .a(bucket.get2())
                .a(", lists=[")
                .a(bucketData.stream().map(String::valueOf).collect(joining(", ")))
                .a("]");

            print(sb.toString());
        });

        if (pageListsInfo.allPages.size() > 0) {
            print("-- Page stat:");

            pageListsInfo.pageListStat.forEach((cls, cnt) -> print(cls.getSimpleName() + ": " + cnt.get()));
        }

        if (!pageListsInfo.errors.isEmpty()) {
            print("---Errors:");

            pageListsInfo.errors.forEach((id, error) -> {
                printErr("Page id: " + id + ", exception: ");

                printStackTrace(error);
            });
        }

        print("");
        print("Total index pages found in lists: " + pageListsInfo.allPages.size());
        print("Total errors during lists scan: " + pageListsInfo.errors.size());
        print("------------------");
    }

    /** */
    private TreeTraversalInfo traverseTree(long rootPageId, boolean isMetaTree) {
        Map<Class, AtomicLong> ioStat = new HashMap<>();

        Map<Long, Set<Throwable>> errors = new HashMap<>();

        Set<Long> innerPageIds = new HashSet<>();

        PageCallback innerCb = (content, pageId) -> innerPageIds.add(pageId);

        List<Object> idxItems = new LinkedList<>();

        AtomicLong idxItemsCnt = new AtomicLong(0);

        ItemCallback itemCb = isMetaTree
            ? (currPageId, item, link) -> idxItems.add(item)
            : (currPageId, item, link) -> idxItemsCnt.incrementAndGet();

        getTreeNode(rootPageId, new TreeNodeContext(idxStore, ioStat, errors, innerCb, null, itemCb));

        return isMetaTree
            ? new TreeTraversalInfo(ioStat, errors, innerPageIds, rootPageId, idxItems)
            : new TreeTraversalInfo(ioStat, errors, innerPageIds, rootPageId, idxItemsCnt.get());
    }

    /** */
    private TreeNode getTreeNode(long pageId, TreeNodeContext nodeCtx) {
        Class ioCls;

        PageContent pageContent;

        PageIOProcessor ioProcessor;

        try {
            final ByteBuffer buf = allocateBuffer(pageSize);

            try {
                nodeCtx.store.read(pageId, buf, false);

                final long addr = bufferAddress(buf);

                final PageIO io = getPageIO(addr);

                ioCls = io.getClass();

                nodeCtx.ioStat.computeIfAbsent(io.getClass(), k -> new AtomicLong(0)).incrementAndGet();

                ioProcessor = ioProcessorsMap.getOrDefault(ioCls, getDefaultIoProcessor(io));

                if (ioProcessor == null)
                    throw new IgniteException("Unexpected page io: " + ioCls.getSimpleName());

                pageContent = ioProcessor.getContent(io, addr, pageId, nodeCtx);
            }
            finally {
                freeBuffer(buf);
            }

            return ioProcessor.getNode(pageContent, pageId, nodeCtx);
        }
        catch (Exception e) {
            nodeCtx.errors.computeIfAbsent(pageId, k -> new HashSet<>()).add(e);

            return new TreeNode(pageId, null, "exception: " + e.getMessage(), Collections.emptyList());
        }
    }

    /** */
    private PageIOProcessor getDefaultIoProcessor(PageIO io) {
        if (io instanceof BPlusInnerIO)
            return ioProcessorsMap.get(BPlusInnerIO.class);
        else if (io instanceof BPlusLeafIO)
            return ioProcessorsMap.get(BPlusLeafIO.class);
        else
            return null;
    }

    /** */
    private static <T> T getOptionFromMap(Map<String, String> options, String name, Class<T> cls, Supplier<T> dfltVal) {
        String s = options.get(name);

        if (s == null)
            return dfltVal.get();

        T val = null;

        if (cls.equals(String.class))
            val = (T)s;
        else if (cls.equals(Integer.class))
            val = (T)new Integer(Integer.parseInt(s));

        return val == null ? dfltVal.get() : val;
    }

    /**
     * Entry point.
     * @param args Arguments.
     * @throws Exception If failed.
     */
    public static void main(String[] args) throws Exception {
        try {
            Map<String, String> options = new HashMap<String, String>() {{
                put("--dir", null);
                put("--partCnt", null);
                put("--pageSize", null);
                put("--pageStoreVer", null);
                put("--destFile", null);
            }};

            for (Iterator<String> iterator = Arrays.asList(args).iterator(); iterator.hasNext(); ) {
                String option = iterator.next();

                if (!options.containsKey(option))
                    throw new Exception("Unexpected option: " + option);

                if (!iterator.hasNext())
                    throw new Exception("Please specify a value for option: " + option);

                String val = iterator.next();

                options.put(option, val);
            }

            String dir = getOptionFromMap(options, "--dir", String.class, () -> { throw new IgniteException("File path was not specified."); } );

            int partCnt = getOptionFromMap(options, "--partCnt", Integer.class, () -> 0);

            int pageSize = getOptionFromMap(options, "--pageSize", Integer.class, () -> 4096);

            int pageStoreVer = getOptionFromMap(options, "--pageStoreVer", Integer.class, () -> 2);

            String destFile = getOptionFromMap(options, "--destFile", String.class, () -> null);

            new IgniteIndexReader(dir, pageSize, partCnt, pageStoreVer, destFile)
                .readIdx();
        }
        catch (Exception e) {
            System.err.println("How to use: please pass option names, followed by space and option values. Options list:");
            System.err.println("--dir: partition directory, where index.bin and (optionally) partition files are located (obligatory)");
            System.err.println("--partCnt: full partitions count in cache group (optional)");
            System.err.println("--pageSize: page size (optional, default value is 4096)");
            System.err.println("--pageStoreVer: page store version (optional, default value is 2)");
            System.err.println("--destFile: file to print the report to (optional, by default report is printed to console)");

            throw e;
        }
    }

    /**
     *
     */
    private static class TreeNode {
        final long pageId;
        final PageIO io;
        final String additionalInfo;
        final List<TreeNode> children;

        /** */
        public TreeNode(long pageId, PageIO io, String additionalInfo, List<TreeNode> children) {
            this.pageId = pageId;
            this.io = io;
            this.additionalInfo = additionalInfo;
            this.children = children;
        }
    }

    /**
     *
     */
    private static class TreeNodeContext {
        final FilePageStore store;
        final Map<Class, AtomicLong> ioStat;
        final Map<Long, Set<Throwable>> errors;
        final PageCallback innerCb;
        final PageCallback leafCb;
        final ItemCallback itemCb;

        /** */
        private TreeNodeContext(
            FilePageStore store,
            Map<Class, AtomicLong> ioStat,
            Map<Long, Set<Throwable>> errors,
            PageCallback innerCb,
            PageCallback leafCb,
            ItemCallback itemCb
        ) {
            this.store = store;
            this.ioStat = ioStat;
            this.errors = errors;
            this.innerCb = innerCb;
            this.leafCb = leafCb;
            this.itemCb = itemCb;
        }
    }

    /**
     *
     */
    private static class PageContent {
        final PageIO io;
        final List<Long> linkedPageIds;
        final List<Object> items;
        final String info;

        /** */
        public PageContent(PageIO io, List<Long> linkedPageIds, List<Object> items, String info) {
            this.io = io;
            this.linkedPageIds = linkedPageIds;
            this.items = items;
            this.info = info;
        }
    }

    /**
     *
     */
    private interface PageCallback {
        /** */
        void cb(PageContent pageContent, long pageId);
    }

    /**
     *
     */
    private interface ItemCallback {
        /** */
        void cb(long currPageId, Object item, long link);
    }

    /**
     *
     */
    private interface PageIOProcessor {
        /** */
        PageContent getContent(PageIO io, long addr, long pageId, TreeNodeContext nodeCtx);
        /** */
        TreeNode getNode(PageContent content, long pageId, TreeNodeContext nodeCtx);
    }

    /**
     *
     */
    private class MetaPageIOProcessor implements PageIOProcessor {
        /** {@inheritDoc} */
        @Override public PageContent getContent(PageIO io, long addr, long pageId, TreeNodeContext nodeCtx) {
            BPlusMetaIO bPlusMetaIO = (BPlusMetaIO)io;

            int rootLvl = bPlusMetaIO.getRootLevel(addr);
            long rootId = bPlusMetaIO.getFirstPageId(addr, rootLvl);

            return new PageContent(io, singletonList(rootId), null, null);
        }

        /** {@inheritDoc} */
        @Override public TreeNode getNode(PageContent content, long pageId, TreeNodeContext nodeCtx) {
            return new TreeNode(pageId, content.io, null, singletonList(getTreeNode(content.linkedPageIds.get(0), nodeCtx)));
        }
    }

    /**
     *
     */
    private class InnerPageIOProcessor implements PageIOProcessor {
        /** {@inheritDoc} */
        @Override public PageContent getContent(PageIO io, long addr, long pageId, TreeNodeContext nodeCtx) {
            BPlusInnerIO innerIo = (BPlusInnerIO)io;

            int cnt = innerIo.getCount(addr);

            List<Long> childrenIds;

            if (cnt > 0) {
                childrenIds = new ArrayList<>(cnt + 1);

                for (int i = 0; i < cnt; i++)
                    childrenIds.add(innerIo.getLeft(addr, i));

                childrenIds.add(innerIo.getRight(addr, cnt - 1));
            }
            else {
                long left = innerIo.getLeft(addr, 0);

                childrenIds = left == 0 ? Collections.<Long>emptyList() : singletonList(left);
            }

            return new PageContent(io, childrenIds, null, null);
        }

        /** {@inheritDoc} */
        @Override public TreeNode getNode(PageContent content, long pageId, TreeNodeContext nodeCtx) {
            List<TreeNode> children = new ArrayList<>(content.linkedPageIds.size());

            for (Long id : content.linkedPageIds)
                children.add(getTreeNode(id, nodeCtx));

            if (nodeCtx.innerCb != null)
                nodeCtx.innerCb.cb(content, pageId);

            return new TreeNode(pageId, content.io, null, children);
        }
    }

    /**
     *
     */
    private class LeafPageIOProcessor implements PageIOProcessor {
        /** {@inheritDoc} */
        @Override public PageContent getContent(PageIO io, long addr, long pageId, TreeNodeContext nodeCtx) {
            GridStringBuilder sb = new GridStringBuilder();

            List<Object> items = new LinkedList<>();

            if (io instanceof MetaStoreLeafIO) {
                MetaStoreLeafIO metaLeafIO = (MetaStoreLeafIO)io;

                for (int j = 0; j < metaLeafIO.getCount(addr); j++) {
                    IndexItem indexItem = null;

                    try {
                        indexItem = metaLeafIO.getLookupRow(null, addr, j);
                    }
                    catch (IgniteCheckedException e) {
                        throw new IgniteException(e);
                    }

                    if (indexItem.pageId() != 0) {
                        sb.a(indexItem.toString() + " ");

                        items.add(indexItem);
                    }
                }
            }
            else {
                boolean processed = processIndexLeaf(io, addr, pageId, items, nodeCtx);

                if (!processed)
                    throw new IgniteException("Unexpected page io: " + io.getClass().getSimpleName());
            }

            return new PageContent(io, null, items, sb.toString());
        }

        /** */
        private boolean processIndexLeaf(PageIO io, long addr, long pageId, List<Object> items, TreeNodeContext nodeCtx) {
            if (io instanceof BPlusIO && (io instanceof H2RowLinkIO || io instanceof PendingRowIO)) {
                int itemsCnt = ((BPlusIO)io).getCount(addr);

                for (int j = 0; j < itemsCnt; j++) {
                    long link = 0;

                    if (io instanceof H2RowLinkIO)
                        link = ((H2RowLinkIO)io).getLink(addr, j);
                    else if (io instanceof PendingRowIO)
                        link = ((PendingRowIO)io).getLink(addr, j);

                    if (link == 0)
                        throw new IgniteException("No link to data page on idx=" + j);

                    items.add(link);

                    if (partCnt > 0) {
                        long linkedPageId = pageId(link);

                        int linkedPagePartId = partId(linkedPageId);

                        if (missingPartitions.contains(linkedPagePartId))
                            continue;

                        int linkedItemId = itemId(link);

                        ByteBuffer dataBuf = allocateBuffer(pageSize);

                        try {
                            long dataBufAddr = bufferAddress(dataBuf);

                            if (linkedPagePartId > partStores.length - 1) {
                                missingPartitions.add(linkedPagePartId);

                                throw new IgniteException("Calculated data page partition id exceeds given partitions count: " +
                                    linkedPagePartId + ", partCnt=" + partCnt);
                            }

                            final FilePageStore store = partStores[linkedPagePartId];

                            if (store == null) {
                                missingPartitions.add(linkedPagePartId);

                                throw new IgniteException("Corresponding store wasn't found for partId=" + linkedPagePartId + ". Does partition file exist?");
                            }

                            store.read(linkedPageId, dataBuf, false);

                            PageIO dataIo = getPageIO(getType(dataBuf), getVersion(dataBuf));

                            if (dataIo instanceof AbstractDataPageIO) {
                                AbstractDataPageIO dataPageIO = (AbstractDataPageIO)dataIo;

                                DataPagePayload payload = dataPageIO.readPayload(dataBufAddr, linkedItemId, pageSize);

                                if (payload.offset() <= 0 || payload.payloadSize() <= 0) {
                                    GridStringBuilder payloadInfo = new GridStringBuilder("Invalid data page payload: ")
                                        .a("off=").a(payload.offset())
                                        .a(", size=").a(payload.payloadSize())
                                        .a(", nextLink=").a(payload.nextLink());

                                    throw new IgniteException(payloadInfo.toString());
                                }
                            }
                        }
                        catch (Exception e) {
                            nodeCtx.errors.computeIfAbsent(pageId, k -> new HashSet<>()).add(e);
                        }
                        finally {
                            freeBuffer(dataBuf);
                        }
                    }
                }

                return true;
            }
            else
                return false;
        }

        /** {@inheritDoc} */
        @Override public TreeNode getNode(PageContent content, long pageId, TreeNodeContext nodeCtx) {
            if (nodeCtx.leafCb != null)
                nodeCtx.leafCb.cb(content, pageId);

            if (nodeCtx.itemCb != null) {
                for (Object item : content.items)
                    nodeCtx.itemCb.cb(pageId, item, 0);
            }

            return new TreeNode(pageId, content.io, content.info, Collections.emptyList());
        }
    }

    /**
     *
     */
    private static class TreeTraversalInfo {
        final Map<Class, AtomicLong> ioStat;
        final Map<Long, Set<Throwable>> errors;
        final Set<Long> innerPageIds;
        final long rootPageId;
        final List<Object> idxItems;
        final long itemsCnt;

        /** */
        public TreeTraversalInfo(
            Map<Class, AtomicLong> ioStat,
            Map<Long, Set<Throwable>> errors,
            Set<Long> innerPageIds,
            long rootPageId,
            List<Object> idxItems
        ) {
            this.ioStat = ioStat;
            this.errors = errors;
            this.innerPageIds = innerPageIds;
            this.rootPageId = rootPageId;
            this.idxItems = idxItems;
            this.itemsCnt = idxItems.size();
        }

        public TreeTraversalInfo(
            Map<Class, AtomicLong> ioStat,
            Map<Long, Set<Throwable>> errors,
            Set<Long> innerPageIds,
            long rootPageId,
            long itemsCnt
        ) {
            this.ioStat = ioStat;
            this.errors = errors;
            this.innerPageIds = innerPageIds;
            this.rootPageId = rootPageId;
            this.idxItems = null;
            this.itemsCnt = itemsCnt;
        }

        boolean compareIoStats(TreeTraversalInfo other) {
            Map<Class, AtomicLong> otherIoStat = other.ioStat;

            if(otherIoStat.size() != ioStat.size())
                return false;

            for (Map.Entry<Class, AtomicLong> entry: ioStat.entrySet()) {
                AtomicLong otherCntr = otherIoStat.get(entry.getKey());

                if (otherCntr == null)
                    return false;

                if (otherCntr.get() != entry.getValue().get())
                    return false;
            }

            return true;
        }
    }

    /**
     *
     */
    private static class PageListsInfo {
        final Map<IgniteBiTuple<Long, Integer>, List<Long>> bucketsData;
        final Set<Long> allPages;
        final Map<Class, AtomicLong> pageListStat;
        final Map<Long, Throwable> errors;

        /** */
        public PageListsInfo(
            Map<IgniteBiTuple<Long, Integer>, List<Long>> bucketsData,
            Set<Long> allPages,
            Map<Class, AtomicLong> pageListStat,
            Map<Long, Throwable> errors
        ) {
            this.bucketsData = bucketsData;
            this.allPages = allPages;
            this.pageListStat = pageListStat;
            this.errors = errors;
        }
    }
}
