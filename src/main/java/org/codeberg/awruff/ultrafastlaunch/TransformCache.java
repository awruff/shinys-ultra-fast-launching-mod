package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

final class TransformCache {
    private static final int MAGIC = 0x55464C43;
    private static final int VERSION = 2;
    private static final long FLUSH_DELAY_MS = 10_000;
    private static final long FLUSH_POLL_MS = 5_000;
    private static final int MAX_CHANGES_LOGGED = 10;

    private static final class Entry {
        final int inputLength;
        final long inputCrc;
        final byte[] data;
        final int offset;
        final int length;

        Entry(int inputLength, long inputCrc, byte[] data, int offset, int length) {
            this.inputLength = inputLength;
            this.inputCrc = inputCrc;
            this.data = data;
            this.offset = offset;
            this.length = length;
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile byte[] fingerprint;
    private volatile List<String> fingerprintLines;
    private volatile boolean usable;
    private volatile boolean dirty;
    private volatile long lastStore;

    private TransformCache(Path file) {
        this.file = file;
    }

    static Path directory() {
        return FabricLoader.getInstance().getGameDir().resolve(".fabric").resolve("ultrafastlaunch");
    }

    static TransformCache open() {
        TransformCache cache = new TransformCache(directory().resolve("transform-cache.bin"));
        cache.openNow();
        if (cache.usable) cache.startFlusher();
        return cache;
    }

    private void openNow() {
        try {
            fingerprintLines = computeFingerprint();
            fingerprint = hash(fingerprintLines);
            load();
            usable = true;
        } catch (Throwable t) {
            entries.clear();
            Log.warn(LogCategory.MIXIN, "[UltraFastLaunch] Could not open transform cache, caching disabled for this launch", t);
        }
    }

    static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    byte[] lookup(String name, byte[] input, long inputCrc) {
        if (!usable) return null;
        Entry entry = entries.get(name);
        if (entry == null || entry.inputLength != input.length || entry.inputCrc != inputCrc) return null;
        return entry.data == null ? input : Arrays.copyOfRange(entry.data, entry.offset, entry.offset + entry.length);
    }

    void store(String name, byte[] input, long inputCrc, byte[] output) {
        if (!usable) return;
        boolean unchanged = input == output || (input.length == output.length && Arrays.equals(input, output));
        entries.put(name, unchanged
                ? new Entry(input.length, inputCrc, null, 0, 0)
                : new Entry(input.length, inputCrc, output, 0, output.length));
        lastStore = System.currentTimeMillis();
        dirty = true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            Log.info(LogCategory.MIXIN, "[UltraFastLaunch] No transform cache yet, it will be built during this launch");
            return;
        }
        try {
            byte[] buf = Files.readAllBytes(file);
            ByteArrayInputStream bytes = new ByteArrayInputStream(buf);
            DataInputStream in = new DataInputStream(bytes);
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                Log.info(LogCategory.MIXIN, "[UltraFastLaunch] Transform cache format changed, rebuilding");
                return;
            }
            byte[] stored = new byte[in.readUnsignedShort()];
            in.readFully(stored);
            if (!Arrays.equals(stored, fingerprint)) {
                Log.info(LogCategory.MIXIN, "[UltraFastLaunch] Mods or environment changed, rebuilding transform cache");
                logChanges();
                return;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                int inputLength = in.readInt();
                long inputCrc = in.readLong();
                Entry entry;
                if (in.readBoolean()) {
                    int length = in.readInt();
                    int offset = buf.length - bytes.available();
                    if (length < 0 || in.skipBytes(length) != length) throw new EOFException();
                    entry = new Entry(inputLength, inputCrc, buf, offset, length);
                } else {
                    entry = new Entry(inputLength, inputCrc, null, 0, 0);
                }
                entries.put(name, entry);
            }
            Log.info(LogCategory.MIXIN, "[UltraFastLaunch] Loaded %d cached classes", count);
        } catch (IOException | RuntimeException e) {
            entries.clear();
            Log.warn(LogCategory.MIXIN, "[UltraFastLaunch] Transform cache unreadable, rebuilding", e);
        }
    }

    private synchronized void save() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            List<Map.Entry<String, Entry>> snapshot = new ArrayList<>(entries.entrySet());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeShort(fingerprint.length);
                out.write(fingerprint);
                out.writeInt(snapshot.size());
                for (Map.Entry<String, Entry> e : snapshot) {
                    Entry entry = e.getValue();
                    out.writeUTF(e.getKey());
                    out.writeInt(entry.inputLength);
                    out.writeLong(entry.inputCrc);
                    out.writeBoolean(entry.data != null);
                    if (entry.data != null) {
                        out.writeInt(entry.length);
                        out.write(entry.data, entry.offset, entry.length);
                    }
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(fingerprintFile(), fingerprintLines, StandardCharsets.UTF_8);
            Log.info(LogCategory.MIXIN, "[UltraFastLaunch] Saved %d cached classes", snapshot.size());
        } catch (IOException e) {
            lastStore = System.currentTimeMillis();
            dirty = true;
            Log.warn(LogCategory.MIXIN, "[UltraFastLaunch] Failed to save transform cache", e);
        }
    }

    private void startFlusher() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::save, "UltraFastLaunch cache save"));
        Thread flusher = new Thread(() -> {
            try {
                while (true) {
                    long wait = dirty ? lastStore + FLUSH_DELAY_MS - System.currentTimeMillis() : FLUSH_POLL_MS;
                    if (wait > 0) {
                        Thread.sleep(wait);
                    } else {
                        save();
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "UltraFastLaunch cache flusher");
        flusher.setDaemon(true);
        flusher.setPriority(Thread.MIN_PRIORITY);
        flusher.start();
    }

    private static List<String> sharedFingerprint;

    static synchronized List<String> computeFingerprint() throws IOException {
        if (sharedFingerprint == null) sharedFingerprint = Collections.unmodifiableList(readFingerprint());
        return sharedFingerprint;
    }

    private static List<String> readFingerprint() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        List<String> lines = new ArrayList<>();

        lines.add("java=" + System.getProperty("java.version") + "|" + System.getProperty("java.vendor"));
        loader.getModContainer("fabricloader").ifPresent(m ->
                lines.add("loader=" + m.getMetadata().getVersion().getFriendlyString()));

        TreeMap<String, String> props = new TreeMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("mixin.") || key.startsWith("fabric.")) props.put(key, System.getProperty(key));
        }
        props.forEach((k, v) -> lines.add("prop:" + k + "=" + v));

        Set<Path> paths = new HashSet<>();
        for (ModContainer mod : loader.getAllMods()) {
            if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) continue;
            for (Path p : mod.getOrigin().getPaths()) paths.add(p.toAbsolutePath().normalize());
        }
        for (Path p : FabricLauncherBase.getLauncher().getClassPath()) paths.add(p.toAbsolutePath().normalize());
        List<String> pathLines = new ArrayList<>();
        for (Path p : paths) pathLines.add(describe(p));

        Path configDir = loader.getConfigDir();
        if (Files.isDirectory(configDir)) {
            try (Stream<Path> walk = Files.walk(configDir, 2)) {
                walk.filter(TransformCache::isMixinConfig).forEach(p -> pathLines.add(describeConfig(p)));
            }
        }
        Collections.sort(pathLines);
        lines.addAll(pathLines);

        return lines;
    }

    private static boolean isMixinConfig(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return (name.contains("mixin") || name.endsWith(".properties")) && Files.isRegularFile(p);
    }

    private static byte[] hash(List<String> lines) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return digest.digest();
    }

    private void logChanges() {
        Path txt = fingerprintFile();
        if (!Files.isRegularFile(txt)) return;
        try {
            Set<String> old = new HashSet<>(Files.readAllLines(txt, StandardCharsets.UTF_8));
            Set<String> now = new HashSet<>(fingerprintLines);
            int removed = 0;
            int added = 0;
            for (String line : old) {
                if (!now.contains(line) && removed++ < MAX_CHANGES_LOGGED) Log.info(LogCategory.MIXIN, "[UltraFastLaunch]   was: %s", line);
            }
            for (String line : fingerprintLines) {
                if (!old.contains(line) && added++ < MAX_CHANGES_LOGGED) Log.info(LogCategory.MIXIN, "[UltraFastLaunch]   now: %s", line);
            }
        } catch (IOException ignored) {
        }
    }

    private Path fingerprintFile() {
        return file.resolveSibling("fingerprint.txt");
    }

    private static String describe(Path abs) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
            return abs + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return abs + "|missing";
        }
    }

    // Lithium, Sodium and other mods turn mixins on and off from config files. Properties.store adds a date comment on
    // every save and writes keys in hash table order, so the hash skips comment lines and sorts .properties lines.
    // Resaving the same settings keeps the cache valid.
    private static String describeConfig(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(abs, StandardCharsets.ISO_8859_1)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) lines.add(trimmed);
            }
            if (abs.getFileName().toString().endsWith(".properties")) Collections.sort(lines);
            CRC32 crc = new CRC32();
            for (String line : lines) {
                crc.update(line.getBytes(StandardCharsets.ISO_8859_1));
                crc.update('\n');
            }
            return "config:" + abs + "|" + Long.toHexString(crc.getValue());
        } catch (IOException e) {
            return "config:" + abs + "|unreadable";
        }
    }
}
