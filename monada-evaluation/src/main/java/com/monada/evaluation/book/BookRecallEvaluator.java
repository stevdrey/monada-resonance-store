package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.evaluation.HitAtK;
import com.monada.evaluation.PrecisionAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.storage.JsonStrings;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Measures how well a seeded book Store recalls the judged evidence of {@code book-queries-v1} (Issue #167, Book 5/8;
 * evaluation only, exploratory). A hit only counts when the frozen judgments call its chunk evidence: wrong evidence,
 * missed evidence and NO_ANSWER false hits are reported instead of treating any hit as success.
 *
 * <p>Inputs are bound before anything is measured: the work directory's chunk files must be exactly the chunk set the
 * judgments were made against (the dataset's chunk map is that run's sidecar, pinned by digest), the Store must pass
 * the #165 readback on fresh instances and carry the default encoding profile, and every gold reference must name a
 * chunk of the set. Queries run through the public {@link MonadaMemory#resonate(String)} API with the default
 * options, no threshold and top-{@value #MAX_K}, on a throwaway Store copy, twice on separate instances: any
 * difference between the runs, any order other than score descending then atom id ascending, or any hit outside the
 * chunk set fails the evaluation. Nothing is tuned: there is one configuration, and the HOLDOUT partition is
 * reported, never used to choose anything.
 *
 * <p>Relevance is per stored atom: chunks with identical text share one content-derived atom, so every key is mapped
 * to the first key of its atom (its relevance key) before the shared metrics are computed.
 */
final class BookRecallEvaluator {
    static final List<Integer> KS = List.of(1, 3, 5, 10);
    static final int MAX_K = 10;
    /** Exploration grid for the uncalibrated similarity threshold; fixed before any run, never tuned. */
    static final List<Double> THRESHOLD_GRID = List.of(0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45,
            0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95);
    static final String PROFILE = "MonadaMemoryOptions.defaults()";

    private BookRecallEvaluator() {
    }

    /** One raw hit: the stored atom id and its similarity score. */
    record Hit(String atomId, double score) {
    }

    /** Runs one query against an opened Store. */
    @FunctionalInterface
    interface Retriever {
        List<Hit> search(String query, int k);
    }

    /** Opens a fresh {@link Retriever} on a throwaway Store copy; called once per run. */
    @FunctionalInterface
    interface RetrieverFactory {
        Retriever open(Path storeCopy) throws IOException;
    }

    /** The public API with default options, no threshold: every top-K hit stays visible. */
    static final RetrieverFactory DEFAULT_RETRIEVER = copy -> {
        MonadaMemory memory = MonadaMemory.open(copy);
        return (query, k) -> memory.resonate(query).topK(k).threshold(Double.NEGATIVE_INFINITY).execute().results()
                .stream().map((ResonanceResult r) -> new Hit(r.atom().id(), r.score())).toList();
    };

    /** Everything that identifies the measured inputs and configuration; rendered as the report header. */
    record Identity(Map<String, String> fields) {
        Identity {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * One retrieved chunk.
     *
     * @param key      relevance key: the first chunk key whose text is this atom
     * @param keys     every chunk key with this atom's text (more than one only for repeated text)
     * @param ordinals the chunk ordinals of {@code keys}
     * @param section  the section heading (or kind) of {@code key}
     * @param label    {@code E2}/{@code E1} judged evidence of that grade, {@code C0} judged confounder,
     *                 {@code U} unjudged for this query (not assumed non-relevant)
     */
    record RankedHit(int rank, String atomId, double score, String key, List<String> keys, List<Integer> ordinals,
                     String section, String label) {
        RankedHit {
            keys = List.copyOf(keys);
            ordinals = List.copyOf(ordinals);
        }
    }

    /**
     * One query's outcome. The metric maps are empty for NO_ANSWER queries (they have no relevant set), and
     * {@code firstRelevantRank} is 0 when no evidence was retrieved.
     */
    record CaseResult(Case query, List<RankedHit> hits, Map<Integer, Double> precision, Map<Integer, Double> recall,
                      Map<Integer, Double> hit, Map<Integer, Double> directHit, double reciprocalRank,
                      int firstRelevantRank, BookRecallFailure failure) {
        CaseResult {
            hits = List.copyOf(hits);
            precision = Map.copyOf(precision);
            recall = Map.copyOf(recall);
            hit = Map.copyOf(hit);
            directHit = Map.copyOf(directHit);
        }

        boolean answerable() {
            return query.answerable();
        }
    }

    /**
     * Means over the answerable queries of one group ({@code answerable} is the denominator), NO_ANSWER queries
     * counted separately, and the judged-confounder and unjudged hits retrieved at each K by answerable queries.
     */
    record Aggregate(String name, int queries, int answerable, int negatives, Map<Integer, Double> precision,
                     Map<Integer, Double> recall, Map<Integer, Double> hit, Map<Integer, Double> directHit,
                     double mrr, Map<Integer, Integer> confounderHits, Map<Integer, Integer> unjudgedHits,
                     int negativesWithHits, Map<BookRecallFailure, Integer> failures) {
    }

    /**
     * Threshold exploration for one partition: at score threshold {@code threshold}, how many NO_ANSWER queries would
     * still return a hit and how many answerable queries would keep their first retrieved evidence.
     */
    record ThresholdPoint(Split split, double threshold, int negativesWithHit, int negatives, int answerableKept,
                          int answerable) {
    }

    record Result(Identity identity, List<CaseResult> cases, List<Aggregate> partitions, List<Aggregate> categories,
                  List<ThresholdPoint> thresholds) {
        Result {
            cases = List.copyOf(cases);
            partitions = List.copyOf(partitions);
            categories = List.copyOf(categories);
            thresholds = List.copyOf(thresholds);
        }
    }

    /** Evaluates {@code work} (already guarded by {@link ExperimentDirectoryGuard#requireSeeded}) with the API. */
    static Result evaluate(Path work, BookQueryDataset dataset) throws IOException, BookInputException {
        return evaluate(work, dataset, DEFAULT_RETRIEVER, null);
    }

    static Result evaluate(Path work, BookQueryDataset dataset, RetrieverFactory retriever, Path tmpRoot)
            throws IOException, BookInputException {
        Path chunkDir = work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR);
        Path store = work.resolve(ExperimentDirectoryGuard.STORE_DIR);
        byte[] chunkBytes = Files.readAllBytes(chunkDir.resolve(BookChunkOutputWriter.CHUNKS_FILE));
        byte[] sidecarBytes = Files.readAllBytes(chunkDir.resolve(BookChunkOutputWriter.SIDECAR_FILE));
        BookChunkFiles.Loaded chunks = BookChunkFiles.parse(chunkBytes, sidecarBytes);
        List<String> sidecarLines = new String(sidecarBytes, StandardCharsets.UTF_8).lines().toList();
        Map<String, String> header = BookChunkFiles.header(sidecarLines.getFirst());
        bind(dataset.manifest(), chunks, header, sidecarBytes);

        String storeDigest = BookStoreInventory.treeDigest(store);
        Manifest manifest = auditedManifest(store);
        if (manifest != null) {
            // Checked before readback: a feedback log is reported as feedback, not as a generic readback failure.
            requireNoFeedback(store, manifest);
        }
        BookStoreReadback.Verification readback = BookStoreReadback.verify(work, tmpRoot);
        if (!readback.passed() || !readback.chunks().equals(chunks)) {
            throw invalid("the Store does not pass the book readback (run runBookStoreReadback verify): "
                    + "status " + readback.audit().status() + ", exact readback " + readback.exactReadback() + "/"
                    + readback.expectedUniqueAtoms());
        }
        if (manifest == null || !Objects.equals(manifest.encodingProfile(),
                MonadaMemory.getExpectedProfile(manifest.dimensions(), MonadaMemoryOptions.defaults()))) {
            throw invalid("the Store's encoding profile is not the one " + PROFILE + " expects");
        }

        Map<String, BookChunkFiles.Entry> byKey = new LinkedHashMap<>();
        Map<String, List<BookChunkFiles.Entry>> byAtom = new LinkedHashMap<>();
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            byKey.put(entry.key(), entry);
            byAtom.computeIfAbsent(KnowledgeAtom.text(entry.text()).id(), id -> new ArrayList<>()).add(entry);
        }
        Map<String, String> relevanceKey = new LinkedHashMap<>();
        byAtom.values().forEach(group -> group.forEach(e -> relevanceKey.put(e.key(), group.getFirst().key())));
        Map<String, String> sections = sections(sidecarLines);

        List<Case> cases = dataset.cases().stream().sorted(Comparator.comparing(Case::id)).toList();
        validateGold(cases, byKey);

        List<List<Hit>> first = new ArrayList<>();
        List<List<Hit>> second = new ArrayList<>();
        BookStoreReadback.onCopy(store, tmpRoot, copy -> {
            run(retriever.open(copy), cases, first);
            run(retriever.open(copy), cases, second);
            return null;
        });
        if (!storeDigest.equals(BookStoreInventory.treeDigest(store))) {
            throw new IllegalStateException("the Store changed while it was evaluated");
        }

        List<CaseResult> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            List<Hit> hits = first.get(i);
            validateHits(c.id(), hits, byAtom.keySet());
            if (!hits.equals(second.get(i))) {
                throw new IllegalStateException(c.id() + ": unstable result ordering, two runs on fresh instances"
                        + " differ: " + hits + " vs " + second.get(i));
            }
            results.add(score(c, hits, byAtom, byKey, relevanceKey, sections));
        }

        Identity identity = identity(dataset.manifest(), chunks, header, sidecarBytes, manifest, readback,
                storeDigest);
        List<Aggregate> partitions = List.of(
                aggregate("DEV", results, r -> r.query().split() == Split.DEV),
                aggregate("HOLDOUT", results, r -> r.query().split() == Split.HOLDOUT),
                aggregate("ALL", results, r -> true));
        List<Aggregate> categories = new ArrayList<>();
        for (Category category : Category.values()) {
            categories.add(aggregate(category.name(), results, r -> r.query().category() == category));
        }
        return new Result(identity, results, partitions, categories, thresholds(results));
    }

    /** The manifest as the read-only auditor parses it, or null when it cannot (the readback then reports why). */
    private static Manifest auditedManifest(Path store) {
        try {
            return new StorageIntegrityAuditor().audit(store).manifest();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The default options rank feedback-aware, so recorded feedback would change scores and order without changing
     * the atom fingerprint: this baseline requires an absent or empty feedback log.
     */
    private static void requireNoFeedback(Path store, Manifest manifest) throws IOException, BookInputException {
        Path root = store.toAbsolutePath().normalize();
        Path log = root.resolve(manifest.feedbackSegment()).normalize();
        if (!log.startsWith(root)) {
            throw invalid("the Store's feedback segment resolves outside the Store");
        }
        if (Files.exists(log, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.size(log) > 0)) {
            throw invalid("the Store has recorded feedback; book recall is measured on a feedback-free Store only"
                    + " (seed a new work directory)");
        }
    }

    /** The chunk set in the work directory must be the one the judgments were made against. */
    private static void bind(Properties dataset, BookChunkFiles.Loaded chunks, Map<String, String> header,
                             byte[] sidecarBytes) throws BookInputException {
        expect("corpus", chunks.corpus(), dataset.getProperty("corpus"));
        expect("corpusVersion", chunks.corpusVersion(), dataset.getProperty("corpusVersion"));
        expect("source.sizeBytes", header.get("sourceBytes"), dataset.getProperty("source.sizeBytes"));
        expect("source.sha256", chunks.sourceSha256(), dataset.getProperty("source.sha256"));
        expect("corpusManifest.sha256", header.get("manifestSha256"), dataset.getProperty("corpusManifest.sha256"));
        expect("chunking.policy", chunks.policyId(), dataset.getProperty("chunking.policy"));
        expect("chunksFile.sha256", chunks.chunksFileSha256(), dataset.getProperty("chunksFile.sha256"));
        expect("chunkMap.sha256", VerifiedBookSource.sha256(sidecarBytes), dataset.getProperty("chunkMap.sha256"));
    }

    private static void expect(String field, String actual, String expected) throws BookInputException {
        if (!Objects.equals(actual, expected)) {
            throw invalid("foreign corpus input: the work directory's " + field + " is " + actual
                    + ", the judged dataset expects " + expected);
        }
    }

    /** Every gold reference must name a chunk of the set; answerable queries need evidence, NO_ANSWER none. */
    private static void validateGold(List<Case> cases, Map<String, BookChunkFiles.Entry> byKey)
            throws BookInputException {
        if (cases.isEmpty()) {
            throw invalid("the judged dataset has no queries");
        }
        for (Case c : cases) {
            for (Judgment j : c.judgments()) {
                if (!byKey.containsKey(j.chunkKey())) {
                    throw invalid(c.id() + ": invalid gold reference to unknown chunk " + j.chunkKey());
                }
            }
            if (c.answerable() && c.evidence().isEmpty()) {
                throw invalid(c.id() + ": empty gold, answerable query has no evidence");
            }
            if (!c.answerable() && !c.evidence().isEmpty()) {
                throw invalid(c.id() + ": invalid gold, NO_ANSWER query has evidence");
            }
        }
    }

    private static void run(Retriever retriever, List<Case> cases, List<List<Hit>> into) {
        for (Case c : cases) {
            into.add(List.copyOf(retriever.search(c.text(), MAX_K)));
        }
    }

    /** Top-K, known atoms only, finite scores, no repeats, score descending then atom id ascending. */
    private static void validateHits(String id, List<Hit> hits, Set<String> atoms) {
        if (hits.size() > MAX_K) {
            throw new IllegalStateException(id + ": " + hits.size() + " hits for top-" + MAX_K);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (!atoms.contains(hit.atomId())) {
                throw new IllegalStateException(id + ": hit " + hit.atomId() + " is not a chunk of this corpus");
            }
            if (!Double.isFinite(hit.score()) || !seen.add(hit.atomId())) {
                throw new IllegalStateException(id + ": invalid hit at rank " + (i + 1) + ": " + hit);
            }
            if (i > 0) {
                Hit previous = hits.get(i - 1);
                int byScore = Double.compare(previous.score(), hit.score());
                if (byScore < 0 || (byScore == 0 && previous.atomId().compareTo(hit.atomId()) > 0)) {
                    throw new IllegalStateException(id + ": unstable result ordering at rank " + (i + 1)
                            + ", expected score descending then atom id ascending: " + previous + " before " + hit);
                }
            }
        }
    }

    private static CaseResult score(Case c, List<Hit> hits, Map<String, List<BookChunkFiles.Entry>> byAtom,
                                    Map<String, BookChunkFiles.Entry> byKey, Map<String, String> relevanceKey,
                                    Map<String, String> sections) {
        Map<String, Judgment> judged = new LinkedHashMap<>();
        c.judgments().forEach(j -> judged.put(j.chunkKey(), j));
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> direct = new LinkedHashSet<>();
        Set<Integer> evidenceOrdinals = new HashSet<>();
        for (Judgment j : c.evidence()) {
            evidence.add(relevanceKey.get(j.chunkKey()));
            if (j.grade() == 2) {
                direct.add(relevanceKey.get(j.chunkKey()));
            }
            evidenceOrdinals.add(byKey.get(j.chunkKey()).ordinal());
        }
        Set<String> confounders = new LinkedHashSet<>();
        c.confounders().forEach(j -> confounders.add(relevanceKey.get(j.chunkKey())));
        confounders.removeAll(evidence);

        List<RankedHit> ranked = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            List<BookChunkFiles.Entry> group = byAtom.get(hit.atomId());
            List<String> keys = group.stream().map(BookChunkFiles.Entry::key).toList();
            String key = keys.getFirst();
            ranked.add(new RankedHit(i + 1, hit.atomId(), hit.score(), key, keys,
                    group.stream().map(BookChunkFiles.Entry::ordinal).toList(), sections.get(key),
                    label(keys, judged)));
        }
        List<String> keys = ranked.stream().map(RankedHit::key).toList();
        BookRecallFailure failure = BookRecallFailure.classify(c.answerable(),
                ranked.stream().map(r -> new BookRecallFailure.Ranked(r.key(), r.ordinals())).toList(), evidence,
                confounders, evidenceOrdinals);
        if (!c.answerable()) {
            return new CaseResult(c, ranked, Map.of(), Map.of(), Map.of(), Map.of(), 0.0, 0, failure);
        }
        Map<Integer, Double> precision = new TreeMap<>();
        Map<Integer, Double> recall = new TreeMap<>();
        Map<Integer, Double> hit = new TreeMap<>();
        Map<Integer, Double> directHit = new TreeMap<>();
        for (int k : KS) {
            precision.put(k, PrecisionAtK.compute(evidence, keys, k));
            recall.put(k, RecallAtK.compute(evidence, keys, k));
            hit.put(k, HitAtK.compute(evidence, keys, k));
            directHit.put(k, HitAtK.compute(direct, keys, k));
        }
        int firstRelevant = 0;
        for (int i = 0; i < keys.size() && firstRelevant == 0; i++) {
            if (evidence.contains(keys.get(i))) {
                firstRelevant = i + 1;
            }
        }
        return new CaseResult(c, ranked, precision, recall, hit, directHit, ReciprocalRank.compute(evidence, keys),
                firstRelevant, failure);
    }

    /** The strongest judgment of this query on any of the atom's chunk keys. */
    private static String label(List<String> keys, Map<String, Judgment> judged) {
        int grade = -1;
        boolean confounder = false;
        for (String key : keys) {
            Judgment j = judged.get(key);
            if (j != null && j.role() == Role.EVIDENCE) {
                grade = Math.max(grade, j.grade());
            } else if (j != null) {
                confounder = true;
            }
        }
        return grade > 0 ? "E" + grade : confounder ? "C0" : "U";
    }

    private static Aggregate aggregate(String name, List<CaseResult> all, Predicate<CaseResult> filter) {
        List<CaseResult> group = all.stream().filter(filter).toList();
        List<CaseResult> answerable = group.stream().filter(CaseResult::answerable).toList();
        List<CaseResult> negatives = group.stream().filter(r -> !r.answerable()).toList();
        Map<Integer, Double> precision = new TreeMap<>();
        Map<Integer, Double> recall = new TreeMap<>();
        Map<Integer, Double> hit = new TreeMap<>();
        Map<Integer, Double> directHit = new TreeMap<>();
        Map<Integer, Integer> confounderHits = new TreeMap<>();
        Map<Integer, Integer> unjudgedHits = new TreeMap<>();
        for (int k : KS) {
            precision.put(k, mean(answerable, r -> r.precision().get(k)));
            recall.put(k, mean(answerable, r -> r.recall().get(k)));
            hit.put(k, mean(answerable, r -> r.hit().get(k)));
            directHit.put(k, mean(answerable, r -> r.directHit().get(k)));
            confounderHits.put(k, countLabels(answerable, k, "C0"));
            unjudgedHits.put(k, countLabels(answerable, k, "U"));
        }
        Map<BookRecallFailure, Integer> failures = new EnumMap<>(BookRecallFailure.class);
        group.forEach(r -> failures.merge(r.failure(), 1, Integer::sum));
        return new Aggregate(name, group.size(), answerable.size(), negatives.size(), precision, recall, hit,
                directHit, mean(answerable, CaseResult::reciprocalRank), confounderHits, unjudgedHits,
                (int) negatives.stream().filter(r -> !r.hits().isEmpty()).count(), failures);
    }

    private static double mean(List<CaseResult> results, ToDoubleFunction<CaseResult> metric) {
        return results.isEmpty() ? Double.NaN : results.stream().mapToDouble(metric).sum() / results.size();
    }

    private static int countLabels(List<CaseResult> results, int k, String label) {
        return (int) results.stream().flatMap(r -> r.hits().stream().limit(k)).filter(h -> h.label().equals(label))
                .count();
    }

    private static List<ThresholdPoint> thresholds(List<CaseResult> results) {
        List<ThresholdPoint> points = new ArrayList<>();
        for (Split split : Split.values()) {
            List<CaseResult> group = results.stream().filter(r -> r.query().split() == split).toList();
            for (double t : THRESHOLD_GRID) {
                int negatives = 0;
                int negativesWithHit = 0;
                int answerable = 0;
                int kept = 0;
                for (CaseResult r : group) {
                    if (r.answerable()) {
                        answerable++;
                        if (r.firstRelevantRank() > 0 && r.hits().get(r.firstRelevantRank() - 1).score() >= t) {
                            kept++;
                        }
                    } else {
                        negatives++;
                        if (!r.hits().isEmpty() && r.hits().getFirst().score() >= t) {
                            negativesWithHit++;
                        }
                    }
                }
                points.add(new ThresholdPoint(split, t, negativesWithHit, negatives, kept, answerable));
            }
        }
        return points;
    }

    /** Chunk key to its section heading ({@code FRONT_MATTER} for front matter), from the sidecar lines. */
    private static Map<String, String> sections(List<String> sidecarLines) {
        Map<String, String> sections = new LinkedHashMap<>();
        for (String line : sidecarLines.subList(1, sidecarLines.size())) {
            Map<String, String> fields = JsonStrings.parseFlat(line, BookChunkOutputWriter.SIDECAR_FILE);
            String heading = fields.get("sectionHeading");
            sections.put(fields.get("key"), heading == null || heading.equals("null")
                    ? fields.getOrDefault("sectionKind", "?") : heading);
        }
        return sections;
    }

    private static Identity identity(Properties dataset, BookChunkFiles.Loaded chunks, Map<String, String> header,
                                     byte[] sidecarBytes, Manifest manifest,
                                     BookStoreReadback.Verification readback, String storeDigest) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("corpus", chunks.corpus());
        f.put("corpusVersion", chunks.corpusVersion());
        f.put("corpusManifest.sha256", header.get("manifestSha256"));
        f.put("source.sizeBytes", header.get("sourceBytes"));
        f.put("source.sha256", chunks.sourceSha256());
        f.put("chunking.policy", chunks.policyId());
        f.put("chunking.chapterHeadingPattern", header.get("policy.chapterHeadingPattern"));
        f.put("chunking.maxChunkBytes", header.get("policy.maxChunkBytes"));
        f.put("chunking.overlapBytes", header.get("policy.overlapBytes"));
        f.put("chunks.count", String.valueOf(chunks.entries().size()));
        f.put("chunksFile.sha256", chunks.chunksFileSha256());
        f.put("sidecar.sha256", VerifiedBookSource.sha256(sidecarBytes));
        f.put("dataset", dataset.getProperty("dataset") + " v" + dataset.getProperty("datasetVersion"));
        f.put("queries.sha256", dataset.getProperty("queries.sha256"));
        f.put("judgments.sha256", dataset.getProperty("judgments.sha256"));
        f.put("chunkMap.sha256", dataset.getProperty("chunkMap.sha256"));
        f.put("split.method", dataset.getProperty("split.method"));
        f.put("split.digest", dataset.getProperty("split.digest"));
        f.put("judgment.reviewStatus", dataset.getProperty("judgment.reviewStatus"));
        f.put("store.atoms", String.valueOf(readback.inventory().uniqueAtoms()));
        f.put("store.fingerprint", readback.inventory().fingerprint());
        // Every persisted byte (manifest, atoms, vectors, index, feedback log), so altered or stale vectors cannot
        // produce a different report under the same atom-only fingerprint.
        f.put("store.treeSha256", storeDigest);
        f.put("store.feedbackEvents", "0 (required: feedback-aware ranking must not reorder the baseline)");
        f.put("store.manifestVersion", manifest.version());
        f.put("profile", PROFILE);
        f.put("encodingProfile", manifest.encodingProfile().toString());
        f.put("api", "MonadaMemory.resonate(query).topK(" + MAX_K + ").threshold(-Infinity)");
        f.put("k", KS.toString());
        f.put("runs", "2 per query on fresh instances of one throwaway Store copy; must be identical");
        return new Identity(f);
    }

    private static BookInputException invalid(String message) {
        return new BookInputException(BookInputException.Reason.INVALID_INPUT, message);
    }
}
