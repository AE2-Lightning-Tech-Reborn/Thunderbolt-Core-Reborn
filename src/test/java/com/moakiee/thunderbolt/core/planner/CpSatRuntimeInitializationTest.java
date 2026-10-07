package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CpSatRuntimeInitializationTest {
    @TempDir Path directory;

    @Test
    void swallowedExtractionFailureNeverPublishesAnAvailableRuntime() throws Exception {
        Path blocked = Files.createFile(directory.resolve("not-a-directory"));
        runFreshRuntime(blocked, false);
    }

    @Test
    void aFreshIsolatedRuntimeSolvesBeforeBecomingAvailable() throws Exception {
        runFreshRuntime(directory, true);
    }

    private void runFreshRuntime(Path temporary, boolean expected) throws Exception {
        Path output = directory.resolve("probe.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.io.tmpdir=" + temporary, "-Djna.tmpdir=" + directory,
                "-Djava.library.path=" + directory.resolve("native-search"),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(),
                Boolean.toString(expected))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "native bootstrap probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            // JNA has its own valid temp directory. OR-Tools receives the JVM temp directory
            // at startup, before java.nio caches it, so only its extraction is blocked.
            if (com.sun.jna.Platform.RESOURCE_PREFIX.isEmpty()) throw new AssertionError();
            boolean expected = Boolean.parseBoolean(args[0]);
            boolean loaded = CpSatRuntime.initializeFromTestClasspath();
            if (loaded != expected || CpSatRuntime.isAvailable() != expected)
                throw new AssertionError("expected available=" + expected + ", got " + loaded);
            if (expected && CpSatRuntime.loadFailure() != null)
                throw new AssertionError(CpSatRuntime.loadFailure());
            if (!expected && !(CpSatRuntime.loadFailure() instanceof UnsatisfiedLinkError))
                throw new AssertionError("expected a native binding failure", CpSatRuntime.loadFailure());
        }
    }
}
