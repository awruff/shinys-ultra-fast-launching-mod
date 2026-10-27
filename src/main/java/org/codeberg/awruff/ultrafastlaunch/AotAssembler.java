package org.codeberg.awruff.ultrafastlaunch;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the JVM that creates an AOT cache in a process that outlives the game. The cache is written to a temporary file
 * and moved into place once complete, so a launch never maps a half-written cache. The file lock held while this runs
 * tells the game an assembly is in progress.
 */
public final class AotAssembler {
    private AotAssembler() {
    }

    public static void main(String[] args) throws Exception {
        Path lock = Paths.get(args[0]);
        Path tmp = Paths.get(args[1]);
        Path cache = Paths.get(args[2]);
        List<String> command = Arrays.asList(args).subList(3, args.length);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.tryLock()) {
            if (held == null) return;
            Files.deleteIfExists(tmp);
            int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
            if (exit == 0 && Files.isRegularFile(tmp)) {
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(tmp);
                System.out.println("AOT cache creation failed with exit code " + exit);
            }
        }
    }
}
