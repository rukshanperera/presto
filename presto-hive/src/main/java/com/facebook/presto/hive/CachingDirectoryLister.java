/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.common.RuntimeStats;
import com.facebook.presto.hive.filesystem.ExtendedFileSystem;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.Duration;
import org.apache.hadoop.fs.Path;
import org.weakref.jmx.Managed;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.common.RuntimeMetricName.DIRECTORY_LISTING_CACHE_HIT;
import static com.facebook.presto.common.RuntimeMetricName.DIRECTORY_LISTING_CACHE_MISS;
import static com.facebook.presto.common.RuntimeMetricName.FILES_READ_COUNT;
import static com.facebook.presto.common.RuntimeUnit.NONE;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_PROCEDURE_ARGUMENT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

public class CachingDirectoryLister
        implements DirectoryLister
{
    private final Cache<Path, List<HiveFileInfo>> cache;
    private final CachedTableChecker cachedTableChecker;
    private final DirectoryLister delegate;

    @Inject
    public CachingDirectoryLister(@ForCachingDirectoryLister DirectoryLister delegate, HiveClientConfig hiveClientConfig)
    {
        this(
                delegate,
                hiveClientConfig.getFileStatusCacheExpireAfterWrite(),
                hiveClientConfig.getFileStatusCacheMaxSize(),
                hiveClientConfig.getFileStatusCacheTables());
    }

    public CachingDirectoryLister(DirectoryLister delegate, Duration expireAfterWrite, long maxSize, List<String> tables)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
        cache = CacheBuilder.newBuilder()
                .maximumWeight(maxSize)
                .weigher((Weigher<Path, List<HiveFileInfo>>) (key, value) -> value.size())
                .expireAfterWrite(expireAfterWrite.toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
        this.cachedTableChecker = new CachedTableChecker(requireNonNull(tables, "tables is null"));
    }

    @Override
    public Iterator<HiveFileInfo> list(
            ExtendedFileSystem fileSystem,
            Table table,
            Path path,
            Optional<Partition> partition,
            NamenodeStats namenodeStats,
            HiveDirectoryContext hiveDirectoryContext)
    {
        RuntimeStats runtimeStats = hiveDirectoryContext.getRuntimeStats();
        if (hiveDirectoryContext.isCacheable()) {
            // DO NOT USE Caching, when cache is disabled.
            // This is useful for debugging issues, when cache is explicitly disabled via session property.
            List<HiveFileInfo> files = cache.getIfPresent(path);
            if (files != null) {
                runtimeStats.addMetricValue(DIRECTORY_LISTING_CACHE_HIT, NONE, 1);
                runtimeStats.addMetricValue(FILES_READ_COUNT, NONE, files.size());
                return files.iterator();
            }
        }

        runtimeStats.addMetricValue(DIRECTORY_LISTING_CACHE_MISS, NONE, 1);
        Iterator<HiveFileInfo> iterator = delegate.list(fileSystem, table, path, partition, namenodeStats, hiveDirectoryContext);
        if (hiveDirectoryContext.isCacheable() && cachedTableChecker.isCachedTable(table.getSchemaTableName())) {
            return fileCountTrackingIterator(iterator, path, runtimeStats, true);
        }
        return fileCountTrackingIterator(iterator, path, runtimeStats, false);
    }

    private Iterator<HiveFileInfo> fileCountTrackingIterator(Iterator<HiveFileInfo> iterator, Path path, RuntimeStats runtimeStats, boolean enableCaching)
    {
        return new Iterator<HiveFileInfo>()
        {
            private final List<HiveFileInfo> files = new ArrayList<>();

            @Override
            public boolean hasNext()
            {
                boolean hasNext = iterator.hasNext();
                if (!hasNext) {
                    runtimeStats.addMetricValue(FILES_READ_COUNT, NONE, files.size());
                    if (enableCaching) {
                        cache.put(path, ImmutableList.copyOf(files));
                    }
                }
                return hasNext;
            }

            @Override
            public HiveFileInfo next()
            {
                HiveFileInfo next = iterator.next();
                files.add(next);
                return next;
            }
        };
    }

    public void invalidateDirectoryListCache(Optional<String> directoryPath)
    {
        if (directoryPath.isPresent()) {
            if (directoryPath.get().isEmpty()) {
                throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Directory path can not be a empty string");
            }
            Path path = new Path(directoryPath.get());
            List<HiveFileInfo> files = cache.getIfPresent(path);
            if (files == null) {
                throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Given directory path is not cached : " + directoryPath);
            }
            cache.invalidate(path);
        }
        else {
            flushCache();
        }
    }

    @Managed
    public void flushCache()
    {
        cache.invalidateAll();
    }

    @Managed
    public Double getHitRate()
    {
        return cache.stats().hitRate();
    }

    @Managed
    public Double getMissRate()
    {
        return cache.stats().missRate();
    }

    @Managed
    public long getHitCount()
    {
        return cache.stats().hitCount();
    }

    @Managed
    public long getMissCount()
    {
        return cache.stats().missCount();
    }

    @Managed
    public long getRequestCount()
    {
        return cache.stats().requestCount();
    }

    @Managed
    public long getEvictionCount()
    {
        return cache.stats().evictionCount();
    }

    @Managed
    public long getSize()
    {
        return cache.size();
    }

    private static class CachedTableChecker
    {
        private final Set<SchemaTableName> cachedTableNames;
        private final boolean cacheAllTables;

        public CachedTableChecker(List<String> cachedTables)
        {
            cacheAllTables = cachedTables.contains("*");
            if (cacheAllTables) {
                checkArgument(cachedTables.size() == 1, "Only '*' is expected when caching all tables");
                this.cachedTableNames = ImmutableSet.of();
            }
            else {
                this.cachedTableNames = cachedTables.stream()
                        .map(SchemaTableName::valueOf)
                        .collect(toImmutableSet());
            }
        }

        public boolean isCachedTable(SchemaTableName schemaTableName)
        {
            return cacheAllTables || cachedTableNames.contains(schemaTableName);
        }
    }
}
