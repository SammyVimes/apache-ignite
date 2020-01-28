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

package org.apache.ignite.internal.processors.cache.checker.util;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.CacheObjectContext;
import org.apache.ignite.internal.processors.cache.CacheObjectImpl;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.KeyCacheObjectImpl;
import org.apache.ignite.internal.processors.cache.checker.objects.VersionedValue;
import org.apache.ignite.internal.processors.cache.verify.PartitionReconciliationDataRowMeta;
import org.apache.ignite.internal.processors.cache.verify.PartitionReconciliationKeyMeta;
import org.apache.ignite.internal.processors.cache.verify.PartitionReconciliationValueMeta;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.typedef.internal.U;

import static java.io.File.separatorChar;
import static org.apache.ignite.IgniteSystemProperties.getInteger;
import static org.apache.ignite.cache.CacheMode.REPLICATED;

/**
 *
 */
public class ConsistencyCheckUtils {
    /**
     * Folder with local result of reconciliation.
     */
    public static final String RECONCILIATION_DIR = "reconciliation";

    /**
     *
     */
    public static final String AVAILABLE_PROCESSORS_RECONCILIATION = "AVAILABLE_PROCESSORS_RECONCILIATION";

    /** Time formatter for log file name. */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss_SSS");

    /**
     *
     */
    public static Map<KeyCacheObject, Map<UUID, GridCacheVersion>> checkConflicts(
        Map<KeyCacheObject, Map<UUID, GridCacheVersion>> oldKeys,
        Map<KeyCacheObject, Map<UUID, VersionedValue>> actualKeys,
        GridCacheContext cctx,
        AffinityTopologyVersion startTopVer
    ) {
        Map<KeyCacheObject, Map<UUID, GridCacheVersion>> keysWithConflicts = new HashMap<>();

        // Actual keys are a subset of old keys.
        // TODO: 05.12.19 Seems that it's not correct to use keyCacheObject.equals() here.
        for (Map.Entry<KeyCacheObject, Map<UUID, GridCacheVersion>> keyEntry : oldKeys.entrySet()) {
            KeyCacheObject key = keyEntry.getKey();
            Map<UUID, GridCacheVersion> oldKeyVers = keyEntry.getValue();
            Map<UUID, VersionedValue> newKeyVers = actualKeys.get(key);

            if (newKeyVers != null) {
                int ownerSize = cctx.topology().owners(cctx.affinity().partition(key), startTopVer).size();

                if (oldKeyVers.size() != ownerSize) {
                    boolean rmv = oldKeyVers.keySet().stream()
                        .anyMatch(nodeId -> !newKeyVers.containsKey(nodeId));

                    if (rmv)
                        continue;

                    boolean maxVerChanged = findMaxVersionSet(oldKeyVers).stream()
                        .anyMatch(nodeId -> newKeyVers.get(nodeId).version().isGreater(oldKeyVers.get(nodeId)));

                    if (maxVerChanged)
                        continue;

                    GridCacheVersion maxOldVer = oldKeyVers.values().stream()
                        .max(GridCacheVersion::compareTo)
                        .orElseThrow(NoSuchElementException::new);

                    boolean missingMaxElement = newKeyVers.values().stream().map(VersionedValue::version)
                        .anyMatch(v -> v.isGreater(maxOldVer));

                    if (missingMaxElement) {
                        keysWithConflicts.put(
                            keyEntry.getKey(),
                            newKeyVers.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().version()))
                        );

                        continue;
                    }
                }

                // Elements with min GridCacheVersion should increment
                if ((oldKeyVers.size() == ownerSize && oldKeyVers.size() != newKeyVers.size()) ||
                    !checkConsistency(oldKeyVers, newKeyVers, ownerSize))
                    keysWithConflicts.put(keyEntry.getKey(), oldKeyVers);
            }
        }

        return keysWithConflicts;
    }

    /**
     *
     */
    public static boolean checkConsistency(Map<UUID, GridCacheVersion> oldKeyVers,
        Map<UUID, VersionedValue> actualKeyVers, int ownerSize) {

        assert !oldKeyVers.isEmpty();

        if (actualKeyVers.isEmpty())
            return true;

        Set<UUID> maxVersions = findMaxVersionSet(oldKeyVers);

        GridCacheVersion maxVer = oldKeyVers.get(maxVersions.iterator().next());

        for (UUID maxVerOwner : maxVersions) {
            VersionedValue verVal = actualKeyVers.get(maxVerOwner);
            if (verVal == null || maxVer.isLess(verVal.version()))
                return true;
        }

        if (ownerSize != actualKeyVers.size())
            return false;

        boolean allNonMaxChanged = true;

        for (Map.Entry<UUID, GridCacheVersion> oldEntry : oldKeyVers.entrySet()) {
            if (!actualKeyVers.containsKey(oldEntry.getKey()))
                return true;
        }

        for (VersionedValue actualKeyVer : actualKeyVers.values()) {
            if (actualKeyVer.version().isGreater(maxVer))
                return true;

            if (actualKeyVer.version().isLess(maxVer)) {
                allNonMaxChanged = false;

                break;
            }
        }

        return allNonMaxChanged;
    }

    /**
     *
     */
    private static Set<UUID> findMaxVersionSet(Map<UUID, GridCacheVersion> verSet) {
        //TODO Possible you can check it use only one iteration.
        Set<UUID> maxVersions = new HashSet<>();

        maxVersions.add(verSet.keySet().iterator().next());

        for (Map.Entry<UUID, GridCacheVersion> entry : verSet.entrySet()) {
            GridCacheVersion lastMaxVer = verSet.get(maxVersions.iterator().next());
            GridCacheVersion curVer = entry.getValue();

            if (curVer.isGreater(lastMaxVer)) {
                maxVersions.clear();
                maxVersions.add(entry.getKey());
            }
            else if (curVer.equals(lastMaxVer))
                maxVersions.add(entry.getKey());
        }

        return maxVersions;
    }

    /**
     *
     */
    public static KeyCacheObject unmarshalKey(KeyCacheObject unmarshalKey,
        GridCacheContext<Object, Object> cctx) throws IgniteCheckedException {
        if (unmarshalKey == null)
            return null;

        unmarshalKey.finishUnmarshal(cctx.cacheObjectContext(), null);

        return unmarshalKey;
    }

    /**
     *
     */
    public static List<PartitionReconciliationDataRowMeta> mapPartitionReconciliation(
        Map<KeyCacheObject, Map<UUID, GridCacheVersion>> conflicts,
        Map<KeyCacheObject, Map<UUID, VersionedValue>> actualKeys,
        CacheObjectContext ctx
    ) throws IgniteCheckedException {
        List<PartitionReconciliationDataRowMeta> brokenKeys = new ArrayList<>();

        for (Map.Entry<KeyCacheObject, Map<UUID, GridCacheVersion>> entry : conflicts.entrySet()) {
            KeyCacheObject key = entry.getKey();

            Map<UUID, PartitionReconciliationValueMeta> valMap = new HashMap<>();

            for (Map.Entry<UUID, GridCacheVersion> versionEntry : entry.getValue().entrySet()) {
                UUID nodeId = versionEntry.getKey();

                Optional<CacheObject> cacheObjOpt = Optional.ofNullable(actualKeys.get(key))
                    .flatMap(keyVersions -> Optional.ofNullable(keyVersions.get(nodeId)))
                    .map(VersionedValue::value);

                valMap.put(
                    nodeId,
                    cacheObjOpt.isPresent() ?
                        new PartitionReconciliationValueMeta(
                            cacheObjOpt.get().valueBytes(ctx),
                            cacheObjOpt.map(o -> objectStringView(ctx, o)).orElse(null),
                            versionEntry.getValue())
                        :
                        null);
            }

            brokenKeys.add(
                new PartitionReconciliationDataRowMeta(
                    new PartitionReconciliationKeyMeta(
                        key.valueBytes(ctx),
                        objectStringView(ctx, key)),
                    valMap
                ));
        }

        return brokenKeys;
    }

    /**
     * @param startTime Operation start time.
     */
    public static File createLocalResultFile(
        ClusterNode locNode,
        LocalDateTime startTime
    ) throws IgniteCheckedException, IOException {
        String maskId = U.maskForFileName(locNode.consistentId().toString());

        File dir = new File(U.defaultWorkDirectory() + separatorChar + RECONCILIATION_DIR);

        if (!dir.exists())
            dir.mkdir();

        File file = new File(dir.getPath() + separatorChar + maskId + "_" + startTime.format(TIME_FORMATTER) +
            ".txt");

        if (!file.exists())
            file.createNewFile();

        return file;
    }

    /**
     *
     */
    public static int parallelismLevel(double loadFactor, Collection<String> caches, IgniteEx ignite) {
        assert loadFactor > 0 && loadFactor <= 1;

        int totalBackupCnt = 0;

        for (String cache : caches) {
            CacheMode mode = ignite.cachex(cache).configuration().getCacheMode();
            totalBackupCnt += (mode == REPLICATED ? ignite.context().discovery().aliveServerNodes().size()
                : ignite.cachex(cache).configuration().getBackups() + 1);
        }

        int cpus = Math.max(4, getInteger(AVAILABLE_PROCESSORS_RECONCILIATION, Runtime.getRuntime().availableProcessors()));

        return Math.max(1, (int)((loadFactor * cpus) / ((double)totalBackupCnt / (caches.isEmpty() ? 1 : caches.size()))));
    }

    /**
     * Builds text view for the object: regular view for simple objects and BinaryObject's toString otherwise.
     *
     * @param ctx Context.
     * @param obj Object.
     */
    public static String objectStringView(CacheObjectContext ctx, CacheObject obj) {
        if (obj instanceof KeyCacheObjectImpl || obj instanceof CacheObjectImpl)
            return Objects.toString(obj.value(ctx, false));

        return Objects.toString(obj);
    }
}
