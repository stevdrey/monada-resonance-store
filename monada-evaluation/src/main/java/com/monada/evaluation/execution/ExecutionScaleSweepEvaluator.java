package com.monada.evaluation.execution;

import com.monada.api.execution.AttemptView;
import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceRecall;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.HistoryEntry;
import com.monada.api.execution.ProjectionStatus;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.ScaleTimingStatistics;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Experience;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Query;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.QueryKind;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScaleCorpus;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScopeCorpus;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.Disk;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.ScopeStructure;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.SizeResult;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.SizeTiming;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.ProjectionCheckpoint;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Measures scoped execution recall as the corpus grows (issue #125), through public {@link ExecutionMemory} APIs
 * only. For every size a fresh store records the generated corpus of every scope, rebuilds each projection twice,
 * reopens it writable, opens it read-only and recalls every query at every K.
 *
 * <p>Two kinds of output are kept apart. The hard checks (isolation, exact refs, deterministic tie order, append /
 * rebuild / reopen / read-only parity, structural accounting) are deterministic and decide the exit code. Phase
 * timings, recall latency percentiles, heap and on-disk sizes are informational only and never gate anything.
 * Nothing is tuned: no encoder, projection, index, limit or default is changed or configured.
 */
public final class ExecutionScaleSweepEvaluator {
    static final List<Integer> DEFAULT_SIZES = List.of(25, 250, 1000);
    static final List<Integer> DEFAULT_KS = List.of(1, 5, 20);
    static final int DEFAULT_WARMUP = 2;
    static final int DEFAULT_ITERATIONS = 5;

    /** What one sweep measures; every field is printed in the deterministic report. */
    public record Config(long seed, List<Integer> sizes, List<Integer> ks, int warmup, int iterations) {
        public Config {
            sizes = List.copyOf(sizes);
            ks = List.copyOf(ks);
            if (sizes.isEmpty()) {
                throw new IllegalArgumentException("at least one size is required");
            }
            if (new HashSet<>(sizes).size() != sizes.size()) {
                throw new IllegalArgumentException("sizes must be distinct, got: " + sizes);
            }
            for (int n : sizes) {
                if (n < ExecutionScaleCorpusGenerator.MIN_EXPERIENCES_PER_SCOPE) {
                    throw new IllegalArgumentException("every size must be >= "
                            + ExecutionScaleCorpusGenerator.MIN_EXPERIENCES_PER_SCOPE + ", got: " + n);
                }
            }
            if (ks.isEmpty() || new HashSet<>(ks).size() != ks.size()) {
                throw new IllegalArgumentException("ks must be non-empty and distinct, got: " + ks);
            }
            for (int k : ks) {
                if (k < 1 || k > ExperienceRecall.MAX_LIMIT) {
                    throw new IllegalArgumentException("every K must be in 1.." + ExperienceRecall.MAX_LIMIT
                            + ", got: " + k);
                }
            }
            if (warmup < 0) {
                throw new IllegalArgumentException("warmup must be >= 0, got: " + warmup);
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations must be >= 1, got: " + iterations);
            }
            sizes = sizes.stream().sorted().toList();
            ks = ks.stream().sorted().toList();
        }

        public static Config defaults() {
            return new Config(ExecutionScaleCorpusGenerator.DEFAULT_SEED, DEFAULT_SIZES, DEFAULT_KS, DEFAULT_WARMUP,
                    DEFAULT_ITERATIONS);
        }
    }

    /** Every ranking of one phase: key {@code <queryId>@<K>}, value the hits in rank order. */
    record Rankings(Map<String, List<ExperienceHit>> hits) {
        String fingerprint() {
            StringBuilder out = new StringBuilder();
            hits.forEach((key, list) -> {
                out.append(key);
                list.forEach(h -> out.append(' ').append(ExperienceRefCodec.canonical(h.ref())).append('#')
                        .append(h.atomId()).append(':').append(h.similarity()).append("@c")
                        .append(h.coveredSequence()).append('v').append(h.projectionVersion()));
                out.append('\n');
            });
            return out.toString();
        }
    }

    private ExecutionScaleSweepEvaluator() {
    }

    /** Runs the sweep with stores below {@code workDir}; the caller owns {@code workDir} and removes it. */
    public static ExecutionScaleSweepReport evaluate(Config config, Path workDir) {
        Objects.requireNonNull(config, "config");
        List<SizeResult> results = new ArrayList<>();
        List<SizeTiming> timings = new ArrayList<>();
        Violations v = new Violations();
        for (int n : config.sizes()) {
            ScaleCorpus corpus = ExecutionScaleCorpusGenerator.generate(config.seed(), n);
            Path root = RecallStressEvaluator.fresh(workDir, "scale-n" + n + "-");
            SizeRun run = new SizeRun(config, corpus, root, v);
            run.execute();
            results.add(run.result());
            timings.add(run.timing());
        }
        List<ForgeGate> gates = List.of(
                ForgeGate.of(ExecutionScaleSweepReport.SCOPE_ISOLATION,
                        "zero foreign-scope hits; foreign scopes rejected by recall, load and export",
                        v.isolation, List.of("hits checked: " + v.hitsChecked + "; cross-scope probes rejected: "
                                + v.probes)),
                ForgeGate.of(ExecutionScaleSweepReport.EXACT_REFERENCES,
                        "every hit is an expected latest-revision finish that loads exactly from the ledger; never "
                                + "more than K hits",
                        v.references, List.of("hits checked: " + v.hitsChecked + "; distinct refs resolved against "
                                + "the ledger: " + v.refsResolved)),
                ForgeGate.of(ExecutionScaleSweepReport.DETERMINISTIC_TIES,
                        "hits ordered by similarity, atom id, canonical ref; an atom's refs contiguous and complete",
                        v.ties, List.of("rankings checked: " + v.rankingsChecked)),
                ForgeGate.of(ExecutionScaleSweepReport.PARITY,
                        "append (same instance), rebuild, second rebuild, reopen, read-only and repeated recalls rank "
                                + "identically from projections covering the whole ledger",
                        v.parity, List.of("phase comparisons: " + v.parityComparisons
                                + "; repeated timed recalls compared: " + v.repeatedRecalls)),
                ForgeGate.of(ExecutionScaleSweepReport.STRUCTURAL_ACCOUNTING,
                        "ledger events, refs, live and retired atoms and dimensions match the generated corpus",
                        v.structure, List.of("scopes checked: " + v.scopesChecked)));
        return new ExecutionScaleSweepReport(config, results, gates, timings, ExecutionScaleSweepReport.currentEnvironment());
    }

    /** Violations collected across every size, one list per hard check. */
    private static final class Violations {
        final List<String> isolation = new ArrayList<>();
        final List<String> references = new ArrayList<>();
        final List<String> ties = new ArrayList<>();
        final List<String> parity = new ArrayList<>();
        final List<String> structure = new ArrayList<>();
        int hitsChecked;
        int refsResolved;
        int probes;
        int rankingsChecked;
        int parityComparisons;
        int repeatedRecalls;
        int scopesChecked;
    }

    /** One size of the sweep in its own root. */
    private static final class SizeRun {
        private final Config config;
        private final ScaleCorpus corpus;
        private final Path root;
        private final Violations v;
        private final String label;
        private final Map<String, Long> phases = new LinkedHashMap<>();
        private final Map<String, List<String>> generations = new LinkedHashMap<>();
        /** Retired and live atom ids of the published generation after each observed phase, per scope. */
        private final Map<String, List<SortedSet<String>>> retiredByPhase = new LinkedHashMap<>();
        private final Map<String, List<Set<String>>> liveByPhase = new LinkedHashMap<>();
        private final Map<Integer, ScaleTimingStatistics> latency = new TreeMap<>();
        private final Map<String, Disk> disk = new LinkedHashMap<>();
        private final List<ScopeStructure> structures = new ArrayList<>();
        private int selfRecallTargets;
        private int selfRecallAtOne;
        private long heapDelta;
        private int readOnlySnapshots;

        SizeRun(Config config, ScaleCorpus corpus, Path root, Violations v) {
            this.config = config;
            this.corpus = corpus;
            this.root = root;
            this.v = v;
            this.label = "N=" + corpus.experiencesPerScope();
        }

        void execute() {
            // The rankings after append come from the very instance that appended: its incrementally maintained
            // projection is what is compared, not one a later open would reconcile.
            Map<String, Rankings> appended = new LinkedHashMap<>();
            long append = 0;
            for (ScopeCorpus scope : corpus.scopes()) {
                long t = System.nanoTime();
                try (ExecutionMemory memory = ExecutionMemory.open(root, scope.scope())) {
                    List<RecordResult> recorded = ForgeFixtureRecorder.record(memory, scope.events());
                    append += System.nanoTime() - t;
                    for (int i = 0; i < recorded.size(); i++) {
                        if (recorded.get(i).status() != RecordResult.Status.APPENDED) {
                            v.structure.add(label + " " + scope.scope().value() + ": event " + i + " was "
                                    + recorded.get(i).status() + " " + recorded.get(i).reason());
                        }
                    }
                    status(scope, "after append", memory.projectionStatus(scope.scope()));
                    appended.put(key(scope), rankings(memory, scope, "after append"));
                }
                observe(scope, "append");
            }
            phases.put("append", append);

            Map<String, Rankings> rebuilt = new LinkedHashMap<>();
            Map<String, Rankings> rebuiltAgain = new LinkedHashMap<>();
            long rebuild = 0;
            long rebuildAgain = 0;
            for (ScopeCorpus scope : corpus.scopes()) {
                try (ExecutionMemory memory = ExecutionMemory.open(root, scope.scope())) {
                    long t = System.nanoTime();
                    ProjectionStatus first = memory.rebuildProjection(scope.scope());
                    rebuild += System.nanoTime() - t;
                    status(scope, "after rebuild", first);
                    observe(scope, "rebuild");
                    rebuilt.put(key(scope), rankings(memory, scope, "after rebuild"));
                    t = System.nanoTime();
                    ProjectionStatus second = memory.rebuildProjection(scope.scope());
                    rebuildAgain += System.nanoTime() - t;
                    status(scope, "after second rebuild", second);
                    observe(scope, "second rebuild");
                    rebuiltAgain.put(key(scope), rankings(memory, scope, "after second rebuild"));
                }
            }
            phases.put("rebuild", rebuild);
            phases.put("second rebuild", rebuildAgain);

            Map<String, Rankings> reopened = new LinkedHashMap<>();
            long reopen = 0;
            for (ScopeCorpus scope : corpus.scopes()) {
                long t = System.nanoTime();
                try (ExecutionMemory memory = ExecutionMemory.open(root, scope.scope())) {
                    ProjectionStatus status = memory.projectionStatus(scope.scope());
                    reopen += System.nanoTime() - t;
                    status(scope, "after writable reopen", status);
                    if (memory.history(scope.scope()).highWatermark() != scope.events().size()) {
                        v.parity.add(label + " " + key(scope) + ": reopened history high watermark "
                                + memory.history(scope.scope()).highWatermark() + ", recorded "
                                + scope.events().size());
                    }
                    reopened.put(key(scope), rankings(memory, scope, "after writable reopen"));
                }
                observe(scope, "reopen");
            }
            phases.put("writable reopen", reopen);

            Map<String, ExecutionMemory> readOnly = new LinkedHashMap<>();
            try {
                long before = usedHeap();
                long t = System.nanoTime();
                for (ScopeCorpus scope : corpus.scopes()) {
                    readOnly.put(key(scope), ExecutionMemory.openReadOnly(root, scope.scope()));
                }
                phases.put("read-only open", System.nanoTime() - t);
                heapDelta = usedHeap() - before;
                readOnlySnapshots = readOnly.size();
                for (ScopeCorpus scope : corpus.scopes()) {
                    ExecutionMemory memory = readOnly.get(key(scope));
                    status(scope, "read-only", memory.projectionStatus(scope.scope()));
                    Rankings frozen = rankings(memory, scope, "read-only");
                    String reference = frozen.fingerprint();
                    // read once: the read-only generation's checkpoint serves the hit and structure checks
                    Optional<ProjectionCheckpoint.Snapshot> snapshot = checkpoint(scope, "read-only");
                    compare(scope, "append", appended.get(key(scope)), reference);
                    compare(scope, "rebuild", rebuilt.get(key(scope)), reference);
                    compare(scope, "second rebuild", rebuiltAgain.get(key(scope)), reference);
                    compare(scope, "writable reopen", reopened.get(key(scope)), reference);
                    checkHits(scope, memory, frozen, snapshot);
                    probeForeignScopes(scope, memory);
                    structure(scope, snapshot);
                    selfRecall(scope, frozen);
                }
                measureLatency(readOnly);
            } finally {
                readOnly.values().forEach(ExecutionMemory::close);
            }
            for (ScopeCorpus scope : corpus.scopes()) {
                disk.put(key(scope), disk(scope));
            }
        }

        // --- rankings and parity --------------------------------------------------------------------------

        /**
         * Recalls every query at every K. Each hit must come from a projection that covers the whole recorded ledger
         * and carry the projection version the recall reports: a hit of a stale projection is a parity violation.
         */
        private Rankings rankings(ExecutionMemory memory, ScopeCorpus scope, String phase) {
            Map<String, List<ExperienceHit>> hits = new LinkedHashMap<>();
            for (Query q : scope.queries()) {
                for (int k : config.ks()) {
                    ExperienceRecall recall = memory.recall(scope.scope(), q.text(), k);
                    for (ExperienceHit h : recall.hits()) {
                        if (h.coveredSequence() != scope.events().size()
                                || h.projectionVersion() != recall.status().projectionVersion()) {
                            v.parity.add(label + " " + q.id() + "@" + k + " " + phase + ": hit "
                                    + ExperienceRefCodec.canonical(h.ref()) + " covers " + h.coveredSequence() + " of "
                                    + scope.events().size() + " events at projection version "
                                    + h.projectionVersion() + ", recall reports " + recall.status().projectionVersion());
                        }
                    }
                    hits.put(q.id() + "@" + k, recall.hits());
                }
            }
            return new Rankings(hits);
        }

        private void compare(ScopeCorpus scope, String phase, Rankings rankings, String reference) {
            v.parityComparisons++;
            if (!rankings.fingerprint().equals(reference)) {
                v.parity.add(label + " " + key(scope) + ": rankings after " + phase
                        + " differ from the read-only snapshot");
            }
        }

        private void status(ScopeCorpus scope, String phase, ProjectionStatus status) {
            v.parity.addAll(ForgeExperienceEvaluator.projectionViolations(label + " " + key(scope) + " " + phase,
                    status));
            if (status.ledgerSequence() != scope.events().size()) {
                v.structure.add(label + " " + key(scope) + " " + phase + ": ledger sequence "
                        + status.ledgerSequence() + ", recorded " + scope.events().size());
            }
        }

        /**
         * Times every query of every scope at each K on the read-only snapshots. Every round visits all K arms, in an
         * order rotated by one each round so no K always runs warmer or later than another; the first
         * {@code warmup} rounds are untimed, the next {@code iterations} are timed, and every call must return
         * exactly the snapshot's ranking.
         */
        private void measureLatency(Map<String, ExecutionMemory> readOnly) {
            int queries = corpus.scopes().stream().mapToInt(s -> s.queries().size()).sum();
            List<Integer> ks = config.ks();
            Map<String, List<ExperienceHit>> expected = new LinkedHashMap<>();
            Map<Integer, List<Long>> samples = new TreeMap<>();
            for (int k : ks) {
                samples.put(k, new ArrayList<>());
                for (ScopeCorpus scope : corpus.scopes()) {
                    for (Query q : scope.queries()) {
                        expected.put(q.id() + "@" + k, readOnly.get(key(scope)).recall(scope.scope(), q.text(), k)
                                .hits());
                    }
                }
            }
            int rounds = config.warmup() + config.iterations();
            for (int round = 0; round < rounds; round++) {
                boolean timed = round >= config.warmup();
                for (int j = 0; j < ks.size(); j++) {
                    int k = ks.get((j + round) % ks.size());
                    for (ScopeCorpus scope : corpus.scopes()) {
                        ExecutionMemory memory = readOnly.get(key(scope));
                        for (Query q : scope.queries()) {
                            long t = System.nanoTime();
                            ExperienceRecall recall = memory.recall(scope.scope(), q.text(), k);
                            long elapsed = System.nanoTime() - t;
                            if (!timed) {
                                continue;
                            }
                            samples.get(k).add(elapsed);
                            v.repeatedRecalls++;
                            if (!recall.hits().equals(expected.get(q.id() + "@" + k))) {
                                v.parity.add(label + " " + q.id() + "@" + k + ": repeated recall in round " + round
                                        + " differs");
                            }
                        }
                    }
                }
            }
            samples.forEach((k, list) -> latency.put(k, ScaleTimingStatistics.from(list, List.of(), queries,
                    config.iterations())));
        }

        // --- isolation, references and ties -------------------------------------------------------------------

        private void checkHits(ScopeCorpus scope, ExecutionMemory memory, Rankings rankings,
                               Optional<ProjectionCheckpoint.Snapshot> snapshot) {
            Map<String, Experience> expected = scope.experiences().stream().collect(Collectors.toMap(
                    e -> ExperienceRefCodec.canonical(e.ref()), Function.identity()));
            Map<String, ExperienceHit> resolved = new LinkedHashMap<>();
            rankings.hits().forEach((key, hits) -> {
                int k = Integer.parseInt(key.substring(key.lastIndexOf('@') + 1));
                String where = label + " " + key;
                v.rankingsChecked++;
                if (hits.size() > k) {
                    v.references.add(where + ": " + hits.size() + " hits for K=" + k);
                }
                for (ExperienceHit h : hits) {
                    v.hitsChecked++;
                    if (!h.ref().scope().equals(scope.scope())) {
                        v.isolation.add(where + ": foreign-scope hit " + h.ref().scope().value());
                    }
                    String canonical = ExperienceRefCodec.canonical(h.ref());
                    if (!expected.containsKey(canonical)) {
                        v.references.add(where + ": hit " + canonical + " is not a latest-revision finish of "
                                + key(scope));
                    }
                    resolved.putIfAbsent(canonical, h);
                }
                v.ties.addAll(orderViolations(where, hits));
                snapshot.ifPresent(cp -> v.ties.addAll(atomViolations(where, hits, k, cp)));
            });
            resolved.forEach((canonical, h) -> {
                Experience e = expected.get(canonical);
                if (e != null) {
                    v.refsResolved++;
                    v.references.addAll(referenceViolations(label + " " + key(scope), memory, scope.scope(), h, e));
                }
            });
        }

        private void probeForeignScopes(ScopeCorpus scope, ExecutionMemory memory) {
            for (ScopeCorpus other : corpus.scopes()) {
                if (other == scope) {
                    continue;
                }
                ScopeId foreign = other.scope();
                String what = label + " " + key(scope) + " memory, ";
                v.probes += 3;
                v.isolation.addAll(ForgeExperienceEvaluator.mustReject(what + "recall of " + key(other),
                        () -> memory.recall(foreign, other.queries().get(0).text(), 5)));
                v.isolation.addAll(ForgeExperienceEvaluator.mustReject(what + "load of " + key(other),
                        () -> memory.loadExecution(foreign, other.experiences().get(0).ref().execution())));
                v.isolation.addAll(ForgeExperienceEvaluator.mustReject(what + "export of " + key(other),
                        () -> memory.exportSamples(ExportRequest.firstPage(foreign, 5))));
            }
        }

        private void selfRecall(ScopeCorpus scope, Rankings rankings) {
            int k = config.ks().get(config.ks().size() - 1);
            Map<String, Experience> byId = scope.experiences().stream().collect(Collectors.toMap(
                    Experience::experienceId, Function.identity()));
            for (Query q : scope.queries()) {
                if (q.target().isEmpty()) {
                    continue;
                }
                selfRecallTargets++;
                List<ExperienceHit> hits = rankings.hits().get(q.id() + "@" + k);
                String target = byId.get(q.target().get()).text();
                // the top atom is the exact text: rank 1 holds the target or one of its identical-text twins
                if (!hits.isEmpty() && scope.experiences().stream().anyMatch(e -> e.text().equals(target)
                        && e.ref().equals(hits.get(0).ref()))) {
                    selfRecallAtOne++;
                }
            }
        }

        // --- structure -----------------------------------------------------------------------------------------

        private void structure(ScopeCorpus scope, Optional<ProjectionCheckpoint.Snapshot> published) {
            v.scopesChecked++;
            String where = label + " " + key(scope);
            if (published.isEmpty()) {
                return; // checkpoint(...) already recorded why the read-only generation could not be read
            }
            ProjectionCheckpoint.Snapshot snapshot = published.get();
            if (!snapshot.isSound() || !snapshot.cleanTail()) {
                v.structure.add(where + ": checkpoint is not sound " + snapshot.problems());
            }
            if (snapshot.dimensions() != ProjectionCheckpoint.PROJECTION_DIMENSIONS) {
                v.structure.add(where + ": dimensions " + snapshot.dimensions());
            }
            Set<String> expectedRefs = scope.experiences().stream().map(e -> ExperienceRefCodec.canonical(e.ref()))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
            Set<String> projectedRefs = new java.util.TreeSet<>();
            int refCount = 0;
            for (List<ExperienceRef> refs : snapshot.refsByAtom().values()) {
                refCount += refs.size();
                refs.forEach(r -> projectedRefs.add(ExperienceRefCodec.canonical(r)));
            }
            if (refCount != scope.activeAttempts() || !projectedRefs.equals(expectedRefs)) {
                v.structure.add(where + ": projected " + refCount + " refs, expected " + scope.activeAttempts()
                        + " (missing " + difference(expectedRefs, projectedRefs) + ", unexpected "
                        + difference(projectedRefs, expectedRefs) + ")");
            }
            if (snapshot.refsByAtom().size() != scope.liveTexts().size()) {
                v.structure.add(where + ": " + snapshot.refsByAtom().size() + " live atoms, expected "
                        + scope.liveTexts().size() + " distinct texts");
            }
            if (!partitionByAtom(snapshot).equals(partitionByText(scope))) {
                v.structure.add(where + ": refs are not grouped into atoms by identical text");
            }
            // Every phase's published generation must retire exactly the corrected texts, by identity: the same
            // retired atom ids as the read-only generation, none of them live in that phase.
            if (snapshot.retiredAtoms().size() != scope.retiredTexts().size()) {
                v.structure.add(where + ": " + snapshot.retiredAtoms().size() + " retired atoms in the read-only "
                        + "generation, expected " + scope.retiredTexts().size() + " corrected texts");
            }
            List<String> phaseNames = generations.get(key(scope));
            List<SortedSet<String>> retiredPerPhase = retiredByPhase.getOrDefault(key(scope), List.of());
            List<Set<String>> livePerPhase = liveByPhase.getOrDefault(key(scope), List.of());
            for (int i = 0; i < retiredPerPhase.size(); i++) {
                String phase = phaseNames.get(i);
                if (!retiredPerPhase.get(i).equals(snapshot.retiredAtoms())) {
                    v.structure.add(where + ": retired atoms after " + phase + " " + retiredPerPhase.get(i).size()
                            + " differ from the read-only generation's " + snapshot.retiredAtoms().size());
                }
                Set<String> liveAndRetired = new HashSet<>(retiredPerPhase.get(i));
                liveAndRetired.retainAll(livePerPhase.get(i));
                if (!liveAndRetired.isEmpty()) {
                    v.structure.add(where + ": atoms both live and retired after " + phase + " " + liveAndRetired);
                }
            }
            Set<String> liveAndRetired = new HashSet<>(snapshot.retiredAtoms());
            liveAndRetired.retainAll(snapshot.refsByAtom().keySet());
            if (!liveAndRetired.isEmpty()) {
                v.structure.add(where + ": atoms both live and retired in the read-only generation " + liveAndRetired);
            }
            structures.add(new ScopeStructure(key(scope), scope.events().size(), scope.executions(),
                    scope.patternCounts(), scope.attemptsStarted(), scope.unfinishedAttempts(),
                    scope.correctedFinishes(), scope.activeAttempts(), refCount, snapshot.refsByAtom().size(),
                    retiredPerPhase.stream().map(Set::size).toList(), snapshot.retiredAtoms().size(),
                    snapshot.dimensions(),
                    snapshot.refsByAtom().size() + snapshot.retiredAtoms().size(), generations.get(key(scope)),
                    scope.queries().size()));
        }

        private static Set<Set<String>> partitionByAtom(ProjectionCheckpoint.Snapshot snapshot) {
            Set<Set<String>> out = new HashSet<>();
            snapshot.refsByAtom().values().forEach(refs -> out.add(refs.stream().map(ExperienceRefCodec::canonical)
                    .collect(Collectors.toSet())));
            return out;
        }

        private static Set<Set<String>> partitionByText(ScopeCorpus scope) {
            Map<String, Set<String>> byText = new LinkedHashMap<>();
            scope.experiences().forEach(e -> byText.computeIfAbsent(e.text(), t -> new HashSet<>())
                    .add(ExperienceRefCodec.canonical(e.ref())));
            return new HashSet<>(byText.values());
        }

        /**
         * Reads the published generation once after {@code phase}: its name and its retired and live atom ids. A
         * missing or unreadable projection is recorded as a structural violation, never thrown.
         */
        private void observe(ScopeCorpus scope, String phase) {
            Optional<ProjectionCheckpoint.Snapshot> snapshot = checkpoint(scope, "after " + phase);
            String name;
            try {
                name = ProjectionLayout.of(root, scope.scope()).currentDir().map(p -> p.getFileName().toString())
                        .orElse("<none>");
            } catch (IOException e) {
                name = "<unreadable>";
            }
            generations.computeIfAbsent(key(scope), k -> new ArrayList<>()).add(phase + "=" + name);
            if (snapshot.isPresent()) {
                retiredByPhase.computeIfAbsent(key(scope), k -> new ArrayList<>()).add(snapshot.get().retiredAtoms());
                liveByPhase.computeIfAbsent(key(scope), k -> new ArrayList<>()).add(snapshot.get().refsByAtom()
                        .keySet());
            } else {
                retiredByPhase.computeIfAbsent(key(scope), k -> new ArrayList<>()).add(new java.util.TreeSet<>());
                liveByPhase.computeIfAbsent(key(scope), k -> new ArrayList<>()).add(Set.of());
            }
        }

        /** The published generation's checkpoint, or empty with a structural violation when it cannot be read. */
        private Optional<ProjectionCheckpoint.Snapshot> checkpoint(ScopeCorpus scope, String phase) {
            String where = label + " " + key(scope) + " " + phase;
            try {
                ProjectionLayout layout = ProjectionLayout.of(root, scope.scope());
                Optional<Path> current = layout.currentDir();
                if (current.isEmpty()) {
                    v.structure.add(where + ": no published projection");
                    return Optional.empty();
                }
                return Optional.of(ProjectionCheckpoint.read(layout.checkpoint(current.get()), scope.scope()));
            } catch (IOException | RuntimeException e) {
                v.structure.add(where + ": published projection is unreadable: " + e.getMessage());
                return Optional.empty();
            }
        }

        private Disk disk(ScopeCorpus scope) {
            try {
                ProjectionLayout layout = ProjectionLayout.of(root, scope.scope());
                long projection = 0;
                for (Path generation : layout.generationDirs()) {
                    projection += bytes(generation);
                }
                return new Disk(bytes(layout.scopeDir()) - projection, projection);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        SizeResult result() {
            return new SizeResult(corpus.experiencesPerScope(), corpus.scopes().size(), queryDigest(corpus), structures,
                    selfRecallTargets, selfRecallAtOne);
        }

        SizeTiming timing() {
            return new SizeTiming(corpus.experiencesPerScope(), phases, latency, heapDelta, readOnlySnapshots, disk);
        }
    }

    // --- shared checks -------------------------------------------------------------------------------------

    /** Hits must be ordered by similarity descending, then atom id ascending, then canonical ref ascending. */
    static List<String> orderViolations(String where, List<ExperienceHit> hits) {
        Comparator<ExperienceHit> order = Comparator.comparingDouble(ExperienceHit::similarity).reversed()
                .thenComparing(ExperienceHit::atomId)
                .thenComparing(h -> ExperienceRefCodec.canonical(h.ref()));
        List<String> violations = new ArrayList<>();
        for (int i = 1; i < hits.size(); i++) {
            if (order.compare(hits.get(i - 1), hits.get(i)) >= 0) {
                violations.add(where + ": rank " + (i + 1) + " is out of the deterministic tie order");
            }
        }
        return violations;
    }

    /**
     * The refs of one atom must be contiguous and, unless cut by K at the end of the list, be all of the atom's
     * refs in the published checkpoint, in canonical order.
     */
    static List<String> atomViolations(String where, List<ExperienceHit> hits, int k,
                                       ProjectionCheckpoint.Snapshot snapshot) {
        List<String> violations = new ArrayList<>();
        Map<String, List<String>> byAtom = new LinkedHashMap<>();
        String previous = null;
        for (ExperienceHit h : hits) {
            if (!h.atomId().equals(previous) && byAtom.containsKey(h.atomId())) {
                violations.add(where + ": refs of atom " + h.atomId() + " are not contiguous");
            }
            byAtom.computeIfAbsent(h.atomId(), a -> new ArrayList<>()).add(ExperienceRefCodec.canonical(h.ref()));
            previous = h.atomId();
        }
        List<String> atoms = new ArrayList<>(byAtom.keySet());
        for (int i = 0; i < atoms.size(); i++) {
            String atom = atoms.get(i);
            List<ExperienceRef> projected = snapshot.refsByAtom().get(atom);
            if (projected == null) {
                violations.add(where + ": atom " + atom + " is not live in the published checkpoint");
                continue;
            }
            List<String> all = projected.stream().map(ExperienceRefCodec::canonical).sorted().toList();
            List<String> got = byAtom.get(atom);
            boolean truncated = i == atoms.size() - 1 && hits.size() == k;
            boolean complete = truncated ? all.subList(0, Math.min(got.size(), all.size())).equals(got)
                    : all.equals(got);
            if (!complete) {
                violations.add(where + ": atom " + atom + " returned refs " + got + ", projected " + all);
            }
        }
        return violations;
    }

    /**
     * A hit must load exactly from the ledger at its revision, with every coordinate of its ref matching the
     * stored envelope, be an attempt finish (or its correction to one), and its attempt must load the generated
     * latest solution.
     */
    static List<String> referenceViolations(String where, ExecutionMemory memory, ScopeId scope, ExperienceHit h,
                                            Experience expected) {
        List<String> violations = new ArrayList<>();
        ExperienceRef ref = h.ref();
        Optional<HistoryEntry> exact = memory.loadEvent(scope, ref.eventId(), ref.revision());
        if (exact.isEmpty() || !exact.get().equals(h.event())) {
            violations.add(where + ": hit " + ref.eventId().value() + " r" + ref.revision()
                    + " does not equal the exact ledger load");
        }
        exact.ifPresent(entry -> violations.addAll(ForgeExperienceEvaluator.coordinateViolations(where + ": hit",
                scope, ref, entry)));
        ExecutionEvent event = h.event().event();
        ExecutionEvent fact = event instanceof ExecutionEvent.Correction c ? c.replacement() : event;
        if (fact.kind() != EventKind.ATTEMPT_FINISHED) {
            violations.add(where + ": hit " + ref.eventId().value() + " is a " + fact.kind());
        } else if (((ExecutionEvent.AttemptFinished) fact).result() != expected.result()) {
            violations.add(where + ": hit " + expected.experienceId() + " finished "
                    + ((ExecutionEvent.AttemptFinished) fact).result() + ", expected " + expected.result());
        }
        Optional<AttemptView> view = ref.attempt().flatMap(a -> memory.loadAttempt(scope, ref.execution(), a));
        if (view.isEmpty() || !view.get().solutionSummary().equals(Optional.of(expected.solution()))) {
            violations.add(where + ": " + expected.experienceId() + " loads a different solution than its latest "
                    + "revision");
        }
        return violations;
    }

    /** SHA-256 (first 16 hex digits) of every scope's queries: id, kind, text and target, in order. */
    static String queryDigest(ScaleCorpus corpus) {
        StringBuilder text = new StringBuilder();
        corpus.scopes().forEach(s -> s.queries().forEach(q -> text.append(q.id()).append('|').append(q.kind())
                .append('|').append(q.text()).append('|').append(q.target().orElse("")).append('\n')));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(ScopeCorpus scope) {
        return scope.scope().value();
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.TreeSet<>(a);
        out.removeAll(b);
        return out.size() > 5 ? Set.of(out.size() + " refs") : out;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long bytes(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        }
    }
}
