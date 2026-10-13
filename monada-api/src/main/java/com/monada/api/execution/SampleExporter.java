package com.monada.api.execution;

import com.monada.api.execution.ExperienceSample.Group;
import com.monada.api.execution.ExperienceSamplePage.LedgerCheckpoint;
import com.monada.api.execution.ExperienceSamplePage.ProjectionStamp;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ProjectionCheckpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only paging over the executions of one scope (contract v1, section 12). It reads the in-memory ledger
 * index only: it opens no file, never touches the projection and writes nothing, so a ledger, a projection and the
 * ranking-feedback files are byte-identical before and after any export.
 *
 * <p>Executions are ordered by the ledger sequence of their first event, which a later append can never change;
 * the cursor remembers the last start sequence returned and the digest of the ledger prefix it was issued for.
 * Samples are derived from the prefix up to the cutoff only, so appends and corrections recorded later are
 * invisible to a pinned cutoff.
 */
final class SampleExporter {
    /** Sequences between two saved SHA-256 states of the running prefix digest. */
    static final int DEFAULT_CHECKPOINT_INTERVAL = 1024;

    private final int interval;
    private final List<String> payloadDigests = new ArrayList<>(); // index seq - 1, filled lazily
    private final List<MessageDigest> checkpoints = new ArrayList<>(); // [k]: state after sequences 1..(k+1)*interval
    private SuccessIndex successes; // the latest (cutoff, gates) success index

    SampleExporter() {
        this(DEFAULT_CHECKPOINT_INTERVAL);
    }

    SampleExporter(int checkpointInterval) {
        if (checkpointInterval < 1) {
            throw new IllegalArgumentException("checkpointInterval must be >= 1");
        }
        this.interval = checkpointInterval;
    }

    /** The validated successes of every group as of one cutoff, in start order (see {@link Group}). */
    private record SuccessIndex(long cutoff, Set<QualityDimension> gates, Map<Group, List<ExecutionId>> byGroup) {
    }

    synchronized ExperienceSamplePage export(ExportRequest request, HistoryIndex index, ProjectionStatus projection,
                                             int maxPageSize) {
        if (request.pageSize() > maxPageSize) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + maxPageSize);
        }
        List<HistoryEntry> all = index.all(); // sequences are contiguous from 1
        ScopeId scope = request.scope();
        long cutoff;
        long after;
        if (request.cursor().isPresent()) {
            ExportCursor cursor = request.cursor().get();
            if (cursor.cutoffSequence() > all.size()) {
                throw new IllegalArgumentException("export cursor is beyond the end of this ledger");
            }
            cutoff = cursor.cutoffSequence();
            after = cursor.afterStartSequence();
            if (!cursor.ledgerDigest().equals(prefixDigest(all, (int) cutoff))) {
                throw new IllegalArgumentException("export cursor was issued for a different ledger history");
            }
        } else {
            cutoff = resolve(request.cutoff().get(), all);
            after = 0;
        }
        ExportView view = request.view();
        Set<QualityDimension> gates = request.evidencePolicy().additionalGates();
        String ledgerDigest = prefixDigest(all, (int) cutoff);

        List<Long> starts = startsAfter(index, after, cutoff, request.pageSize() + 1);
        boolean hasMore = starts.size() > request.pageSize();
        if (hasMore) {
            starts = starts.subList(0, request.pageSize());
        }
        List<ExperienceSample> samples = new ArrayList<>();
        for (long start : starts) {
            ExecutionId execution = all.get((int) start - 1).event().executionId();
            samples.add(sample(scope, execution, index, cutoff, view, gates));
        }
        Optional<ExportCursor> next = hasMore
                ? Optional.of(new ExportCursor(scope, cutoff, starts.get(starts.size() - 1), view, gates,
                        ledgerDigest))
                : Optional.empty();
        return new ExperienceSamplePage(ExperienceSamplePage.SCHEMA, scope, view,
                gates.isEmpty() ? List.of() : List.copyOf(EnumSet.copyOf(gates)),
                new LedgerCheckpoint(cutoff, ledgerDigest),
                new ProjectionStamp(ProjectionCheckpoint.FORMAT_VERSION, ProjectionCheckpoint.TEXT_SCHEME,
                        projection.state(), projection.coveredSequence() >= cutoff),
                samples, hasMore, next);
    }

    /** Start sequences (first event of an execution) in {@code (after, cutoff]}, at most {@code limit} of them. */
    private static List<Long> startsAfter(HistoryIndex index, long after, long cutoff, int limit) {
        List<HistoryEntry> all = index.all();
        List<Long> starts = new ArrayList<>();
        for (long seq = after + 1; seq <= cutoff && starts.size() < limit; seq++) {
            ExecutionId execution = all.get((int) seq - 1).event().executionId();
            if (index.execution(execution).get(0).sequence() == seq) {
                starts.add(seq);
            }
        }
        return starts;
    }

    private ExperienceSample sample(ScopeId scope, ExecutionId execution, HistoryIndex index, long cutoff,
                                    ExportView view, Set<QualityDimension> gates) {
        ExperienceSample s = SampleBuilder.build(scope, execution, boundedHistory(index, execution, cutoff), view,
                gates);
        if (s.label() != ExperienceSample.Label.SUCCESS) {
            return s;
        }
        List<ExecutionId> group = successIndex(scope, index, cutoff, gates).byGroup().get(s.group());
        return s.withSuccessOrdinal(group.indexOf(execution) + 1);
    }

    private static List<HistoryEntry> boundedHistory(HistoryIndex index, ExecutionId execution, long cutoff) {
        return index.execution(execution).stream().filter(e -> e.sequence() <= cutoff).toList();
    }

    /**
     * Labels every execution started by the cutoff once per (cutoff, gates) and keeps the successes of each group
     * in start order, so a sample's ordinal is stable whatever the page size (one pass over the scope, then cached).
     */
    private SuccessIndex successIndex(ScopeId scope, HistoryIndex index, long cutoff, Set<QualityDimension> gates) {
        if (successes != null && successes.cutoff() == cutoff && successes.gates().equals(gates)) {
            return successes;
        }
        Map<Group, List<ExecutionId>> byGroup = new LinkedHashMap<>();
        for (long start : startsAfter(index, 0, cutoff, Integer.MAX_VALUE)) {
            ExecutionId execution = index.all().get((int) start - 1).event().executionId();
            ExperienceSample s = SampleBuilder.build(scope, execution, boundedHistory(index, execution, cutoff),
                    ExportView.ADAPTER_READY, gates);
            if (s.label() == ExperienceSample.Label.SUCCESS) {
                byGroup.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(execution);
            }
        }
        successes = new SuccessIndex(cutoff, Set.copyOf(gates), byGroup);
        return successes;
    }

    static long resolve(ExportCutoff cutoff, List<HistoryEntry> all) {
        return switch (cutoff) {
            case ExportCutoff.Latest l -> all.size();
            case ExportCutoff.AtSequence s -> {
                if (s.sequence() > all.size()) {
                    throw new IllegalArgumentException("cutoff sequence " + s.sequence()
                            + " is beyond the end of this ledger (" + all.size() + ")");
                }
                yield s.sequence();
            }
            case ExportCutoff.RecordedUpTo t -> {
                int n = 0;
                while (n < all.size() && !all.get(n).event().envelope().recordedAt().isAfter(t.recordedAt())) {
                    n++;
                }
                yield n;
            }
        };
    }

    /**
     * SHA-256 over {@code <sequence>:<payload digest> LF} for sequences 1..upTo (see {@link LedgerCheckpoint}).
     * Saved states every {@code interval} sequences make a digest cost the lines after the nearest saved state, not
     * the whole prefix, so a paged export hashes the ledger once rather than once per page.
     */
    String prefixDigest(List<HistoryEntry> all, int upTo) {
        while (payloadDigests.size() < upTo) {
            payloadDigests.add(ProjectionCheckpoint.ledgerDigest(all.get(payloadDigests.size()).event()));
        }
        try {
            while ((checkpoints.size() + 1L) * interval <= upTo) {
                int from = checkpoints.size() * interval;
                MessageDigest sha = checkpoints.isEmpty() ? MessageDigest.getInstance("SHA-256")
                        : (MessageDigest) checkpoints.get(checkpoints.size() - 1).clone();
                for (int i = from; i < from + interval; i++) {
                    line(sha, all, i);
                }
                checkpoints.add(sha);
            }
            int saved = upTo / interval; // saved states that lie within the prefix
            MessageDigest sha = saved == 0 ? MessageDigest.getInstance("SHA-256")
                    : (MessageDigest) checkpoints.get(saved - 1).clone();
            for (int i = saved * interval; i < upTo; i++) {
                line(sha, all, i);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException | CloneNotSupportedException e) {
            throw new IllegalStateException("a cloneable SHA-256 is required", e);
        }
    }

    private void line(MessageDigest sha, List<HistoryEntry> all, int i) {
        sha.update((all.get(i).sequence() + ":" + payloadDigests.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
