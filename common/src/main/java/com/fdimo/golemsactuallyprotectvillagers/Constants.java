package com.fdimo.golemsactuallyprotectvillagers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Constants {

    public static final String MOD_ID = "golems_actually_protect_villagers";
    public static final String MOD_NAME = "Golems Actually Protect Villagers";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private static final AtomicInteger threadId = new AtomicInteger(1);
    public static volatile ThreadPoolExecutor ASYNC_POOL = createThreadPool(1000);

    public static ThreadPoolExecutor createThreadPool(int capacity) {
        return new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(Math.max(10, capacity)),
            r -> {
                Thread t = new Thread(r, "GolemsActuallyProtectVillagers-Async-" + threadId.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public static synchronized void initThreadPool() {
        if (ASYNC_POOL != null && !ASYNC_POOL.isShutdown()) {
            ASYNC_POOL.shutdown();
        }
        int capacity = Config.DATA != null ? Config.DATA.asyncQueueCapacity : 1000;
        ASYNC_POOL = createThreadPool(capacity);
    }
}
