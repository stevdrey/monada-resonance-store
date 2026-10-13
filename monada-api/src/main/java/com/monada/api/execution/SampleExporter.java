package com.monada.api.execution;

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
import java.util.List;
import java.util.Set;

/**
 * Read-only paging over the executions of one scope (contract v1, section 12). It reads the in-memory ledger
 * index only: it opens no file, never touches the projection and writes nothing, so a ledger, a projection and the
 * ranking-feedback files are byte-identical before and after any export.
 *
 * <p>Executions are ordered by the ledger sequence of their first event, which a later append can never change;
 * the cursor remembers the last start sequence returned. Samples are derived from the prefix up to the cutoff
 * only, so appends and corrections recorded later are invisible to a pinned cutoff.
 */
final class SampleExporter {
    private final List<String> payloadDigests = new ArrayList<>(); // index seq - 1, filled lazily

    ExperienceSamplePage export(ExportRequest request, HistoryIndex index, ProjectionStatus projection,
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
        } else {
            cutoff = resolve(request.cutoff().get(), all);
            after = 0;
        }
        ExportView view = request.view();
        Set<QualityDimension> gates = request.evidencePolicy().additionalGates();

        // An entry opens an execution exactly when it is the first of its execution.
        List<ExecutionId> page = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        boolean hasMore = false;
        for (long seq = after + 1; seq <= cutoff; seq++) {
            HistoryEntry entry = all.get((int) seq - 1);
            ExecutionId execution = entry.event().executionId();
            if (index.execution(execution).get(0).sequence() != seq) {
                continue;
            }
            if (page.size() == request.pageSize()) {
                hasMore = true;
                break;
            }
            page.add(execution);
            starts.add(seq);
        }
        List<ExperienceSample> samples = new ArrayList<>();
        for (ExecutionId execution : page) {
            List<HistoryEntry> history = index.execution(execution).stream()
                    .filter(e -> e.sequence() <= cutoff).toList();
            samples.add(SampleBuilder.build(scope, execution, history, view, gates));
        }
        java.util.Optional<ExportCursor> next = hasMore
                ? java.util.Optional.of(new ExportCursor(scope, cutoff, starts.get(starts.size() - 1), view, gates))
                : java.util.Optional.empty();
        return new ExperienceSamplePage(ExperienceSamplePage.SCHEMA, scope, view,
                gates.isEmpty() ? List.of() : List.copyOf(EnumSet.copyOf(gates)),
                new LedgerCheckpoint(cutoff, prefixDigest(all, cutoff)),
                new ProjectionStamp(ProjectionCheckpoint.FORMAT_VERSION, ProjectionCheckpoint.TEXT_SCHEME,
                        projection.state(), projection.coveredSequence() >= cutoff),
                samples, hasMore, next);
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

    /** SHA-256 over {@code <sequence>:<payload digest> LF} for sequences 1..upTo (see {@link LedgerCheckpoint}). */
    private synchronized String prefixDigest(List<HistoryEntry> all, long upTo) {
        while (payloadDigests.size() < upTo) {
            payloadDigests.add(ProjectionCheckpoint.ledgerDigest(all.get(payloadDigests.size()).event()));
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < upTo; i++) {
                sha.update((all.get(i).sequence() + ":" + payloadDigests.get(i) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
