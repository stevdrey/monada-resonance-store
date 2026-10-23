package com.monada.evaluation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract checks for the {@code book-corpus-v1} input (issue #163). Runs the real offline verifier
 * ({@code docs/experiments/book-corpus-v1/VerifyBookCorpus.java}), compiled once per class, against a
 * tiny original fixture; one test also runs the documented source-launcher command. No network access; the book itself is only checked when the
 * caller opts in with {@code MONADA_BOOK_CORPUS_V1_FILE}.
 */
class BookCorpusV1ContractTest {

    private static final String REAL_FILE_ENV = "MONADA_BOOK_CORPUS_V1_FILE";
    private static final String FIXTURE = "/book-corpus-fixture-v1/";
    private static final String FIXTURE_NORMALIZED = """
            The lighthouse keeper counted the gulls — eleven, then twelve.

            Café lights blinked across the harbour at dusk.
            """;
    private static final char BOM = 0xFEFF;
    private static final long MAX_TRACKED_FILE_BYTES = 64 * 1024;
    // Both directories are immutable for v1; any extra tracked file (e.g. a book excerpt) must fail here.
    private static final String CONTRACT_DIR = "docs/experiments/book-corpus-v1";
    private static final Set<String> CONTRACT_FILES = Set.of(
            "README.md", "RIGHTS.md", "manifest.properties", "VerifyBookCorpus.java", "CHUNKING.md",
            "STORE-READBACK.md", "QUERIES.md", "RECALL.md", "book-recall-v1-report.txt", "HUB-DIAGNOSTICS.md",
            "book-hub-diagnostics-v1-report.txt", "UNJUDGED-REVIEW.md", "book-unjudged-review-v2-report.txt",
            "LATENCY.md", "book-latency-v1-report.txt", "book-latency-v1-summary.md",
            ".gitattributes");
    private static final String FIXTURE_DIR = "monada-evaluation/src/test/resources/book-corpus-fixture-v1";
    private static final Set<String> FIXTURE_FILES = Set.of(".gitattributes", "manifest.properties", "sample.txt");

    @TempDir
    static Path compiledVerifier;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private static Path corpusDir() {
        // Gradle runs tests with the module directory as working directory.
        Path dir = Path.of(System.getProperty("user.dir")).resolve("../docs/experiments/book-corpus-v1").normalize();
        assertTrue(Files.isDirectory(dir), "book-corpus-v1 contract directory not found: " + dir);
        return dir;
    }

    private static Path repoRoot() {
        return corpusDir().resolve("../../..").normalize();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @BeforeAll
    static void compileVerifierOnce() {
        // Compiling once keeps real-CLI coverage without a source-launcher compile per test.
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not a JRE) is required to compile the verifier");
        int status = compiler.run(null, null, null, "-d", compiledVerifier.toString(),
                corpusDir().resolve("VerifyBookCorpus.java").toString());
        assertEquals(0, status, "VerifyBookCorpus.java failed to compile");
    }

    private static Result verify(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-cp", compiledVerifier.toString(),
                "VerifyBookCorpus"));
        command.addAll(List.of(args));
        return execute(command);
    }

    private static Result verifyWithSourceLauncher(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(javaExecutable(), CONTRACT_DIR + "/VerifyBookCorpus.java"));
        command.addAll(List.of(args));
        return execute(command);
    }

    private static Result execute(List<String> command) throws IOException, InterruptedException {
        // Redirect to files so a stalled child cannot block the timeout below on a pipe read.
        Path out = Files.createTempFile("verify-book-corpus", ".out");
        Path err = Files.createTempFile("verify-book-corpus", ".err");
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot().toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError("verifier did not finish within 2 minutes; stderr: "
                        + Files.readString(err, StandardCharsets.UTF_8));
            }
            return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
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
        // Runs the exact command the README documents (JDK source launcher from the repository root).
        var result = verifyWithSourceLauncher("--manifest", fixtureManifest(dir).toString(), missing.toString());
        assertEquals(2, result.exitCode(), result.stderr());
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
                input(dir, (BOM + sample()).getBytes(StandardCharsets.UTF_8)).toString());
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
    void gitTracksNoBookBytes() throws Exception {
        assertEquals(CONTRACT_FILES, trackedSmallFiles(CONTRACT_DIR),
                CONTRACT_DIR + " may track only its contract files; book bytes are never committed");
        assertEquals(FIXTURE_FILES, trackedSmallFiles(FIXTURE_DIR),
                FIXTURE_DIR + " may track only the original fixture files");

        var manifest = new Properties();
        try (InputStream in = Files.newInputStream(corpusDir().resolve("manifest.properties"))) {
            manifest.load(in);
        }
        Set<String> pinnedDigests = Set.of(manifest.getProperty("raw.sha256"), manifest.getProperty("normalized.sha256"));
        Set<Long> pinnedSizes = Set.of(Long.parseLong(manifest.getProperty("raw.sizeBytes")),
                Long.parseLong(manifest.getProperty("normalized.sizeBytes")));
        for (String tracked : gitLsFiles(".")) {
            Path file = repoRoot().resolve(tracked);
            // Only files of exactly a pinned size can be the book; hash just those.
            if (Files.isRegularFile(file) && pinnedSizes.contains(Files.size(file))) {
                String digest = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                assertFalse(pinnedDigests.contains(digest), "pinned book bytes are tracked in Git: " + tracked);
            }
        }
    }

    /** Tracked files directly under {@code dir}, each checked to be small and not nested. */
    private static Set<String> trackedSmallFiles(String dir) throws Exception {
        Set<String> names = new TreeSet<>();
        for (String tracked : gitLsFiles(dir)) {
            String name = tracked.substring(dir.length() + 1);
            assertFalse(name.contains("/"), "unexpected nested tracked file: " + tracked);
            assertTrue(Files.size(repoRoot().resolve(tracked)) <= MAX_TRACKED_FILE_BYTES,
                    "tracked file too large to be contract-only: " + tracked);
            names.add(name);
        }
        return names;
    }

    private static List<String> gitLsFiles(String pathspec) throws Exception {
        Result result;
        try {
            result = execute(List.of("git", "-C", repoRoot().toString(), "ls-files", "-z", "--", pathspec));
        } catch (IOException e) {
            assumeTrue(false, "git is not available; the tracked-file guard runs in CI checkouts");
            throw e;
        }
        assumeTrue(result.exitCode() == 0, "not a git checkout; the tracked-file guard runs in CI checkouts");
        if (result.stdout().isEmpty()) {
            fail("git tracks nothing under " + pathspec);
        }
        return List.of(result.stdout().split("\0"));
    }

    @Test
    void writingNormalizedOverTheInputIsRejected(@TempDir Path dir) throws Exception {
        Path input = input(dir, resource("sample.txt"));
        var result = verify("--manifest", fixtureManifest(dir).toString(), "--write-normalized",
                dir.resolve(".").resolve("input.txt").toString(), input.toString());
        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("must not name the input file"), result.stderr());
        assertArrayEquals(resource("sample.txt"), Files.readAllBytes(input), "raw input must stay untouched");
    }

    @Test
    void unsupportedNormalizationPolicyIsABadManifest(@TempDir Path dir) throws Exception {
        Path manifest = fixtureManifest(dir);
        Files.writeString(manifest, Files.readString(manifest).replace("normalize.policy=v1", "normalize.policy=v2"));
        var result = verify("--manifest", manifest.toString(), input(dir, resource("sample.txt")).toString());
        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("normalize.policy=v2 is not implemented"), result.stderr());
    }

    @Test
    void malformedManifestEscapeIsABadManifest(@TempDir Path dir) throws Exception {
        Path manifest = fixtureManifest(dir);
        Files.writeString(manifest, Files.readString(manifest) + "broken=\\u12G4\n");
        var result = verify("--manifest", manifest.toString(), input(dir, resource("sample.txt")).toString());
        assertEquals(2, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("cannot read manifest"), result.stderr());
    }

    @Test
    void bootstrapManifestPrintsDigestsWithoutPinnedValues(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("bootstrap.properties");
        Files.writeString(manifest, """
                corpus=book-corpus-fixture
                corpusVersion=2
                normalize.policy=v1
                normalize.startMarkerPrefix=*** BEGIN FIXTURE TEXT
                normalize.endMarkerPrefix=*** FINISH FIXTURE TEXT
                """);
        var result = verify("--manifest", manifest.toString(), "--print-digests",
                input(dir, resource("sample.txt")).toString());
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("raw.sha256=dc516fe5ef20e4b137d1ba287f86e8e93d1a8d16e1cfc9740dd4aa15ebde2b84"),
                result.stdout());
        assertTrue(result.stdout().contains("raw.eol=CRLF"), result.stdout());
        assertTrue(result.stdout().contains("normalized.lineCount=3"), result.stdout());
    }

    @Test
    void declaredRawLineEndingsAreEnforced(@TempDir Path dir) throws Exception {
        Path manifest = fixtureManifest(dir);
        Files.writeString(manifest, Files.readString(manifest).replace("raw.eol=CRLF", "raw.eol=LF"));
        var result = verify("--manifest", manifest.toString(), input(dir, resource("sample.txt")).toString());
        assertEquals(1, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("eol=LF"), result.stderr());
        assertTrue(result.stderr().contains("eol=CRLF"), result.stderr());
    }

    @Test
    void declaredValuesThePolicyCannotGuaranteeAreABadManifest(@TempDir Path dir) throws Exception {
        Path manifest = fixtureManifest(dir);
        Files.writeString(manifest, Files.readString(manifest) + "normalized.unicodeForm=NFD\n");
        var result = verify("--manifest", manifest.toString(), input(dir, resource("sample.txt")).toString());
        assertEquals(2, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("normalized.unicodeForm=NFD is not guaranteed"), result.stderr());
    }

    @Test
    void normalizedOutputReplacesAnExistingFileAtomically(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("normalized.txt");
        Files.writeString(out, "stale previous output");
        var result = verify("--manifest", fixtureManifest(dir).toString(), "--write-normalized", out.toString(),
                input(dir, resource("sample.txt")).toString());
        assertEquals(0, result.exitCode(), result.stderr());
        assertArrayEquals(FIXTURE_NORMALIZED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out));
        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "temp file left behind");
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
