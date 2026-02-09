package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

//缓存机制
public class WorldEngineCache {

    private static class CachedWorldEngine {
        final WorldEngine engine;
        final long cachedTime;
        final AtomicLong lastAccessTime;
        final WeakReference<WorldIdentifier> identifier;

        CachedWorldEngine(WorldEngine engine, WorldIdentifier identifier) {
            this.engine = engine;
            this.identifier = new WeakReference<>(identifier);
            this.cachedTime = System.currentTimeMillis();
            this.lastAccessTime = new AtomicLong(this.cachedTime);
        }

        void updateAccessTime() {
            lastAccessTime.set(System.currentTimeMillis());
        }

        long getIdleTime() {
            return System.currentTimeMillis() - lastAccessTime.get();
        }

        boolean isIdentifierValid() {
            return identifier.get() != null;
        }
    }

    private final Map<String, CachedWorldEngine> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService gcExecutor;

    private volatile boolean enabled;
    private volatile int maxCachedWorlds;
    private volatile long maxIdleTimeMs;

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public WorldEngineCache(boolean enabled, int maxCachedWorlds, long maxIdleTimeMinutes) {
        this.enabled = enabled;
        this.maxCachedWorlds = maxCachedWorlds;
        this.maxIdleTimeMs = maxIdleTimeMinutes * 60 * 1000;

        this.gcExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Voxy-WorldCache-GC");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // 30s gc
        this.gcExecutor.scheduleWithFixedDelay(this::performGC, 30, 30, TimeUnit.SECONDS);

        Logger.info("WorldEngineCache initialized: enabled=" + enabled +
                   ", maxCached=" + maxCachedWorlds +
                   ", maxIdle=" + maxIdleTimeMinutes + "min");
    }


    public WorldEngine getOrCreate(WorldIdentifier identifier, WorldEngineFactory factory) {
        if (!enabled) {
            cacheMisses.incrementAndGet();
            return factory.create();
        }

        String key = getCacheKey(identifier);
        CachedWorldEngine cached = cache.get(key);

        if (cached != null && cached.isIdentifierValid()) {
            cacheHits.incrementAndGet();
            cached.updateAccessTime();
            Logger.info("WorldEngine cache HIT for " + key + " (idle: " + cached.getIdleTime() / 1000 + "s)");

            cache.remove(key);
            return cached.engine;
        }


        cacheMisses.incrementAndGet();
        if (cached != null) {
            Logger.info("WorldEngine cache not FOUND " + key);
            cache.remove(key);
            tryFreeEngine(cached.engine);
        }

        return factory.create();
    }

    public boolean cache(WorldIdentifier identifier, WorldEngine engine) {
        if (!enabled || engine == null || identifier == null) {
            return false;
        }

        String key = getCacheKey(identifier);

        if (cache.size() >= maxCachedWorlds) {
            evictOldest();
        }

        CachedWorldEngine cached = new CachedWorldEngine(engine, identifier);
        cache.put(key, cached);

        Logger.info("WorldEngine CACHED for " + key + " (total: " + cache.size() + "/" + maxCachedWorlds + ")");
        return true;
    }


    public void remove(WorldIdentifier identifier) {
        if (identifier == null) return;

        String key = getCacheKey(identifier);
        CachedWorldEngine cached = cache.remove(key);

        if (cached != null) {
            Logger.info("WorldEngine removed from cache: " + key);
            tryFreeEngine(cached.engine);
        }
    }

    public void clear() {
        Logger.info("Clearing WorldEngine cache (" + cache.size() + " entries)");

        for (CachedWorldEngine cached : cache.values()) {
            tryFreeEngine(cached.engine);
        }

        cache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
        evictions.set(0);
    }

    private void performGC() {
        if (!enabled || cache.isEmpty()) {
            return;
        }

        try {
            long now = System.currentTimeMillis();
            int removedCount = 0;

            for (Map.Entry<String, CachedWorldEngine> entry : cache.entrySet()) {
                CachedWorldEngine cached = entry.getValue();

                boolean shouldRemove = false;
                String reason = "";

                if (!cached.isIdentifierValid()) {
                    shouldRemove = true;
                    reason = "identifier GC'd";
                } else if (cached.getIdleTime() > maxIdleTimeMs) {
                    shouldRemove = true;
                    reason = "idle timeout (" + cached.getIdleTime() / 1000 + "s)";
                }

                if (shouldRemove) {
                    cache.remove(entry.getKey());
                    tryFreeEngine(cached.engine);
                    evictions.incrementAndGet();
                    removedCount++;
                    Logger.info("GC evicted WorldEngine: " + entry.getKey() + " - " + reason);
                }
            }

            if (removedCount > 0) {
                Logger.info("WorldEngine cache GC completed: removed " + removedCount +
                           ", remaining " + cache.size());
            }

        } catch (Exception e) {
            Logger.error("Error during WorldEngine cache GC", e);
        }
    }

	//处理旧缓存
    private void evictOldest() {
        if (cache.isEmpty()) return;

        Map.Entry<String, CachedWorldEngine> oldest = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, CachedWorldEngine> entry : cache.entrySet()) {
            long cachedTime = entry.getValue().cachedTime;
            if (cachedTime < oldestTime) {
                oldestTime = cachedTime;
                oldest = entry;
            }
        }

        if (oldest != null) {
            cache.remove(oldest.getKey());
            tryFreeEngine(oldest.getValue().engine);
            evictions.incrementAndGet();
            Logger.info("WorldEngine cache FULL, evicted oldest: " + oldest.getKey());
        }
    }

    private void tryFreeEngine(WorldEngine engine) {
        if (engine == null) return;

        try {
            engine.free();
        } catch (Exception e) {
            Logger.error("Error freeing cached WorldEngine", e);
        }
    }

    private String getCacheKey(WorldIdentifier identifier) {
        return identifier.toString();
    }


    public CacheStats getStats() {
        long totalRequests = cacheHits.get() + cacheMisses.get();
        double hitRate = totalRequests > 0 ? (double) cacheHits.get() / totalRequests * 100 : 0;

        return new CacheStats(
            cache.size(),
            maxCachedWorlds,
            cacheHits.get(),
            cacheMisses.get(),
            evictions.get(),
            hitRate
        );
    }

    public void updateConfig(boolean enabled, int maxCachedWorlds, long maxIdleTimeMinutes) {
        this.enabled = enabled;
        this.maxCachedWorlds = maxCachedWorlds;
        this.maxIdleTimeMs = maxIdleTimeMinutes * 60 * 1000;

        if (!enabled) {
            clear();
        }

        Logger.info("WorldEngineCache config updated: enabled=" + enabled +
                   ", maxCached=" + maxCachedWorlds +
                   ", maxIdle=" + maxIdleTimeMinutes + "min");
    }

    public void shutdown() {
        Logger.info("Shutting down WorldEngineCache");

        gcExecutor.shutdown();
        try {
            if (!gcExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                gcExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            gcExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        clear();
    }

    public static class CacheStats {
        public final int currentSize;
        public final int maxSize;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final double hitRate;

        CacheStats(int currentSize, int maxSize, long hits, long misses, long evictions, double hitRate) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.hitRate = hitRate;
        }
    }

    @FunctionalInterface
    public interface WorldEngineFactory {
        WorldEngine create();
    }
}
