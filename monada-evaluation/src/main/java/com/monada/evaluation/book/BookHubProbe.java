package com.monada.evaluation.book;

import com.monada.api.MonadaMemoryOptions;
import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.encoder.WeightedAtomEncoder;
import com.monada.encoder.WeightedAtomTextParts;
import com.monada.encoder.WeightedText;
import com.monada.encoder.WeightedTextParts;
import com.monada.encoder.WeightedToken;
import com.monada.evaluation.book.BookHubConcentration.Distribution;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.RankedHit;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.Manifest;
import com.monada.storage.StoredVector;
import com.monada.storage.audit.StorageIntegrityAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only hub-concentration diagnostic (Issue #176, evaluation only). It never ranks anything itself: the frozen
 * recall result of {@link BookRecallEvaluator} is the ranking truth, and must first reproduce the committed baseline
 * report byte for byte. The probe then reads the stored vectors of a throwaway Store copy, re-scores every query
 * against every chunk with a verbatim copy of the production cosine and requires the recomputed top-K to equal the
 * production top-K exactly (same atoms, same order, bit-identical scores) before it reports anything derived from
 * that recomputation: lengths, normalized token counts, vector shape and an exact per-bucket decomposition of the
 * cosine.
 *
 * <p>The decomposition is per hashed bucket, because that is what the cosine sums. Several tokens may share a bucket,
 * so a bucket's contribution is attributed to tokens only by naming the query tokens that hash there and counting the
 * chunk tokens that do; no per-token percentage is derived. Chunk text and chunk tokens are never reported: only
 * counts, and tokens of the (committed) query text.
 */
final class BookHubProbe {
    /** Recomputed scores must equal production scores exactly; the cosine is a verbatim copy. */
    static final double SCORE_TOLERANCE = 0.0;
    /** Bucket contributions listed per scored pair. */
    static final int TOP_BUCKETS = 3;

    private BookHubProbe() {
    }

    /**
     * Lexical, length and vector diagnostics of one stored atom, under its relevance key.
     *
     * @param topTokenCount       occurrences of the most frequent normalized original token (the token is not named)
     * @param norm                L2 norm of the stored vector
     * @param centroidCosine      cosine (double arithmetic) of the stored vector with the mean of all stored vectors
     * @param reencodeMatches     re-encoding the chunk text with the production encoder gives the stored vector
     */
    record ChunkMetrics(String key, String atomId, int ordinal, int aliases, long utf8Bytes, int codePoints,
                        int originalTokens, int distinctOriginalTokens, int expansionTokens, int topTokenCount,
                        double norm, int nonZeroBuckets, double maxAbsComponent, double centroidCosine,
                        boolean reencodeMatches) {
    }

    /**
     * One bucket's share of a cosine: {@code q[i] * c[i] / (|q| |c|)} exactly as production sums it.
     *
     * @param queryTokens        distinct query tokens hashing to this bucket ({@code +} marks a lexical expansion)
     * @param chunkMatches       occurrences, in the chunk's weighted tokens, of exactly those query tokens
     * @param otherChunkTokens   distinct other chunk tokens hashing to this bucket (collisions; not named)
     */
    record Contribution(int bucket, double value, List<String> queryTokens, int chunkMatches, int otherChunkTokens) {
        Contribution {
            queryTokens = List.copyOf(queryTokens);
        }
    }

    /**
     * One query scored against one atom.
     *
     * @param rank            rank in the full recomputed ranking over all atoms (top-K equals production)
     * @param positive        sum of positive bucket contributions
     * @param negative        sum of negative bucket contributions
     * @param sharedBuckets   buckets non-zero in both vectors
     * @param coveredTokens   distinct normalized original query tokens that also occur among the chunk's
     * @param queryTokens     distinct normalized original query tokens
     * @param residual        |sum of contributions - score|, floating-point summation order only
     */
    record PairScore(String queryId, Split split, boolean answerable, int rank, double score, double positive,
                     double negative, int sharedBuckets, int coveredTokens, int queryTokens, double residual,
                     List<Contribution> top) {
        PairScore {
            top = List.copyOf(top);
        }
    }

    /** One predeclared control: its role, the rule that chose it, and its DEV scores (one per DEV query). */
    record Control(String role, String rule, String key, List<PairScore> dev) {
        Control {
            dev = List.copyOf(dev);
        }
    }

    /** What parity was checked: queries, compared top-K hits, largest score difference, atoms ranked per query. */
    record Parity(int queries, int comparedHits, double maxAbsDifference, int rankedAtoms, int storedVectors) {
    }

    record Result(BookRecallEvaluator.Result recall, String baselineSha256, Distribution distribution,
                  List<ChunkMetrics> metrics, List<Control> controls, Parity parity) {
        Result {
            metrics = List.copyOf(metrics);
            controls = List.copyOf(controls);
        }
    }

    static Result diagnose(Path work, BookQueryDataset dataset, byte[] baseline) throws IOException,
            BookInputException {
        return diagnose(work, dataset, baseline, BookRecallEvaluator.DEFAULT_RETRIEVER, null);
    }

    static Result diagnose(Path work, BookQueryDataset dataset, byte[] baseline,
                           BookRecallEvaluator.RetrieverFactory retriever, Path tmpRoot)
            throws IOException, BookInputException {
        Path store = work.resolve(ExperimentDirectoryGuard.STORE_DIR);
        // Taken before the evaluation: the vectors read below must be the ones the baseline was measured on.
        String digest = BookStoreInventory.treeDigest(store);
        BookRecallEvaluator.Result recall = BookRecallEvaluator.evaluate(work, dataset, retriever, tmpRoot);
        byte[] rendered = BookRecallReport.render(recall).getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(rendered, baseline)) {
            throw new IllegalStateException("pinned input changed: the recall result renders to SHA-256 "
                    + VerifiedBookSource.sha256(rendered) + ", the baseline report is "
                    + VerifiedBookSource.sha256(baseline) + "; the diagnostic only explains the committed baseline");
        }

        Path chunkDir = work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR);
        BookChunkFiles.Loaded chunks = BookChunkFiles.load(chunkDir);
        Map<String, String> evaluated = recall.identity().fields();
        String sidecar = VerifiedBookSource.sha256(Files.readAllBytes(
                chunkDir.resolve(BookChunkOutputWriter.SIDECAR_FILE)));
        if (!chunks.chunksFileSha256().equals(evaluated.get("chunksFile.sha256"))
                || !sidecar.equals(evaluated.get("sidecar.sha256"))) {
            throw new IllegalStateException("inputs changed during the diagnostic: the chunk files are not the ones"
                    + " the baseline was evaluated on");
        }
        Manifest manifest = new StorageIntegrityAuditor().audit(store).manifest();
        List<StoredVector> stored = BookStoreReadback.onCopy(store, tmpRoot, copy -> FileFrequencyStore
                .openExisting(copy, manifest.vectorSegment(), manifest.vectorFormatProfile()).findAll());
        if (!digest.equals(BookStoreInventory.treeDigest(store))) {
            throw new IllegalStateException("inputs changed during the diagnostic: the Store is not the one the"
                    + " baseline was evaluated on");
        }

        // Atom id -> chunk entries with that text, in chunk order; the first is the relevance key.
        Map<String, List<BookChunkFiles.Entry>> byAtom = new LinkedHashMap<>();
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            byAtom.computeIfAbsent(KnowledgeAtom.text(entry.text()).id(), id -> new ArrayList<>()).add(entry);
        }
        Map<String, FrequencyVector> vectors = new LinkedHashMap<>();
        for (StoredVector v : stored) {
            if (!byAtom.containsKey(v.atomId())) {
                throw new IllegalStateException("stored vector for " + v.atomId() + " is not a chunk of this corpus");
            }
            if (vectors.put(v.atomId(), v.vector()) != null) {
                throw new IllegalStateException("not measured: atom " + v.atomId() + " has more than one stored"
                        + " vector; the per-atom decomposition assumes one");
            }
        }
        if (!vectors.keySet().equals(byAtom.keySet())) {
            throw new IllegalStateException("the Store has " + vectors.size() + " vectors for " + byAtom.size()
                    + " chunk atoms");
        }

        MonadaMemoryOptions options = MonadaMemoryOptions.defaults();
        Encoding encoding = new Encoding(new SimpleFrequencyEncoder(manifest.dimensions()), options.textNormalizer(),
                options.expansionOptions());
        Map<String, AtomTokens> atoms = new LinkedHashMap<>();
        byAtom.forEach((id, group) -> atoms.put(id, encoding.atom(group.getFirst().text())));

        List<CaseResult> cases = recall.cases();
        Map<String, List<Ranked>> rankings = new LinkedHashMap<>();
        Map<String, QueryTokens> queries = new LinkedHashMap<>();
        int compared = 0;
        double maxDifference = 0.0;
        for (CaseResult c : cases) {
            QueryTokens q = encoding.query(c.query().text());
            queries.put(c.query().id(), q);
            List<Ranked> ranking = rank(q, vectors);
            rankings.put(c.query().id(), ranking);
            List<RankedHit> production = c.hits();
            int expected = q.blank() ? 0 : Math.min(BookRecallEvaluator.MAX_K, ranking.size());
            if (production.size() != expected) {
                throw parity(c, "production returned " + production.size() + " hits, recomputation " + expected);
            }
            for (int i = 0; i < production.size(); i++) {
                RankedHit hit = production.get(i);
                Ranked mine = ranking.get(i);
                double difference = Math.abs(hit.score() - mine.score());
                if (!hit.atomId().equals(mine.atomId()) || difference > SCORE_TOLERANCE) {
                    throw parity(c, "rank " + (i + 1) + " production " + hit.atomId() + " " + hit.score()
                            + ", recomputation " + mine.atomId() + " " + mine.score());
                }
                maxDifference = Math.max(maxDifference, difference);
                compared++;
            }
        }
        Parity parity = new Parity(cases.size(), compared, maxDifference, vectors.size(), stored.size());

        double[] centroid = centroid(vectors.values(), manifest.dimensions());
        Distribution distribution = BookHubConcentration.of(cases);
        List<ChunkMetrics> metrics = new ArrayList<>();
        byAtom.forEach((id, group) -> metrics.add(metrics(id, group, atoms.get(id), vectors.get(id), centroid)));

        Map<String, String> relevanceKey = new HashMap<>();
        Map<String, String> atomOfKey = new HashMap<>();
        byAtom.forEach((id, group) -> group.forEach(e -> {
            relevanceKey.put(e.key(), group.getFirst().key());
            atomOfKey.put(e.key(), id);
        }));
        List<Control> controls = new ArrayList<>();
        for (Selected s : controls(distribution, cases, relevanceKey)) {
            String atomId = atomOfKey.get(s.key());
            List<PairScore> dev = new ArrayList<>();
            for (CaseResult c : cases) {
                if (atomId != null && c.query().split() == Split.DEV) {
                    dev.add(pair(c.query(), queries.get(c.query().id()), rankings.get(c.query().id()), atomId,
                            vectors.get(atomId), atoms.get(atomId)));
                }
            }
            controls.add(new Control(s.role(), s.rule(), s.key(), dev));
        }
        return new Result(recall, VerifiedBookSource.sha256(baseline), distribution, metrics, controls, parity);
    }

    private static IllegalStateException parity(CaseResult c, String detail) {
        return new IllegalStateException(c.query().id() + ": score parity failed, the recomputation does not"
                + " reproduce the production ranking (" + detail + ")");
    }

    // ---------------------------------------------------------------- controls

    /** The rules are fixed before any score is read and use DEV queries only. */
    record Rule(String role, String text) {
    }

    static final List<Rule> CONTROL_RULES = List.of(
            new Rule("HUB", "most DEV top-1 appearances, at least one (ties: DEV top-3, DEV top-10 descending, key"
                    + " ascending), else absent"),
            new Rule("RUNNER_UP", "next chunk in the same DEV order"),
            new Rule("TOP1_EVIDENCE", "judged evidence ranked #1 for a DEV query (first by query id), else absent"),
            new Rule("JUDGED_EVIDENCE", "grade-2 evidence judged for the most DEV queries (ties: key ascending)"));

    record Selected(String role, String rule, String key) {
    }

    static List<Selected> controls(Distribution distribution, List<CaseResult> cases,
                                   Map<String, String> relevanceKey) {
        List<String> devOrder = distribution.chunks().stream().filter(c -> c.count("DEV", 10) > 0)
                .sorted(BookHubConcentration.order("DEV")).map(BookHubConcentration.Chunk::key).toList();
        List<String> topEvidence = new ArrayList<>();
        Map<String, Integer> judged = new TreeMap<>();
        for (CaseResult c : cases.stream().sorted(Comparator.comparing(r -> r.query().id())).toList()) {
            if (c.query().split() != Split.DEV) {
                continue;
            }
            if (!c.hits().isEmpty() && c.hits().getFirst().label().startsWith("E")) {
                topEvidence.add(c.hits().getFirst().key());
            }
            Set<String> direct = new LinkedHashSet<>();
            for (Judgment j : c.query().evidence()) {
                if (j.grade() == 2) {
                    direct.add(relevanceKey.getOrDefault(j.chunkKey(), j.chunkKey()));
                }
            }
            direct.forEach(k -> judged.merge(k, 1, Integer::sum));
        }
        List<String> judgedOrder = judged.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();

        Set<String> taken = new LinkedHashSet<>();
        List<Selected> selected = new ArrayList<>();
        pick(selected, taken, CONTROL_RULES.get(0), devOrder.stream()
                .filter(k -> distribution.chunk(k).count("DEV", 1) > 0).limit(1).toList());
        pick(selected, taken, CONTROL_RULES.get(1), devOrder);
        pick(selected, taken, CONTROL_RULES.get(2), topEvidence);
        pick(selected, taken, CONTROL_RULES.get(3), judgedOrder);
        return selected;
    }

    private static void pick(List<Selected> into, Set<String> taken, Rule rule, List<String> candidates) {
        for (String key : candidates) {
            if (taken.add(key)) {
                into.add(new Selected(rule.role(), rule.text(), key));
                return;
            }
        }
        into.add(new Selected(rule.role(), rule.text(), null));
    }

    // ---------------------------------------------------------------- scoring

    record Ranked(String atomId, double score) {
    }

    /** Every atom scored, ordered as production orders: score descending, then atom id ascending. */
    private static List<Ranked> rank(QueryTokens q, Map<String, FrequencyVector> vectors) {
        if (q.blank()) {
            return List.of();
        }
        List<Ranked> ranking = new ArrayList<>();
        vectors.forEach((id, v) -> ranking.add(new Ranked(id, cosineSimilarity(q.vector(), v))));
        ranking.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::atomId));
        return ranking;
    }

    private static PairScore pair(Case query, QueryTokens q, List<Ranked> ranking, String atomId,
                                  FrequencyVector vector, AtomTokens atom) {
        int rank = 0;
        double score = 0.0;
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).atomId().equals(atomId)) {
                rank = i + 1;
                score = ranking.get(i).score();
            }
        }
        float[] left = q.vector().values();
        float[] right = vector.values();
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < left.length; i++) {
            leftMagnitude += left[i] * left[i];
            rightMagnitude += right[i] * right[i];
        }
        double denominator = Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude);
        List<Contribution> contributions = new ArrayList<>();
        double positive = 0.0;
        double negative = 0.0;
        double sum = 0.0;
        int shared = 0;
        for (int i = 0; i < left.length; i++) {
            if (left[i] == 0.0f || right[i] == 0.0f || denominator == 0.0) {
                continue;
            }
            shared++;
            double value = (left[i] * right[i]) / denominator;
            sum += value;
            if (value > 0) {
                positive += value;
            } else {
                negative += value;
            }
            List<String> tokens = q.byBucket().getOrDefault(i, List.of());
            int matches = 0;
            for (String token : tokens) {
                matches += atom.counts().getOrDefault(token.startsWith("+") ? token.substring(1) : token, 0);
            }
            Set<String> others = new LinkedHashSet<>(atom.byBucket().getOrDefault(i, List.of()));
            tokens.forEach(t -> others.remove(t.startsWith("+") ? t.substring(1) : t));
            contributions.add(new Contribution(i, value, tokens, matches, others.size()));
        }
        contributions.sort(Comparator.comparingDouble(Contribution::value).reversed()
                .thenComparingInt(Contribution::bucket));
        Set<String> covered = new LinkedHashSet<>(q.distinctOriginal());
        covered.retainAll(atom.distinctOriginal());
        return new PairScore(query.id(), query.split(), query.answerable(), rank, score, positive, negative, shared,
                covered.size(), q.distinctOriginal().size(), rank == 0 ? 0.0 : Math.abs(sum - score),
                contributions.subList(0, Math.min(TOP_BUCKETS, contributions.size())));
    }

    /** Verbatim copy of the production cosine ({@code LinearScanResonanceIndex}) so doubles are bit-identical. */
    static double cosineSimilarity(FrequencyVector left, FrequencyVector right) {
        var leftValues = left.values();
        var rightValues = right.values();
        if (leftValues.length != rightValues.length) {
            throw new IllegalArgumentException("Vectors must have the same dimensions");
        }

        var dot = 0.0;
        var leftMagnitude = 0.0;
        var rightMagnitude = 0.0;
        for (var i = 0; i < leftValues.length; i++) {
            dot += leftValues[i] * rightValues[i];
            leftMagnitude += leftValues[i] * leftValues[i];
            rightMagnitude += rightValues[i] * rightValues[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    // ---------------------------------------------------------------- metrics

    private static ChunkMetrics metrics(String atomId, List<BookChunkFiles.Entry> group, AtomTokens atom,
                                        FrequencyVector vector, double[] centroid) {
        BookChunkFiles.Entry first = group.getFirst();
        float[] values = vector.values();
        double norm = 0.0;
        double maxAbs = 0.0;
        double dot = 0.0;
        double centroidNorm = 0.0;
        int nonZero = 0;
        for (int i = 0; i < values.length; i++) {
            norm += (double) values[i] * values[i];
            maxAbs = Math.max(maxAbs, Math.abs(values[i]));
            nonZero += values[i] != 0.0f ? 1 : 0;
            dot += values[i] * centroid[i];
            centroidNorm += centroid[i] * centroid[i];
        }
        double centroidCosine = norm == 0.0 || centroidNorm == 0.0 ? 0.0
                : dot / (Math.sqrt(norm) * Math.sqrt(centroidNorm));
        int top = atom.originalCounts().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new ChunkMetrics(first.key(), atomId, first.ordinal(), group.size() - 1,
                first.text().getBytes(StandardCharsets.UTF_8).length,
                first.text().codePointCount(0, first.text().length()), atom.originalTokens(),
                atom.distinctOriginal().size(), atom.expansionTokens(), top, Math.sqrt(norm), nonZero, maxAbs,
                centroidCosine, Arrays.equals(atom.vector().values(), values));
    }

    private static double[] centroid(Iterable<FrequencyVector> vectors, int dimensions) {
        double[] sum = new double[dimensions];
        int n = 0;
        for (FrequencyVector v : vectors) {
            float[] values = v.values();
            for (int i = 0; i < dimensions; i++) {
                sum[i] += values[i];
            }
            n++;
        }
        for (int i = 0; i < dimensions && n > 0; i++) {
            sum[i] /= n;
        }
        return sum;
    }

    // ---------------------------------------------------------------- tokens

    /** A query as production encodes it, with the bucket of every distinct weighted token. */
    record QueryTokens(boolean blank, FrequencyVector vector, Set<String> distinctOriginal,
                       Map<Integer, List<String>> byBucket) {
    }

    /** A chunk as production encodes it: token counts and the distinct tokens of every bucket. */
    record AtomTokens(FrequencyVector vector, int originalTokens, int expansionTokens, Map<String, Integer> counts,
                      Map<String, Integer> originalCounts, Set<String> distinctOriginal,
                      Map<Integer, List<String>> byBucket) {
    }

    /** Production encoding, plus each token's bucket derived through the public encoder (one token, weight 1). */
    private record Encoding(SimpleFrequencyEncoder encoder, TextNormalizer normalizer,
                            LexicalExpansionOptions options, Map<String, Integer> buckets) {
        Encoding(SimpleFrequencyEncoder encoder, TextNormalizer normalizer, LexicalExpansionOptions options) {
            this(encoder, normalizer, options, new HashMap<>());
        }

        QueryTokens query(String text) {
            var normalized = normalizer.normalize(text);
            WeightedTextParts parts = normalized.toWeightedTextParts(options);
            FrequencyVector vector = encoder.encode(normalized.toWeightedText(options));
            Set<String> original = new LinkedHashSet<>();
            parts.originalTokens().forEach(t -> original.add(t.token()));
            Map<Integer, List<String>> byBucket = new TreeMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (WeightedToken t : parts.originalTokens()) {
                if (seen.add(t.token())) {
                    byBucket.computeIfAbsent(bucket(t.token()), b -> new ArrayList<>()).add(t.token());
                }
            }
            for (WeightedToken t : parts.expansionTokens()) {
                if (seen.add(t.token())) {
                    byBucket.computeIfAbsent(bucket(t.token()), b -> new ArrayList<>()).add("+" + t.token());
                }
            }
            return new QueryTokens(normalized.enrichedText().isBlank(), vector, original, byBucket);
        }

        AtomTokens atom(String text) {
            WeightedAtomTextParts parts = WeightedAtomEncoder.toWeightedTextParts(KnowledgeAtom.text(text),
                    normalizer, options);
            FrequencyVector vector = encoder.encode(parts.toWeightedText());
            Map<String, Integer> counts = new HashMap<>();
            Map<String, Integer> originalCounts = new HashMap<>();
            parts.originalTokens().forEach(t -> originalCounts.merge(t.token(), 1, Integer::sum));
            parts.toWeightedText().tokens().forEach(t -> counts.merge(t.token(), 1, Integer::sum));
            Map<Integer, List<String>> byBucket = new HashMap<>();
            for (String token : new TreeSet<>(counts.keySet())) {
                byBucket.computeIfAbsent(bucket(token), b -> new ArrayList<>()).add(token);
            }
            int expansions = parts.expansionTokens().size() + parts.aliasTokens().size()
                    + parts.aliasExpansionTokens().size();
            return new AtomTokens(vector, parts.originalTokens().size(), expansions, counts, originalCounts,
                    originalCounts.keySet(), byBucket);
        }

        /** The bucket production adds {@code token} to: the only non-zero component of its one-token encoding. */
        int bucket(String token) {
            return buckets.computeIfAbsent(token, t -> {
                float[] values = encoder.encode(new WeightedText(List.of(new WeightedToken(t, 1.0)))).values();
                for (int i = 0; i < values.length; i++) {
                    if (values[i] != 0.0f) {
                        return i;
                    }
                }
                throw new IllegalStateException("token encodes to a zero vector");
            });
        }
    }
}
