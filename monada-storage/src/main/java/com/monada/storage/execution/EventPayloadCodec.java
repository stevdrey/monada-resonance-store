package com.monada.storage.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.OutcomeStatus;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import com.monada.core.execution.UsageProvenance;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Canonical, versioned payload codec of ledger schema 1 (contract section 3).
 *
 * <p>A payload is a flat sequence of tokens joined by {@code |}, in a fixed field order. Text tokens are
 * escaped ({@code \\ \p \t \n \r} and {@code \xHHHH} for other ISO control characters) so a payload never
 * contains a raw TAB, LF or {@code |} other than as separator. An optional value is the empty token when
 * absent and {@code ~} followed by the (escaped) value when present. Lists are a count token followed by
 * their elements. Enum sets are written in ordinal order. The same event always encodes to the same bytes.
 * Decoding is strict: a missing, extra or non-canonical token is rejected.
 */
final class EventPayloadCodec {
    static final String SCHEMA_VERSION = "1";

    private EventPayloadCodec() {
    }

    /** Thrown for payloads that cannot be decoded; maps to a malformed-record or unsupported-schema diagnostic. */
    static final class PayloadException extends Exception {
        private final LedgerDiagnosticCategory category;

        PayloadException(LedgerDiagnosticCategory category, String message) {
            super(message);
            this.category = category;
        }

        LedgerDiagnosticCategory category() {
            return category;
        }
    }

    static byte[] encode(ExecutionEvent event) {
        Out out = new Out();
        out.raw(SCHEMA_VERSION);
        out.raw(event.kind().name());
        EventEnvelope env = event.envelope();
        out.text(env.eventId().value());
        out.text(env.scopeId().value());
        out.text(env.taskId().value());
        out.text(env.executionId().value());
        out.opt(env.attemptId().map(AttemptId::value));
        out.raw(Integer.toString(env.revision()));
        out.opt(env.supersedes().map(EventId::value));
        out.raw(env.occurredAt().toString());
        out.raw(env.recordedAt().toString());
        body(out, event);
        return out.build().getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the schema version token of a payload without decoding the rest. */
    static String schemaOf(String payload) {
        int end = payload.indexOf('|');
        return end < 0 ? payload : payload.substring(0, end);
    }

    static ExecutionEvent decode(String payload) throws PayloadException {
        String schema = schemaOf(payload);
        if (!SCHEMA_VERSION.equals(schema)) {
            throw new PayloadException(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA,
                    "unsupported payload schema version '" + printable(schema) + "'");
        }
        try {
            In in = new In(payload);
            in.raw(); // schema version
            EventKind kind = in.enumValue(EventKind.class);
            EventId eventId = EventId.of(in.text());
            ScopeId scope = ScopeId.of(in.text());
            TaskId task = TaskId.of(in.text());
            ExecutionId execution = ExecutionId.of(in.text());
            Optional<AttemptId> attempt = in.opt().map(AttemptId::of);
            int revision = in.integer();
            Optional<EventId> supersedes = in.opt().map(EventId::of);
            Instant occurredAt = Instant.parse(in.raw());
            Instant recordedAt = Instant.parse(in.raw());
            EventEnvelope envelope = new EventEnvelope(eventId, scope, task, execution, attempt, revision,
                    supersedes, occurredAt, recordedAt);
            ExecutionEvent event;
            if (kind == EventKind.CORRECTION) {
                String justification = in.text();
                EventKind replacementKind = in.enumValue(EventKind.class);
                if (replacementKind == EventKind.CORRECTION) {
                    throw new IllegalArgumentException("a Correction cannot replace a Correction");
                }
                EventEnvelope replacementEnvelope = new EventEnvelope(eventId, scope, task, execution, attempt, 1,
                        Optional.empty(), occurredAt, recordedAt);
                event = new Correction(envelope, decodeBody(in, replacementKind, replacementEnvelope), justification);
            } else {
                event = decodeBody(in, kind, envelope);
            }
            in.end();
            return event;
        } catch (PayloadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PayloadException(LedgerDiagnosticCategory.MALFORMED_RECORD,
                    "invalid payload: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ encoding

    private static void body(Out out, ExecutionEvent event) {
        switch (event) {
            case Correction c -> {
                out.text(c.justification());
                out.raw(c.replacement().kind().name());
                body(out, c.replacement());
            }
            case ExecutionStarted e -> {
                out.text(e.taskSummary());
                out.text(e.provenance().sourceRevision());
                out.text(e.provenance().contextFingerprint());
                out.text(e.provenance().constraintsFingerprint());
                out.text(e.policy().id());
                out.text(e.policy().version());
                List<QualityDimension> dims = e.policy().mandatoryDimensions().stream()
                        .sorted(Comparator.comparingInt(QualityDimension::ordinal)).toList();
                out.raw(Integer.toString(dims.size()));
                dims.forEach(d -> out.raw(d.name()));
            }
            case AttemptStarted e -> {
                out.raw(Integer.toString(e.ordinal()));
                out.opt(e.previousAttempt().map(AttemptId::value));
                out.raw(e.reason().name());
            }
            case StageRecorded e -> {
                out.text(e.stage());
                out.text(e.route().worker());
                out.opt(e.route().provider());
                out.opt(e.route().model());
                out.opt(e.route().effort());
                out.raw(e.route().billingMode().name());
                out.raw(e.startedAt().toString());
                out.raw(e.endedAt().toString());
                out.raw(e.usageDeclaration().name());
                out.opt(e.justification());
                out.raw(Integer.toString(e.usage().size()));
                for (UsageCounter u : e.usage()) {
                    out.raw(u.kind().name());
                    out.raw(u.value().isPresent() ? Long.toString(u.value().getAsLong()) : "");
                    out.raw(u.provenance().name());
                    out.text(u.source());
                }
                artifacts(out, e.artifacts());
            }
            case EvidenceRecorded e -> {
                out.raw(Integer.toString(e.observations().size()));
                for (QualityObservation o : e.observations()) {
                    out.raw(o.dimension().name());
                    out.raw(o.state().name());
                    out.raw(o.measuredValue().isPresent() ? Double.toString(o.measuredValue().getAsDouble()) : "");
                    out.opt(o.unit());
                    out.text(o.evaluatorId());
                    out.text(o.evaluatorVersion());
                    out.text(o.policyId());
                    out.text(o.policyVersion());
                    out.raw(o.source().name());
                    out.opt(o.justification());
                    artifacts(out, o.evidence());
                }
                artifacts(out, e.artifacts());
            }
            case AttemptFinished e -> {
                out.raw(e.result().name());
                out.opt(e.solutionSummary());
                out.opt(e.lessonSummary());
            }
            case OutcomeRecorded e -> {
                out.raw(e.outcome().status().name());
                out.raw(e.outcome().origin().name());
                out.opt(e.outcome().acceptedAttempt().map(AttemptId::value));
            }
        }
    }

    private static void artifacts(Out out, List<ArtifactRef> refs) {
        out.raw(Integer.toString(refs.size()));
        for (ArtifactRef r : refs) {
            out.text(r.kind());
            out.text(r.reference());
            out.opt(r.digest());
        }
    }

    // ------------------------------------------------------------------ decoding

    private static ExecutionEvent decodeBody(In in, EventKind kind, EventEnvelope envelope) throws PayloadException {
        return switch (kind) {
            case EXECUTION_STARTED -> {
                String summary = in.text();
                SourceProvenance provenance = new SourceProvenance(in.text(), in.text(), in.text());
                String policyId = in.text();
                String policyVersion = in.text();
                int count = in.count();
                List<QualityDimension> dims = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    dims.add(in.enumValue(QualityDimension.class));
                }
                requireStrictlyAscending(dims);
                yield new ExecutionStarted(envelope, summary, provenance,
                        new EvaluationPolicy(policyId, policyVersion, java.util.Set.copyOf(dims)));
            }
            case ATTEMPT_STARTED -> new AttemptStarted(envelope, in.integer(), in.opt().map(AttemptId::of),
                    in.enumValue(AttemptReason.class));
            case STAGE_RECORDED -> {
                String stage = in.text();
                RouteDescriptor route = new RouteDescriptor(in.text(), in.opt(), in.opt(), in.opt(),
                        in.enumValue(BillingMode.class));
                Instant startedAt = Instant.parse(in.raw());
                Instant endedAt = Instant.parse(in.raw());
                UsageDeclaration declaration = in.enumValue(UsageDeclaration.class);
                Optional<String> justification = in.opt();
                int count = in.count();
                List<UsageCounter> usage = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    UsageKind usageKind = in.enumValue(UsageKind.class);
                    String value = in.raw();
                    OptionalLong amount = value.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
                    usage.add(new UsageCounter(usageKind, amount, in.enumValue(UsageProvenance.class), in.text()));
                }
                yield new StageRecorded(envelope, stage, route, startedAt, endedAt, declaration, justification,
                        usage, readArtifacts(in));
            }
            case EVIDENCE_RECORDED -> {
                int count = in.count();
                List<QualityObservation> observations = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    QualityDimension dimension = in.enumValue(QualityDimension.class);
                    ObservationState state = in.enumValue(ObservationState.class);
                    String measured = in.raw();
                    OptionalDouble value = measured.isEmpty() ? OptionalDouble.empty()
                            : OptionalDouble.of(Double.parseDouble(measured));
                    Optional<String> unit = in.opt();
                    String evaluatorId = in.text();
                    String evaluatorVersion = in.text();
                    String policyId = in.text();
                    String policyVersion = in.text();
                    ObservationSource source = in.enumValue(ObservationSource.class);
                    Optional<String> justification = in.opt();
                    observations.add(new QualityObservation(dimension, state, value, unit, evaluatorId,
                            evaluatorVersion, policyId, policyVersion, source, readArtifacts(in), justification));
                }
                yield new EvidenceRecorded(envelope, observations, readArtifacts(in));
            }
            case ATTEMPT_FINISHED -> new AttemptFinished(envelope, in.enumValue(AttemptResult.class), in.opt(),
                    in.opt());
            case OUTCOME_RECORDED -> new OutcomeRecorded(envelope, new Outcome(in.enumValue(OutcomeStatus.class),
                    in.enumValue(OutcomeOrigin.class), in.opt().map(AttemptId::of)));
            case CORRECTION -> throw new PayloadException(LedgerDiagnosticCategory.MALFORMED_RECORD,
                    "nested correction");
        };
    }

    private static List<ArtifactRef> readArtifacts(In in) throws PayloadException {
        int count = in.count();
        List<ArtifactRef> refs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            refs.add(new ArtifactRef(in.text(), in.text(), in.opt()));
        }
        return refs;
    }

    private static void requireStrictlyAscending(List<QualityDimension> dims) throws PayloadException {
        for (int i = 1; i < dims.size(); i++) {
            if (dims.get(i).ordinal() <= dims.get(i - 1).ordinal()) {
                throw new PayloadException(LedgerDiagnosticCategory.MALFORMED_RECORD,
                        "mandatory dimensions must be unique and in ordinal order");
            }
        }
    }

    private static String printable(String value) {
        String shown = value.length() > 32 ? value.substring(0, 32) + "..." : value;
        return escape(shown);
    }

    // ------------------------------------------------------------------ escaping

    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\p");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (Character.isISOControl(cp)) {
                        sb.append(String.format("\\x%04X", cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        return sb.toString();
    }

    static String unescape(String token) {
        if (token.indexOf('\\') < 0) {
            return token;
        }
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= token.length()) {
                throw new IllegalArgumentException("dangling escape");
            }
            char e = token.charAt(i);
            switch (e) {
                case '\\' -> sb.append('\\');
                case 'p' -> sb.append('|');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'x' -> {
                    if (i + 5 > token.length()) {
                        throw new IllegalArgumentException("truncated \\x escape");
                    }
                    String hex = token.substring(i + 1, i + 5);
                    for (int k = 0; k < 4; k++) {
                        char h = hex.charAt(k);
                        if (!((h >= '0' && h <= '9') || (h >= 'A' && h <= 'F'))) {
                            throw new IllegalArgumentException("invalid \\x escape");
                        }
                    }
                    int cp = Integer.parseInt(hex, 16);
                    if (!Character.isISOControl(cp) || cp == '\t' || cp == '\n' || cp == '\r') {
                        throw new IllegalArgumentException("non-canonical \\x escape");
                    }
                    sb.append((char) cp);
                    i += 4;
                }
                default -> throw new IllegalArgumentException("unknown escape \\" + e);
            }
        }
        return sb.toString();
    }

    private static final class Out {
        private final List<String> tokens = new ArrayList<>();

        void raw(String token) {
            tokens.add(token);
        }

        void text(String value) {
            tokens.add(escape(value));
        }

        void opt(Optional<String> value) {
            tokens.add(value.map(v -> "~" + escape(v)).orElse(""));
        }

        String build() {
            return String.join("|", tokens);
        }
    }

    private static final class In {
        private final String[] tokens;
        private int next;

        In(String payload) {
            this.tokens = split(payload);
        }

        private static String[] split(String payload) {
            List<String> parts = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < payload.length(); i++) {
                if (payload.charAt(i) == '|') {
                    parts.add(payload.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(payload.substring(start));
            return parts.toArray(new String[0]);
        }

        String raw() {
            if (next >= tokens.length) {
                throw new IllegalArgumentException("payload ended early");
            }
            return tokens[next++];
        }

        String text() {
            return unescape(raw());
        }

        Optional<String> opt() {
            String token = raw();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            if (token.charAt(0) != '~') {
                throw new IllegalArgumentException("optional token must be empty or start with '~'");
            }
            return Optional.of(unescape(token.substring(1)));
        }

        int integer() {
            return Integer.parseInt(raw());
        }

        int count() {
            int count = integer();
            if (count < 0 || count > 4096) {
                throw new IllegalArgumentException("invalid list size " + count);
            }
            return count;
        }

        <E extends Enum<E>> E enumValue(Class<E> type) {
            return Enum.valueOf(type, raw());
        }

        void end() {
            if (next != tokens.length) {
                throw new IllegalArgumentException("unexpected extra tokens");
            }
        }
    }
}
