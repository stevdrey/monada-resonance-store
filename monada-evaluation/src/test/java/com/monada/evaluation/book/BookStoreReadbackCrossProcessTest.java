package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeds a Store in one JVM and verifies it in another: the child process has no state but the files on disk, so a
 * passing readback cannot depend on any in-memory cache of the seeding process.
 */
class BookStoreReadbackCrossProcessTest {
    private static final String FIXTURE = "/book-chunking-fixture-v1/";

    @TempDir
    Path dir;

    private Path copyFixture(String name) throws IOException {
        try (InputStream in = BookStoreReadbackCrossProcessTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(in, "missing fixture " + name);
            Path target = dir.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    @Test
    void storeSeededInOneJvmVerifiesIdenticallyInAnother() throws Exception {
        String classpath = System.getProperty("forge.runtime.classpath");
        Assumptions.assumeTrue(classpath != null && !classpath.isBlank(),
                "forge.runtime.classpath is set by the Gradle test task");
        BookChunker.Result result = BookChunker.chunk(VerifiedBookSource.load(copyFixture("manifest.properties"),
                copyFixture("body.txt")), BookChunkingPolicy.V1);
        Path work = ExperimentDirectoryGuard.prepareForSeed(dir.resolve("work"), List.of());
        BookStoreReadback.Seed seed = BookStoreReadback.seed(result, work);
        assertTrue(seed.passed());
        String digest = BookStoreInventory.treeDigest(work.resolve("store"));

        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, BookStoreReadbackMain.class.getName(), "verify", "--work", work.toString())
                .redirectOutput(dir.resolve("verify.out").toFile())
                .redirectError(dir.resolve("verify.err").toFile())
                .start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "child JVM timed out");
        String err = Files.readString(dir.resolve("verify.err"), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), err);

        String child = Files.readString(dir.resolve("verify.out"), StandardCharsets.UTF_8);
        assertEquals(BookStoreReadbackReport.render(seed.verification()), child,
                "the child JVM must report the identical inventory, readback and audit");
        assertTrue(child.contains("inventory fingerprint:         " + seed.after().fingerprint()), child);
        assertEquals(digest, BookStoreInventory.treeDigest(work.resolve("store")), "the child JVM wrote nothing");
    }
}
