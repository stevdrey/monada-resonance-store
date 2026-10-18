package com.monada.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract checks for the {@code book-corpus-v1} input (issue #163). Runs the real offline verifier
 * ({@code docs/experiments/book-corpus-v1/VerifyBookCorpus.java}) through the JDK source launcher
 * against a tiny original fixture. No network access; the book itself is only checked when the
 * caller opts in with {@code MONADA_BOOK_CORPUS_V1_FILE}.
 */
class BookCorpusV1ContractTest {

    private static final String REAL_FILE_ENV = "MONADA_BOOK_CORPUS_V1_FILE";
    private static final String FIXTURE = "/book-corpus-fixture-v1/";
    private static final String FIXTURE_NORMALIZED = """
            The lighthouse keeper counted the gulls — eleven, then twelve.

            Café lights blinked across the harbour at dusk.
            """;
    private static final long MAX_TRACKED_FILE_BYTES = 64 * 1024;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private static Path corpusDir() {
        // Gradle runs tests with the module directory as working directory.
        Path dir = Path.of(System.getProperty("user.dir")).resolve("../docs/experiments/book-corpus-v1").normalize();
        assertTrue(Files.isDirectory(dir), "book-corpus-v1 contract directory not found: " + dir);
        return dir;
    }

    private static Result verify(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(corpusDir().resolve("VerifyBookCorpus.java").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(corpusDir().resolve("../../..").normalize().toFile())
                .start();
        process.getOutputStream().close();
        // Outputs are small; read both streams fully before waiting.
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "verifier did not finish");
        return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8),
                new String(err, StandardCharsets.UTF_8));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = BookCorpusV1ContractTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(in, "fixture resource not found: " + FIXTURE + name);
            return in.readAllBytes();
        }
    }

    private static Path fixtureManifest(Path dir) throws IOException {
        Path manifest = dir.resolve("manifest.properties");
        Files.write(manifest, resource("manifest.properties"));
        return manifest;
    }

    private static Path input(Path dir, byte[] bytes) throws IOException {
        Path input = dir.resolve("input.txt");
        Files.write(input, bytes);
        return input;
    }

    private static String sample() throws IOException {
        return new String(resource("sample.txt"), StandardCharsets.UTF_8);
    }

    @Test
    void fixtureVerifiesAndWritesNormalizedBody(@TempDir Path dir) throws Exception {
        Path manifest = fixtureManifest(dir);
        Path out = dir.resolve("normalized.txt");
        var result = verify("--manifest", manifest.toString(), "--write-normalized", out.toString(),
                input(dir, resource("sample.txt")).toString());
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().startsWith("OK book-corpus-fixture v1"), result.stdout());
        assertArrayEquals(FIXTURE_NORMALIZED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out));
    }

    @Test
    void missingInputFailsWithAcquisitionPointer(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("pg11.txt");
        var result = verify("--manifest", fixtureManifest(dir).toString(), missing.toString());
        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("input not found: " + missing.toAbsolutePath()), result.stderr());
        assertTrue(result.stderr().contains("Offline acquisition"), result.stderr());
    }

    @Test
    void changedBodyByteIsRejectedAsDifferentEdition(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("normalized.txt");
        var result = verify("--manifest", fixtureManifest(dir).toString(), "--write-normalized", out.toString(),
                input(dir, sample().replace("twelve", "twelvE").getBytes(StandardCharsets.UTF_8)).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("raw bytes do not match the pinned edition"), result.stderr());
        assertTrue(result.stderr().contains("normalized body differs too"), result.stderr());
        assertTrue(result.stderr().contains("Re-pinning"), result.stderr());
        assertFalse(Files.exists(out), "nothing may be written when verification fails");
    }

    @Test
    void headerOnlyDriftIsReportedButStillRejected(@TempDir Path dir) throws Exception {
        var drifted = sample().replace("Fixture Header", "Fixture Header (regenerated upstream)");
        var result = verify("--manifest", fixtureManifest(dir).toString(),
                input(dir, drifted.getBytes(StandardCharsets.UTF_8)).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("normalized body still matches"), result.stderr());
        assertTrue(result.stderr().contains("Still rejected"), result.stderr());
    }

    @Test
    void byteOrderMarkIsRejected(@TempDir Path dir) throws Exception {
        var result = verify("--manifest", fixtureManifest(dir).toString(),
                input(dir, ("﻿" + sample()).getBytes(StandardCharsets.UTF_8)).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("byte order mark"), result.stderr());
    }

    @Test
    void invalidUtf8IsRejectedWithOffset(@TempDir Path dir) throws Exception {
        byte[] bytes = resource("sample.txt");
        bytes[3] = (byte) 0xFF;
        var result = verify("--manifest", fixtureManifest(dir).toString(), input(dir, bytes).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("invalid UTF-8 at byte offset 3"), result.stderr());
    }

    @Test
    void missingEndMarkerIsRejected(@TempDir Path dir) throws Exception {
        var truncated = sample().replace("*** FINISH FIXTURE TEXT ***", "");
        var result = verify("--manifest", fixtureManifest(dir).toString(),
                input(dir, truncated.getBytes(StandardCharsets.UTF_8)).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("end marker not found"), result.stderr());
    }

    @Test
    void loneCarriageReturnIsRejected(@TempDir Path dir) throws Exception {
        var mixed = sample().replace("eleven,", "eleven,\r");
        var result = verify("--manifest", fixtureManifest(dir).toString(),
                input(dir, mixed.getBytes(StandardCharsets.UTF_8)).toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("lone carriage return"), result.stderr());
    }

    @Test
    void pinnedManifestDeclaresTheCompleteContract() throws IOException {
        var manifest = new Properties();
        try (InputStream in = Files.newInputStream(corpusDir().resolve("manifest.properties"))) {
            manifest.load(in);
        }
        for (String key : List.of("corpus", "corpusVersion", "source.uri", "source.ebookNumber", "source.revision",
                "source.retrievalMethod", "raw.sizeBytes", "raw.sha256", "raw.encoding", "raw.bom", "raw.eol",
                "normalize.policy", "normalize.startMarkerPrefix", "normalize.endMarkerPrefix",
                "normalized.sizeBytes", "normalized.sha256", "normalized.lineCount", "rights.textUS",
                "rights.textOutsideUS", "rights.platformLicense", "rights.trademark", "redistribution.status")) {
            assertFalse(manifest.getProperty(key, "").isBlank(), "missing manifest key: " + key);
        }
        assertEquals("book-corpus-v1", manifest.getProperty("corpus"));
        assertEquals("NOT_REDISTRIBUTED_IN_GIT", manifest.getProperty("redistribution.status"));
        assertEquals("MANUAL_OFFLINE_USER_DOWNLOAD", manifest.getProperty("source.retrievalMethod"));
        assertTrue(manifest.getProperty("raw.sha256").matches("[0-9a-f]{64}"));
        assertTrue(manifest.getProperty("normalized.sha256").matches("[0-9a-f]{64}"));
        assertTrue(Long.parseLong(manifest.getProperty("raw.sizeBytes"))
                > Long.parseLong(manifest.getProperty("normalized.sizeBytes")));
    }

    @Test
    void contractDirectoryDoesNotRedistributeBookBytes() throws IOException {
        try (Stream<Path> files = Files.walk(corpusDir())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertTrue(Files.size(file) <= MAX_TRACKED_FILE_BYTES, "file too large to be contract-only: " + file);
                String text = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(text.contains("Down the Rabbit-Hole"), "book text found in tracked file: " + file);
            }
        }
    }

    @Test
    void locallySuppliedBookMatchesThePinnedEdition() throws Exception {
        String supplied = System.getenv(REAL_FILE_ENV);
        assumeTrue(supplied != null && !supplied.isBlank(),
                REAL_FILE_ENV + " not set; the book is supplied offline and never fetched by tests");
        var result = verify(supplied);
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().startsWith("OK book-corpus-v1 v1"), result.stdout());
    }
}
