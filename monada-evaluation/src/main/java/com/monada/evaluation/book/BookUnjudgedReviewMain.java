package com.monada.evaluation.book;

import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Offline review workflow of {@code book-queries-v2} (Issue #177, evaluation only; no network, no model):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookUnjudgedReview --args="select [--baseline &lt;report&gt;] [--out &lt;file&gt;]"
 * ./gradlew :monada-evaluation:runBookUnjudgedReview --args="worksheet --input &lt;body.txt&gt; --out &lt;sheet.md&gt;"
 * ./gradlew :monada-evaluation:runBookUnjudgedReview --args="apply --input &lt;body.txt&gt; --worksheet &lt;sheet.md&gt;
 *     --dataset &lt;book-queries-v2 dir&gt;"
 * ./gradlew :monada-evaluation:runBookUnjudgedReview --args="report [--baseline &lt;report&gt;] [--out &lt;file&gt;]"
 * </pre>
 *
 * <p>{@code select} re-derives the selection from the frozen baseline (default {@value
 * BookUnjudgedSelection#BASELINE_REPORT}) and checks it against the committed receipt, or writes it to {@code --out}.
 * {@code worksheet} writes the score-blind review sheet, which holds book text: it refuses any path inside a Git
 * work tree and never overwrites a file. {@code apply} turns the filled sheet into {@code review.psv}, updates only
 * {@code review.sha256} and {@code counts.*} of the v2 manifest, and writes nothing unless the result validates; it
 * never touches the review status or sign-off. {@code report} renders the coverage report.
 *
 * <p>Exit 0 on success, 1 when the inputs fail validation or the receipt is not reproduced, 2 on a usage error, a
 * missing file, an unsafe output path or an I/O failure.
 */
public final class BookUnjudgedReviewMain {
    private static final String USAGE = "usage: select [--baseline <report>] [--out <file>]"
            + " | worksheet --input <body.txt> --out <sheet.md> [--manifest <corpus manifest>]"
            + " | apply --input <body.txt> --worksheet <sheet.md> --dataset <book-queries-v2 dir>"
            + " [--manifest <corpus manifest>] | report [--baseline <report>] [--out <file>]";
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "select", Set.of("--baseline", "--out"),
            "worksheet", Set.of("--input", "--out", "--manifest"),
            "apply", Set.of("--input", "--worksheet", "--dataset", "--manifest"),
            "report", Set.of("--baseline", "--out"));
    /** Fields that attest to one review digest; all must be cleared before different decisions are applied. */
    private static final List<String> SIGN_OFF = List.of("judgment.reviewer", "judgment.approvedBy",
            "judgment.reviewedAt", "judgment.draftedAt", "judgment.verification", "judgment.draftBlinding");
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "select", Set.of(), "worksheet", Set.of("--input", "--out"),
            "apply", Set.of("--input", "--worksheet", "--dataset"), "report", Set.of());

    private BookUnjudgedReviewMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !OPTIONS.containsKey(args[0])) {
            err.println(USAGE);
            return 2;
        }
        String command = args[0];
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 1, OPTIONS.get(command), command, err,
                USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.keySet().containsAll(REQUIRED.get(command))) {
            err.println(USAGE);
            return 2;
        }
        try {
            return switch (command) {
                case "select" -> select(options, out, err);
                case "worksheet" -> worksheet(options, out, err);
                case "apply" -> apply(options, out);
                default -> report(options, out);
            };
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile());
            return 2;
        } catch (BookInputException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("I/O failure: " + e);
            return 2;
        }
    }

    private static int select(Map<String, String> options, PrintStream out, PrintStream err) throws IOException {
        String receipt = BookUnjudgedSelection.render(BookUnjudgedSelection.select(trace(options)));
        if (options.containsKey("--out")) {
            BookRecallMain.write(Path.of(options.get("--out")), receipt);
            out.println("wrote " + options.get("--out"));
            return 0;
        }
        if (!Arrays.equals(receipt.getBytes(StandardCharsets.UTF_8), BookQueryDatasetV2.resource("selection.psv"))) {
            err.println("the committed selection.psv is not reproduced by " + BookUnjudgedSelection.RULE);
            return 1;
        }
        out.println("selection.psv reproduced: " + BookUnjudgedSelection.parse(
                receipt.getBytes(StandardCharsets.UTF_8)).size() + " pairs, sha256 "
                + BookQueryDataset.sha256(receipt.getBytes(StandardCharsets.UTF_8)));
        return 0;
    }

    private static int worksheet(Map<String, String> options, PrintStream out, PrintStream err)
            throws IOException, BookInputException {
        Path target = Path.of(options.get("--out"));
        if (insideGitWorkTree(target)) {
            err.println("--out must be outside any Git work tree; the worksheet holds book text: " + target);
            return 2;
        }
        if (Files.exists(target)) {
            err.println("--out already exists and is never overwritten (it may hold review work): " + target);
            return 2;
        }
        byte[] body = body(options);
        BookQueryDatasetV2 v2 = BookQueryDatasetV2.load();
        Files.writeString(target, BookReviewWorksheet.render(body, v2.base(), v2.selection()), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        out.println("wrote " + target + " (" + v2.selection().size() + " pairs; keep it outside Git)");
        return 0;
    }

    private static int apply(Map<String, String> options, PrintStream out) throws IOException, BookInputException {
        byte[] body = body(options);
        Path dir = Path.of(options.get("--dataset"));
        Path manifestPath = dir.resolve("manifest.properties");
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        // Strict UTF-8 and escapes: a defect is a validation failure (exit 1), not an I/O error.
        Properties currentManifest = BookQueryDatasetV2.readManifest(manifestBytes);
        String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
        byte[] selection = Files.readAllBytes(dir.resolve(BookQueryDatasetV2.SELECTION_FILE));
        BookQueryDataset base = BookQueryDataset.load();
        byte[] baseManifest = BookQueryDataset.resource("manifest.properties");
        // The current dataset validates first, so the worksheet is only ever read against known queries and chunks.
        // "Changed" is then decided against the review actually on disk: a hand-edited manifest digest must not make
        // different decisions look like a re-apply of the signed ones.
        Path reviewPath = dir.resolve(BookQueryDatasetV2.REVIEW_FILE);
        byte[] current = Files.readAllBytes(reviewPath);
        BookQueryDatasetV2 before;
        try {
            before = BookQueryDatasetV2.parse(currentManifest, base, baseManifest, selection, current);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("the current manifest and review.psv do not validate (" + e.getMessage()
                    + "); restore them before applying a worksheet", e);
        }
        Path sheetPath = Path.of(options.get("--worksheet"));
        // The worksheet holds book text and the review backup is written next to it, so both stay outside Git.
        check(!insideGitWorkTree(sheetPath), "--worksheet must be outside any Git work tree: " + sheetPath);
        String sheet = Files.readString(sheetPath, StandardCharsets.UTF_8);
        String review = BookReviewWorksheet.apply(sheet, body, base, before.selection());
        byte[] reviewBytes = review.getBytes(StandardCharsets.UTF_8);
        boolean changed = !Arrays.equals(reviewBytes, current);
        requireUnsignedChange(currentManifest, changed);

        String updated = replace(manifestText, "review.sha256", BookQueryDataset.sha256(reviewBytes));
        if (changed) {
            // A decision carried over unchanged from the review on disk keeps the protocol it was made under, so the
            // record is then the weaker of the two; otherwise every decision comes from this sheet.
            String sheetProtocol = BookReviewWorksheet.protocolOf(sheet);
            boolean carried = carriesDecisions(current, reviewBytes);
            String protocol = carried ? weakerProtocol(currentManifest.getProperty("review.protocol"), sheetProtocol)
                    : sheetProtocol;
            updated = replace(updated, "review.protocol", protocol);
        }
        BookQueryDatasetV2 built = BookQueryDatasetV2.build(manifestOf(updated), base, baseManifest, selection,
                reviewBytes);
        for (var e : BookQueryDatasetV2.expectedCounts(built).entrySet()) {
            updated = replace(updated, e.getKey(), e.getValue());
        }
        // Validate everything, sign-off included, before anything is written.
        BookQueryDatasetV2 checked = BookQueryDatasetV2.parse(manifestOf(updated), base, baseManifest, selection,
                reviewBytes);
        writeTogether(reviewPath, review, manifestPath, updated,
                sheetPath.resolveSibling(sheetPath.getFileName() + ".review-backup.psv"));
        out.println("wrote review.psv: " + checked.reviews().size() + " decided, " + checked.pending().size()
                + " pending, " + checked.addedJudgments().size() + " added judgments, " + checked.findings().size()
                + " findings; review status unchanged (" + checked.manifest().getProperty("judgment.reviewStatus")
                + ")");
        return 0;
    }

    private static int report(Map<String, String> options, PrintStream out) throws IOException {
        String report = BookUnjudgedReviewReport.render(trace(options), BookQueryDatasetV2.load());
        if (options.containsKey("--out")) {
            BookRecallMain.write(Path.of(options.get("--out")), report);
            out.println("wrote " + options.get("--out"));
        } else {
            out.print(report);
        }
        return 0;
    }

    private static List<BookUnjudgedSelection.Hit> trace(Map<String, String> options) throws IOException {
        byte[] baseline = Files.readAllBytes(
                Path.of(options.getOrDefault("--baseline", BookUnjudgedSelection.BASELINE_REPORT)));
        return BookUnjudgedSelection.parseTrace(baseline, BookQueryDataset.load());
    }

    private static byte[] body(Map<String, String> options) throws IOException, BookInputException {
        VerifiedBookSource source = VerifiedBookSource.load(
                Path.of(options.getOrDefault("--manifest", BookChunkingMain.DEFAULT_MANIFEST)),
                Path.of(options.get("--input")));
        String pinned = BookQueryDataset.load().manifest().getProperty("source.sha256");
        if (!pinned.equals(source.sha256())) {
            throw new IllegalStateException("body SHA-256 " + source.sha256() + " is not the judged body " + pinned);
        }
        return source.text().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code target} lies in a Git work tree (see {@link #isGitMarker}), compared on real paths. */
    static boolean insideGitWorkTree(Path target) throws IOException {
        Path existing = target.toAbsolutePath().normalize();
        while (existing != null && Files.notExists(existing)) {
            existing = existing.getParent();
        }
        for (Path dir = existing == null ? null : existing.toRealPath(); dir != null; dir = dir.getParent()) {
            if (isGitMarker(dir.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    /** A repository's {@code .git} directory (it has {@code HEAD}) or a linked worktree's {@code gitdir:} file. */
    private static boolean isGitMarker(Path git) throws IOException {
        if (Files.isDirectory(git)) {
            return Files.exists(git.resolve("HEAD"));
        }
        return Files.isRegularFile(git)
                && Files.readString(git, StandardCharsets.UTF_8).startsWith("gitdir:");
    }

    /**
     * A sign-off and a draft verification cover one review digest: changing the decisions would keep a stale
     * reviewer, approver, date or "each drafted decision verified" claim on labels nobody approved. Re-applying the same
     * decisions stays allowed. {@code changed} compares the new review with the one on disk.
     */
    static void requireUnsignedChange(Properties manifest, boolean changed) {
        if (!changed) {
            return;
        }
        // Status alone is not enough: a leftover reviewer, approver, date or draft verification would be re-attached to
        // the new digest by a later status-only edit back to REVIEWED; they must be re-entered for the new decisions.
        boolean signed = "REVIEWED".equals(manifest.getProperty("judgment.reviewStatus"))
                || SIGN_OFF.stream().anyMatch(key -> !manifest.getProperty(key, "").isBlank());
        if (signed) {
            throw new IllegalStateException("these decisions differ from the signed-off review.psv; set"
                    + " judgment.reviewStatus to PENDING_HUMAN_REVIEW and clear " + String.join(", ", SIGN_OFF)
                    + " first");
        }
    }

    /**
     * Replaces {@code review} and then {@code manifest}, each atomically. The previous review is first copied to
     * {@code backup} (next to the worksheet, so outside Git): if the manifest write fails it is restored, and if the
     * process dies between the two moves the copy survives for recovery. The backup is removed once both are written.
     */
    static void writeTogether(Path review, String reviewText, Path manifest, String manifestText, Path backup)
            throws IOException {
        byte[] previous = Files.exists(review) ? Files.readAllBytes(review) : null;
        if (previous != null) {
            Files.write(backup, previous);
        }
        BookRecallMain.write(review, reviewText);
        try {
            BookRecallMain.write(manifest, manifestText);
        } catch (IOException | RuntimeException e) {
            try {
                if (previous == null) {
                    Files.deleteIfExists(review);
                } else {
                    Files.write(review, previous);
                }
            } catch (IOException | RuntimeException restore) {
                e.addSuppressed(restore);
                e.addSuppressed(new IOException("the previous review.psv is kept at " + backup));
                throw e;
            }
            Files.deleteIfExists(backup);
            throw e;
        }
        Files.deleteIfExists(backup);
    }

    /** Whether {@code next} keeps any decision row of {@code previous} unchanged (headers excluded). */
    static boolean carriesDecisions(byte[] previous, byte[] next) {
        List<String> before = List.of(new String(previous, StandardCharsets.UTF_8).split("\n"));
        return List.of(new String(next, StandardCharsets.UTF_8).split("\n")).stream().skip(1)
                .anyMatch(row -> before.subList(1, before.size()).contains(row));
    }

    /** {@code protocol} values ordered from weakest blinding to strongest: the legacy sheet showed chunk keys. */
    static String weakerProtocol(String a, String b) {
        List<String> order = List.of(BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL, BookQueryDatasetV2.REVIEW_PROTOCOL);
        check(order.contains(a) && order.contains(b), "unknown review protocol " + (order.contains(a) ? b : a));
        return order.indexOf(a) <= order.indexOf(b) ? a : b;
    }

    /** Replaces the value of exactly one existing {@code key=} line. */
    static String replace(String manifest, String key, String value) {
        List<String> lines = new ArrayList<>(List.of(manifest.split("\n", -1)));
        int hits = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                hits++;
            }
        }
        if (hits != 1) {
            throw new IllegalStateException("manifest must have exactly one " + key + " line, found " + hits);
        }
        return String.join("\n", lines);
    }

    private static Properties manifestOf(String text) {
        return BookQueryDatasetV2.readManifest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
