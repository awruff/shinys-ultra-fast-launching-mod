package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Manages a JDK AOT cache (JEP 483/514/515, Java 25+) for the next launch.
 *
 * <p>JVM flags can't be changed from inside a running JVM, so the user adds one argument once:
 * {@code -XX:VMOptionsFile=<config>/ultrafastlaunch/jvm.options}. Every launch rewrites that file for the next one.
 * Building a cache takes three launches. The first runs with {@code -XX:AOTMode=record} and writes an AOT configuration
 * on exit. The second starts a background JVM ({@link AotAssembler}) that turns the configuration into the cache, and
 * later launches start with {@code -XX:AOTCache}. The JDK's one-step {@code -XX:AOTCacheOutput} builds the cache during
 * JVM exit, which takes longer than Minecraft's 15 second shutdown watchdog allows.
 *
 * <p>The JVM only accepts a cache built with the same class path in the same order. Some launchers reorder the class
 * path on every launch, so recording waits until two launches in a row had the same class path.
 *
 * <p>The file always starts with {@code -XX:+IgnoreUnrecognizedVMOptions} so that a JDK without the AOT flags still
 * starts. The JVM refuses to start if the options file is missing, so this class only ever rewrites it in place.
 */
final class AotCache {
    static final String DISABLE_PROPERTY = "ultrafastlaunch.aot";
    private static final String MARKER_PROPERTY = "ultrafastlaunch.vmoptions";
    private static final int MIN_JAVA = 25;
    private static final int MAX_FAILURES = 2;
    private static final String PREFIX = "aot-";
    private static final Pattern NOT_FOR_ASSEMBLY = Pattern.compile(
            "-XX:[+-]?(AOT|SharedArchiveFile|VMOptionsFile).*|-Xshare:.*|-Djava\\.class\\.path=.*|-D" + Pattern.quote(MARKER_PROPERTY) + "=.*|-(javaagent|agentlib|agentpath):.*");

    private AotCache() {
    }

    static Path optionsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("ultrafastlaunch").resolve("jvm.options");
    }

    /**
     * Writes the options for the next launch and returns a short label describing this launch, for the benchmark.
     */
    static String update() {
        Path options = optionsFile().toAbsolutePath().normalize();
        boolean argumentSet = System.getProperty(MARKER_PROPERTY) != null;
        String mode = vmOption("AOTMode");
        String using = vmOption("AOTCache");
        String recording = vmOption("AOTConfiguration");

        if ("false".equals(System.getProperty(DISABLE_PROPERTY))) {
            writeOptions(options, Collections.<String>emptyList());
            return "aot-off";
        }
        if (javaVersion() < MIN_JAVA || mode == null || using == null || recording == null) {
            writeOptions(options, Collections.<String>emptyList());
            if (argumentSet) {
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] The AOT cache needs Java %d or newer, this is Java %s", MIN_JAVA, System.getProperty("java.version"));
            }
            return "aot-unsupported";
        }

        String classPath = System.getProperty("java.class.path", "");
        String key = key(classPath);
        Path dir = TransformCache.directory().resolve("aot").toAbsolutePath().normalize();
        Path cache = dir.resolve(PREFIX + key + ".aot");
        Path config = dir.resolve(PREFIX + key + ".aotconf");
        Path lock = dir.resolve(PREFIX + key + ".lock");
        Path log = dir.resolve(PREFIX + key + ".log");
        Path stateFile = dir.resolve("state.properties");
        boolean recordingNow = "record".equals(mode);
        boolean recordingThis = recordingNow && samePath(recording, config);

        String label;
        if (recordingNow) label = "aot-record";
        else if (!isFile(using)) label = "aot-none";
        else if (!System.getProperty("java.vm.info", "").contains("sharing")) label = "aot-rejected";
        else label = "aot-use";

        Properties state = loadState(stateFile);
        int failures = cache.toString().equals(state.getProperty("target")) ? parseInt(state.getProperty("failures")) : 0;
        if (config.toString().equals(state.getProperty("pending")) && !recordingThis && !Files.isRegularFile(config)) {
            failures++; // the recording launch crashed or was killed before the JVM wrote the configuration
        }
        String classPathHash = hash(Collections.singletonList(classPath));
        List<String> sorted = new ArrayList<>(Arrays.asList(classPath.split(Pattern.quote(File.pathSeparator))));
        Collections.sort(sorted);
        String sortedHash = hash(sorted);
        String previousClassPath = state.getProperty("classpath");

        if (label.equals("aot-rejected")) {
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] The JVM didn't accept the AOT cache %s, run with -Xlog:aot to see why", using);
            if (samePath(using, cache)) {
                deleteQuietly(cache);
                failures++;
            }
        }

        List<String> next = new ArrayList<>();
        String pending = null;
        if (Files.isRegularFile(cache)) {
            next.add("-XX:AOTCache=" + cache);
            failures = 0;
            deleteQuietly(config);
        } else if (Files.isRegularFile(config)) {
            if (assembling(lock)) {
                next.add("-XX:AOTCache=" + cache);
            } else if (failures >= MAX_FAILURES) {
                Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] Building the AOT cache failed %d times, see %s. Not retrying until Java, the class path or mods change, or %s is deleted", failures, log, stateFile);
            } else if (startAssembler(config, cache, lock, log, classPath)) {
                failures++;
                next.add("-XX:AOTCache=" + cache);
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] Building the AOT cache in the background. Launches after it finishes will use it");
            }
        } else if (recordingThis) {
            pending = config.toString();
            Log.info(LogCategory.GENERAL, "[UltraFastLaunch] Recording for the AOT cache during this launch. Quit the game normally to save it; the next launch builds the cache in the background");
        } else if (!classPathHash.equals(previousClassPath)) {
            if (previousClassPath != null && sortedHash.equals(state.getProperty("classpathSorted"))) {
                Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] The launcher put the class path in a different order than last launch. The JVM only uses an AOT cache with the exact class path it was built with, so it won't be built until two launches in a row have the same order");
            } else if (previousClassPath != null) {
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] The class path changed since the last launch. The AOT cache will be recorded once it stays the same for a launch");
            }
        } else if (failures >= MAX_FAILURES) {
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] Recording for the AOT cache didn't finish after %d attempts (the game has to be quit normally for it to be saved). Not retrying until Java, the class path or mods change, or %s is deleted", failures, stateFile);
        } else {
            next.add("-XX:AOTMode=record");
            next.add("-XX:AOTConfiguration=" + config);
            if (argumentSet) {
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] The next launch will record for the AOT cache");
            }
        }

        if (dir.toString().indexOf('"') >= 0) next.clear(); // can't be quoted in the options file
        writeOptions(options, next);

        state.setProperty("target", cache.toString());
        state.setProperty("failures", Integer.toString(failures));
        state.setProperty("classpath", classPathHash);
        state.setProperty("classpathSorted", sortedHash);
        if (pending != null) state.setProperty("pending", pending);
        else state.remove("pending");
        saveState(stateFile, state);

        deleteStale(dir, key, using, recording);

        if (!argumentSet) {
            Log.info(LogCategory.GENERAL, "[UltraFastLaunch] To speed up launches with the Java AOT cache (Java %d+), add this JVM argument: -XX:VMOptionsFile=%s", MIN_JAVA, quoteIfNeeded(options.toString()));
        }
        return label;
    }

    /**
     * Identifies everything an AOT cache depends on. A change makes the next launch build a new cache. The JDK build and
     * class path must match exactly or the JVM ignores the cache, mod changes invalidate the cached Mixin output, and
     * GC/object layout flags change what the JVM can map from the cache.
     */
    private static String key(String classPath) {
        List<String> lines = new ArrayList<>();
        lines.add("vm=" + System.getProperty("java.vm.vendor") + "|" + System.getProperty("java.vm.version") + "|" + System.getProperty("java.home"));
        lines.add("cp=" + classPath);
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.matches("-XX:[+-]Use\\w*GC") || arg.matches("-XX:[+-]Use(CompressedOops|CompressedClassPointers|CompactObjectHeaders)")) {
                lines.add("arg=" + arg);
            }
        }
        try {
            lines.addAll(TransformCache.computeFingerprint());
        } catch (IOException e) {
            lines.add("fingerprint=unavailable");
        }
        return hash(lines);
    }

    private static String hash(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : Arrays.copyOf(digest.digest(), 8)) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startAssembler(Path config, Path cache, Path lock, Path log, String classPath) {
        try {
            Path jar = Paths.get(AotCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String java = javaExecutable();
            Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");

            List<String> command = new ArrayList<>(Arrays.asList(java, "-Xmx64m", "-XX:TieredStopAtLevel=1", "-cp", jar.toString(),
                    AotAssembler.class.getName(), lock.toString(), tmp.toString(), cache.toString(), java));
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!NOT_FOR_ASSEMBLY.matcher(arg).matches()) command.add(arg);
            }
            command.add("-XX:AOTMode=create");
            command.add("-XX:AOTConfiguration=" + config);
            command.add("-XX:AOTCache=" + tmp);
            command.add("-cp");
            command.add(classPath);

            Files.createDirectories(cache.getParent());
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return true;
        } catch (Exception e) {
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] Could not start building the AOT cache", e);
            return false;
        }
    }

    private static String javaExecutable() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            Path javaw = bin.resolve("javaw.exe");
            return (Files.isRegularFile(javaw) ? javaw : bin.resolve("java.exe")).toString();
        }
        return bin.resolve("java").toString();
    }

    private static boolean assembling(Path lock) {
        if (!Files.exists(lock)) return false;
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
             FileLock held = channel.tryLock()) {
            return held == null;
        } catch (IOException | OverlappingFileLockException e) {
            return false;
        }
    }

    // The JVM splits options file lines on whitespace unless quoted and has no comment syntax, so every line is a single
    // option and paths are quoted. Forward slashes avoid any backslash handling.
    private static void writeOptions(Path file, List<String> aotOptions) {
        StringBuilder text = new StringBuilder();
        text.append("-XX:+IgnoreUnrecognizedVMOptions\n");
        text.append("-D").append(MARKER_PROPERTY).append("=1\n");
        for (String option : aotOptions) text.append('"').append(option.replace('\\', '/')).append("\"\n");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) return;
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] Could not write %s", file, e);
        }
    }

    private static void deleteStale(Path dir, String key, String using, String recording) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*")) {
            for (Path p : files) {
                if (p.getFileName().toString().startsWith(PREFIX + key + ".") || samePath(using, p) || samePath(recording, p)) continue;
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // still mapped by this JVM on Windows, or otherwise in use; retried next launch
        }
    }

    /**
     * Reads a HotSpot flag. Returns null if the flag doesn't exist in this JVM and "" if it's unset.
     */
    private static String vmOption(String name) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            CompositeData option = (CompositeData) server.invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption", new Object[]{name}, new String[]{String.class.getName()});
            Object value = option.get("value");
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int javaVersion() {
        String spec = System.getProperty("java.specification.version", "");
        if (spec.startsWith("1.")) spec = spec.substring(2);
        return parseInt(spec);
    }

    private static boolean isFile(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            return Files.isRegularFile(Paths.get(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean samePath(String a, Path b) {
        if (a == null || a.isEmpty()) return false;
        try {
            return Paths.get(a).toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String quoteIfNeeded(String path) {
        return path.indexOf(' ') >= 0 ? "\"" + path + "\"" : path;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Properties loadState(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                p.clear();
            }
        }
        return p;
    }

    private static void saveState(Path file, Properties state) {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                state.store(out, null);
            }
        } catch (IOException e) {
            Log.debug(LogCategory.GENERAL, "[UltraFastLaunch] Could not save %s", file, e);
        }
    }
}
