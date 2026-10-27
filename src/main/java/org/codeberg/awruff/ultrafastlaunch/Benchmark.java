package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class Benchmark {
    static final int HIT = 0;
    static final int MISS = 1;
    static final int UNCACHED = 2;

    private static final String HEADER = "time,mode,classes,cache_hits,cache_misses,uncached,transform_ms,uptime_at_last_transform_ms\n";

    private final String mode;
    private final long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final AtomicInteger classes = new AtomicInteger();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();
    private final AtomicInteger uncached = new AtomicInteger();
    private final AtomicLong transformNanos = new AtomicLong();
    private volatile long lastTransform;

    Benchmark(String mode) {
        this.mode = mode;
        Runtime.getRuntime().addShutdownHook(new Thread(this::report, "UltraFastLaunch benchmark"));
    }

    void record(long nanos, int outcome) {
        classes.incrementAndGet();
        transformNanos.addAndGet(nanos);
        lastTransform = System.currentTimeMillis();
        if (outcome == HIT) hits.incrementAndGet();
        else if (outcome == MISS) misses.incrementAndGet();
        else uncached.incrementAndGet();
    }

    private void report() {
        long transformMs = transformNanos.get() / 1_000_000;
        long uptimeMs = lastTransform == 0 ? 0 : lastTransform - jvmStart;
        String mode = this.mode + "+" + AotCache.label();
        Log.info(LogCategory.MIXIN, "[UltraFastLaunch] Benchmark (%s): %d classes, %d hits, %d misses, %d uncached, %d ms transforming, last transform at %d ms uptime",
                mode, classes.get(), hits.get(), misses.get(), uncached.get(), transformMs, uptimeMs);

        Path csv = TransformCache.directory().resolve("benchmark.csv");
        String row = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()) + "," + mode + ","
                + classes.get() + "," + hits.get() + "," + misses.get() + "," + uncached.get() + ","
                + transformMs + "," + uptimeMs + "\n";
        try {
            Files.createDirectories(csv.getParent());
            try {
                Files.write(csv, (HEADER + row).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            Log.warn(LogCategory.MIXIN, "[UltraFastLaunch] Could not write benchmark.csv", e);
        }
    }
}
