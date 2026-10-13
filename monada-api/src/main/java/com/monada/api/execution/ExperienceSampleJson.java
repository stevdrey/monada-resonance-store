package com.monada.api.execution;

import com.monada.api.execution.ExperienceSample.Gate;
import com.monada.api.execution.ExperienceSample.OutcomeFact;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Canonical, human-inspectable rendering of an {@link ExperienceSamplePage} (schema
 * {@value ExperienceSamplePage#SCHEMA}): JSON with a fixed field order, two-space indentation, LF line endings,
 * absent optional values as {@code null} and no trailing whitespace. The same page always renders to the same
 * bytes, so {@link #digest} is a reproducibility fingerprint. No JSON library is involved.
 *
 * <p>The format is write-only on purpose: consumers read the JSON with their own parser, and the typed records
 * are the in-process API.
 */
public final class ExperienceSampleJson {
    private ExperienceSampleJson() {
    }

    public static String toJson(ExperienceSamplePage page) {
        Objects.requireNonNull(page, "page");
        StringBuilder out = new StringBuilder(4096);
        write(out, page(page), 0);
        return out.append('\n').toString();
    }

    /** SHA-256 (hex) of the UTF-8 bytes of {@link #toJson}. */
    public static String digest(ExperienceSamplePage page) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(toJson(page).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    // --- tree -------------------------------------------------------------------------------------------

    private static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static <T> List<Object> list(List<T> items, java.util.function.Function<T, Object> f) {
        List<Object> out = new ArrayList<>();
        items.forEach(i -> out.add(f.apply(i)));
        return out;
    }

    private static Object opt(Optional<?> value) {
        return value.orElse(null);
    }

    private static Object name(Enum<?> e) {
        return e.name();
    }

    private static Map<String, Object> page(ExperienceSamplePage p) {
        return obj("schema", p.schema(), "scope", p.scope().value(), "view", name(p.view()),
                "additionalGates", list(p.additionalGates(), ExperienceSampleJson::name),
                "ledger", obj("cutoffSequence", p.ledger().cutoffSequence(),
                        "prefixDigest", p.ledger().prefixDigest()),
                "projection", obj("formatVersion", (long) p.projection().formatVersion(),
                        "textScheme", p.projection().textScheme()),
                "samples", list(p.samples(), ExperienceSampleJson::sample),
                "hasMore", p.hasMore(), "nextCursor", p.next().map(ExportCursor::token).orElse(null));
    }

    private static Map<String, Object> sample(ExperienceSample s) {
        return obj("execution", ref(s.execution()), "taskSummary", s.taskSummary(),
                "provenance", obj("sourceRevision", s.provenance().sourceRevision(),
                        "contextFingerprint", s.provenance().contextFingerprint(),
                        "constraintsFingerprint", s.provenance().constraintsFingerprint()),
                "policy", obj("id", s.policy().id(), "version", s.policy().version(),
                        "mandatoryDimensions", list(List.copyOf(new java.util.TreeSet<>(s.policy().mandatoryDimensions())),
                                ExperienceSampleJson::name)),
                "requiredGates", list(s.requiredGates(), ExperienceSampleJson::name),
                "outcome", s.outcome().map(ExperienceSampleJson::outcomeFact).orElse(null),
                "acceptance", name(s.acceptance()), "label", name(s.label()),
                "successOrdinal", s.successOrdinal().isPresent() ? (Object) (long) s.successOrdinal().getAsInt() : null,
                "group", obj("task", s.group().task().value(), "sourceRevision", s.group().sourceRevision(),
                        "contextFingerprint", s.group().contextFingerprint(),
                        "constraintsFingerprint", s.group().constraintsFingerprint(),
                        "policyId", s.group().policyId(), "policyVersion", s.group().policyVersion()),
                "eligibility", obj("eligible", s.eligibility().eligible(),
                        "reasons", list(s.eligibility().reasons(), r -> obj("code", name(r.code()),
                                "dimension", opt(r.dimension().map(ExperienceSampleJson::name)),
                                "detail", r.detail()))),
                "gates", list(s.gates(), ExperienceSampleJson::gate),
                "attempts", list(s.attempts(), ExperienceSampleJson::attempt),
                "revisions", list(s.revisions(), r -> obj("effective", ref(r.effective()), "sequence", r.sequence(),
                        "justification", r.justification(), "superseded", list(r.superseded(),
                                ExperienceSampleJson::ref))),
                "firstSequence", s.firstSequence(), "lastSequence", s.lastSequence(),
                "diagnostics", s.diagnostics().map(d -> (Object) obj("acceptanceReasons",
                        list(d.acceptanceReasons(), x -> x), "outcomeHistory",
                        list(d.outcomeHistory(), ExperienceSampleJson::outcomeFact), "ignoredObservations",
                        list(d.ignoredObservations(), i -> obj("ref", ref(i.ref()),
                                "observation", observation(i.observation()), "reason", i.reason())))).orElse(null));
    }

    private static Map<String, Object> ref(ExperienceRef r) {
        return obj("scope", r.scope().value(), "task", r.task().value(), "execution", r.execution().value(),
                "attempt", opt(r.attempt().map(a -> a.value())), "eventId", r.eventId().value(),
                "revision", (long) r.revision());
    }

    private static Map<String, Object> outcomeFact(OutcomeFact f) {
        Outcome o = f.outcome();
        return obj("ref", ref(f.ref()), "sequence", f.sequence(), "status", name(o.status()),
                "origin", name(o.origin()), "acceptedAttempt", opt(o.acceptedAttempt().map(a -> a.value())));
    }

    private static Map<String, Object> gate(Gate g) {
        return obj("dimension", name(g.dimension()), "required", g.required(), "state", name(g.state()),
                "observation", g.observation().map(ExperienceSampleJson::observation).orElse(null),
                "ref", g.ref().map(ExperienceSampleJson::ref).orElse(null), "sequence", seq(g.sequence()));
    }

    private static Object seq(OptionalLong value) {
        return value.isPresent() ? (Object) value.getAsLong() : null;
    }

    private static Map<String, Object> observation(QualityObservation o) {
        OptionalDouble v = o.measuredValue();
        return obj("dimension", name(o.dimension()), "state", name(o.state()),
                "measuredValue", v.isPresent() ? (Object) v.getAsDouble() : null, "unit", opt(o.unit()),
                "evaluatorId", o.evaluatorId(), "evaluatorVersion", o.evaluatorVersion(),
                "policyId", o.policyId(), "policyVersion", o.policyVersion(), "source", name(o.source()),
                "evidence", list(o.evidence(), ExperienceSampleJson::artifact), "justification", opt(o.justification()));
    }

    private static Map<String, Object> artifact(ArtifactRef a) {
        return obj("kind", a.kind(), "reference", a.reference(), "digest", opt(a.digest()));
    }

    private static Map<String, Object> route(RouteDescriptor r) {
        return obj("worker", r.worker(), "provider", opt(r.provider()), "model", opt(r.model()),
                "effort", opt(r.effort()), "billingMode", name(r.billingMode()));
    }

    private static Map<String, Object> attempt(ExperienceSample.SampleAttempt a) {
        return obj("id", a.id().value(), "start", ref(a.start()), "ordinal", (long) a.ordinal(),
                "reason", name(a.reason()), "previousAttempt", opt(a.previousAttempt().map(x -> x.value())),
                "accepted", a.accepted(), "result", opt(a.result().map(ExperienceSampleJson::name)),
                "finish", a.finish().map(ExperienceSampleJson::ref).orElse(null),
                "solutionSummary", opt(a.solutionSummary()), "lessonSummary", opt(a.lessonSummary()),
                "stages", list(a.stages(), st -> obj("ref", ref(st.ref()), "sequence", st.sequence(),
                        "stage", st.stage(), "route", route(st.route()), "startedAt", st.startedAt().toString(),
                        "endedAt", st.endedAt().toString(), "usageDeclaration", name(st.usageDeclaration()),
                        "justification", opt(st.justification()),
                        "usage", list(st.usage(), u -> obj("kind", name(u.kind()), "value", seq(u.value()),
                                "state", name(u.state()), "source", opt(u.source()))),
                        "artifacts", list(st.artifacts(), ExperienceSampleJson::artifact))),
                "evidence", list(a.evidence(), e -> obj("ref", ref(e.ref()), "sequence", e.sequence(),
                        "observations", list(e.observations(), o -> obj("policyMatches", o.policyMatches(),
                                "observation", observation(o.observation()))),
                        "artifacts", list(e.artifacts(), ExperienceSampleJson::artifact))));
    }

    // --- printer ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b);
            case Long l -> out.append(l);
            case Double d -> {
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("non-finite number");
                }
                out.append(d);
            }
            case Map<?, ?> m -> {
                if (m.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    indent(out, depth + 1);
                    string(out, e.getKey());
                    out.append(": ");
                    write(out, e.getValue(), depth + 1);
                    out.append(++i < m.size() ? ",\n" : "\n");
                }
                indent(out, depth);
                out.append('}');
            }
            case List<?> l -> {
                if (l.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    indent(out, depth + 1);
                    write(out, l.get(i), depth + 1);
                    out.append(i + 1 < l.size() ? ",\n" : "\n");
                }
                indent(out, depth);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported value " + value.getClass());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
