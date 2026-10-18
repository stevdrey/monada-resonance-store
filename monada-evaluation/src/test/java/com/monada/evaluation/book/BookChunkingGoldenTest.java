package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden, CLI and cross-JVM checks over the original {@code book-chunking-fixture-v1} fixture. The golden files
 * use a small fixture policy so that packing, whitespace splitting, hard splitting and repeated content all occur.
 */
class BookChunkingGoldenTest {
    private static final String FIXTURE = "/book-chunking-fixture-v1/";
    private static final BookChunkingPolicy FIXTURE_POLICY = new BookChunkingPolicy("fixture-max200",
            "CHAPTER [IVXLC]+\\.", 200, 0);
    private static final int JVMS = 3;

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = BookChunkingGoldenTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private Path copyFixture(String name) throws IOException {
        Path target = dir.resolve(name);
        Files.write(target, resource(name));
        return target;
    }

    private BookChunker.Result fixtureResult(BookChunkingPolicy policy) throws Exception {
        return BookChunker.chunk(VerifiedBookSource.load(copyFixture("manifest.properties"),
                copyFixture("body.txt")), policy);
    }

    private static Run runMain(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookChunkingMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void fixtureMatchesGoldenChunksAndSidecarByteForByte() throws Exception {
        BookChunkOutputWriter.Output output = BookChunkOutputWriter.render(fixtureResult(FIXTURE_POLICY));
        assertEquals(new String(resource("expected-chunks.jsonl"), StandardCharsets.UTF_8),
                new String(output.chunks(), StandardCharsets.UTF_8));
        assertEquals(new String(resource("expected-sidecar.jsonl"), StandardCharsets.UTF_8),
                new String(output.sidecar(), StandardCharsets.UTF_8));
    }

    @Test
    void goldenSidecarSpansResolveToTheGoldenChunkTexts() throws Exception {
        byte[] body = resource("body.txt");
        List<String> chunkLines = new String(resource("expected-chunks.jsonl"), StandardCharsets.UTF_8).lines()
                .toList();
        List<String> sidecarLines = new String(resource("expected-sidecar.jsonl"), StandardCharsets.UTF_8).lines()
                .toList();
        assertEquals(chunkLines.size() + 1, sidecarLines.size());
        assertTrue(sidecarLines.getFirst().contains("\"chunksFileSha256\":\""
                + VerifiedBookSource.sha256(resource("expected-chunks.jsonl")) + "\""));

        BookChunker.Result result = fixtureResult(FIXTURE_POLICY);
        for (int i = 0; i < chunkLines.size(); i++) {
            String provenance = sidecarLines.get(i + 1);
            int start = (int) number(provenance, "byteStart");
            int end = (int) number(provenance, "byteEnd");
            String text = new String(body, start, end - start, StandardCharsets.UTF_8);
            assertEquals(BookChunkOutputWriter.string(text), field(chunkLines.get(i), "text"));
            assertTrue(provenance.contains("\"sha256\":\""
                    + VerifiedBookSource.sha256(text.getBytes(StandardCharsets.UTF_8)) + "\""));
            assertEquals(result.chunks().get(i).text(), text);
        }
    }

    @Test
    void fixtureCoversEveryCaseTheGoldenIsMeantToPin() throws Exception {
        BookChunker.Result result = fixtureResult(FIXTURE_POLICY);
        BookChunkingReport report = BookChunkingReport.of(result, BookChunkOutputWriter.render(result));
        assertTrue(result.frontMatter());
        assertEquals(2, result.chapterCount());
        assertEquals(10, result.chunks().size());
        assertEquals(1, report.repeatedContentGroups());
        assertEquals(2, report.repeatedContentChunks());
        assertEquals(200, report.maxBytes());
        assertTrue(result.chunks().stream().anyMatch(c -> c.byteEnd() - c.byteStart()
                != c.codePointEnd() - c.codePointStart()), "multi-byte text present");
    }

    @Test
    void commandLineWritesFilesAndAReportWithManifestDigest() throws Exception {
        Path out = dir.resolve("out");
        Run run = runMain("--manifest", copyFixture("manifest.properties").toString(), "--input",
                copyFixture("body.txt").toString(), "--out", out.toString());
        assertEquals(0, run.exit(), run.err());

        BookChunker.Result result = BookChunker.chunk(VerifiedBookSource.load(dir.resolve("manifest.properties"),
                dir.resolve("body.txt")), BookChunkingPolicy.V1);
        BookChunkOutputWriter.Output expected = BookChunkOutputWriter.render(result);
        assertArrayEquals(expected.chunks(), Files.readAllBytes(out.resolve(BookChunkOutputWriter.CHUNKS_FILE)));
        assertArrayEquals(expected.sidecar(), Files.readAllBytes(out.resolve(BookChunkOutputWriter.SIDECAR_FILE)));
        assertEquals(BookChunkingReport.of(result, expected).render(), run.out());
        assertTrue(run.out().contains("manifest sha256: "
                + VerifiedBookSource.sha256(resource("manifest.properties"))));
        assertTrue(run.out().contains("chunk UTF-8 bytes: min="));
        assertTrue(run.out().contains("repeated content: groups="));
        assertFalse(run.out().contains(dir.toString()), "report must not depend on paths");
        try (var files = Files.list(out)) {
            assertEquals(2, files.count(), "no temporary files are left behind");
        }
    }

    @Test
    void commandLineExitCodes() throws Exception {
        Path manifest = copyFixture("manifest.properties");
        Path body = copyFixture("body.txt");
        assertEquals(2, runMain().exit());
        assertEquals(2, runMain("--input", body.toString()).exit());
        assertEquals(2, runMain("--bogus", "x").exit());
        assertEquals(2, runMain("--input", body.toString(), "--input", body.toString(), "--out", "x").exit());
        assertEquals(2, runMain("--manifest", manifest.toString(), "--input", dir.resolve("absent.txt").toString(),
                "--out", dir.resolve("o").toString()).exit());

        Path badManifest = dir.resolve("bad.properties");
        Files.writeString(badManifest, "corpus=c\n");
        assertEquals(2, runMain("--manifest", badManifest.toString(), "--input", body.toString(), "--out",
                dir.resolve("o").toString()).exit());

        Path tampered = dir.resolve("tampered.txt");
        Files.writeString(tampered, Files.readString(body).replace("gulls", "GULLS"));
        assertEquals(1, runMain("--manifest", manifest.toString(), "--input", tampered.toString(), "--out",
                dir.resolve("o").toString()).exit());

        Path clash = dir.resolve(BookChunkOutputWriter.CHUNKS_FILE);
        Files.copy(body, clash);
        Run refused = runMain("--manifest", manifest.toString(), "--input", clash.toString(), "--out",
                dir.toString());
        assertEquals(2, refused.exit());
        assertTrue(refused.err().contains("refusing to overwrite"), refused.err());
        assertArrayEquals(Files.readAllBytes(body), Files.readAllBytes(clash));
    }

    @Test
    void inputsNamedLikeOldTemporaryFilesAreNeverOverwritten() throws Exception {
        Path manifest = copyFixture("manifest.properties");
        Path out = Files.createDirectories(dir.resolve("out"));
        for (String file : new String[] {BookChunkOutputWriter.CHUNKS_FILE, BookChunkOutputWriter.SIDECAR_FILE}) {
            Path input = out.resolve(file + ".tmp");
            Files.write(input, resource("body.txt"));
            assertEquals(0, runMain("--manifest", manifest.toString(), "--input", input.toString(), "--out",
                    out.toString()).exit());
            assertArrayEquals(resource("body.txt"), Files.readAllBytes(input), file);
            Files.delete(input);
        }
        try (var files = Files.list(out)) {
            assertEquals(List.of(BookChunkOutputWriter.CHUNKS_FILE, BookChunkOutputWriter.SIDECAR_FILE),
                    files.map(p -> p.getFileName().toString()).sorted().toList(), "no temporary files left");
        }
    }

    /**
     * Runs the command in {@value #JVMS} concurrent JVMs on the module's runtime classpath and requires byte-identical
     * files and reports, which an in-JVM comparison cannot prove for hash-seeded orders.
     */
    @Test
    void outputIsByteIdenticalAcrossJvms() throws Exception {
        String classpath = System.getProperty("forge.runtime.classpath");
        org.junit.jupiter.api.Assumptions.assumeTrue(classpath != null && !classpath.isBlank(),
                "forge.runtime.classpath is set by the Gradle test task");
        Path manifest = copyFixture("manifest.properties");
        Path body = copyFixture("body.txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<Process> processes = new ArrayList<>();
        for (int i = 0; i < JVMS; i++) {
            Path out = dir.resolve("jvm" + i);
            processes.add(new ProcessBuilder(java, "-cp", classpath, BookChunkingMain.class.getName(),
                    "--manifest", manifest.toString(), "--input", body.toString(), "--out", out.toString())
                    .redirectOutput(dir.resolve("jvm" + i + ".out").toFile())
                    .redirectError(dir.resolve("jvm" + i + ".err").toFile())
                    .start());
        }
        for (int i = 0; i < JVMS; i++) {
            Process process = processes.get(i);
            assertTrue(process.waitFor(2, TimeUnit.MINUTES), "child JVM timed out");
            assertEquals(0, process.exitValue(), Files.readString(dir.resolve("jvm" + i + ".err")));
        }
        for (int i = 1; i < JVMS; i++) {
            for (String file : new String[] {BookChunkOutputWriter.CHUNKS_FILE, BookChunkOutputWriter.SIDECAR_FILE}) {
                assertArrayEquals(Files.readAllBytes(dir.resolve("jvm0").resolve(file)),
                        Files.readAllBytes(dir.resolve("jvm" + i).resolve(file)), file);
            }
            assertEquals(Files.readString(dir.resolve("jvm0.out")), Files.readString(dir.resolve("jvm" + i + ".out")));
        }
        BookChunkOutputWriter.Output inProcess = BookChunkOutputWriter.render(fixtureResult(BookChunkingPolicy.V1));
        assertArrayEquals(inProcess.sidecar(),
                Files.readAllBytes(dir.resolve("jvm0").resolve(BookChunkOutputWriter.SIDECAR_FILE)));
    }

    /** {@code --out ""} names the working directory; it must write there rather than fail on a parentless path. */
    @Test
    void emptyOutputPathWritesIntoTheWorkingDirectory() throws Exception {
        String classpath = System.getProperty("forge.runtime.classpath");
        org.junit.jupiter.api.Assumptions.assumeTrue(classpath != null && !classpath.isBlank(),
                "forge.runtime.classpath is set by the Gradle test task");
        Path manifest = copyFixture("manifest.properties");
        Path body = copyFixture("body.txt");
        Path cwd = Files.createDirectories(dir.resolve("cwd"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, BookChunkingMain.class.getName(), "--manifest", manifest.toString(), "--input",
                body.toString(), "--out", "")
                .directory(cwd.toFile())
                .redirectOutput(dir.resolve("empty-out.out").toFile())
                .redirectError(dir.resolve("empty-out.err").toFile())
                .start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "child JVM timed out");
        assertEquals(0, process.exitValue(), Files.readString(dir.resolve("empty-out.err")));
        BookChunkOutputWriter.Output expected = BookChunkOutputWriter.render(fixtureResult(BookChunkingPolicy.V1));
        assertArrayEquals(expected.chunks(), Files.readAllBytes(cwd.resolve(BookChunkOutputWriter.CHUNKS_FILE)));
        assertArrayEquals(expected.sidecar(), Files.readAllBytes(cwd.resolve(BookChunkOutputWriter.SIDECAR_FILE)));
    }

    private static long number(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), name);
        return Long.parseLong(m.group(1));
    }

    /** The raw JSON string value of the last field, which is {@code text} in a chunks line. */
    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":");
        assertTrue(at >= 0, name);
        return json.substring(at + name.length() + 3, json.length() - 1);
    }
}
