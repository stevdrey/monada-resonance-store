package com.monada.storage.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical string of an {@link ExperienceRef}:
 * {@code scope|task|execution|attempt|eventId|revision}, each identifier escaped like ledger payload tokens
 * ({@code \\}, {@code \p} for {@code |}, ...), the attempt written as an empty token when absent and
 * {@code ~id} when present. The string contains no raw {@code |} inside a field, is stable across runs and
 * is the final tie-break of recall ordering (plain {@link String#compareTo}).
 */
public final class ExperienceRefCodec {
    /** Number of {@code |}-separated tokens of a canonical ref. */
    public static final int TOKENS = 6;

    private ExperienceRefCodec() {
    }

    public static String canonical(ExperienceRef ref) {
        Objects.requireNonNull(ref, "ref");
        return String.join("|",
                EventPayloadCodec.escape(ref.scope().value()),
                EventPayloadCodec.escape(ref.task().value()),
                EventPayloadCodec.escape(ref.execution().value()),
                ref.attempt().map(a -> "~" + EventPayloadCodec.escape(a.value())).orElse(""),
                EventPayloadCodec.escape(ref.eventId().value()),
                Integer.toString(ref.revision()));
    }

    /** Strict inverse of {@link #canonical}; a non-canonical string is rejected. */
    public static ExperienceRef parse(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        String[] t = canonical.split("\\|", -1);
        if (t.length != TOKENS) {
            throw new IllegalArgumentException("experience ref must have " + TOKENS + " tokens");
        }
        return fromTokens(t, 0);
    }

    static ExperienceRef fromTokens(String[] t, int from) {
        Optional<AttemptId> attempt;
        String a = t[from + 3];
        if (a.isEmpty()) {
            attempt = Optional.empty();
        } else if (a.startsWith("~")) {
            attempt = Optional.of(AttemptId.of(EventPayloadCodec.unescape(a.substring(1))));
        } else {
            throw new IllegalArgumentException("malformed optional attempt token");
        }
        String revision = t[from + 5];
        if (!revision.matches("[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("malformed revision");
        }
        ExperienceRef ref = new ExperienceRef(
                ScopeId.of(EventPayloadCodec.unescape(t[from])),
                TaskId.of(EventPayloadCodec.unescape(t[from + 1])),
                ExecutionId.of(EventPayloadCodec.unescape(t[from + 2])),
                attempt,
                EventId.of(EventPayloadCodec.unescape(t[from + 4])),
                Integer.parseInt(revision));
        String[] again = canonical(ref).split("\\|", -1);
        for (int i = 0; i < TOKENS; i++) {
            if (!again[i].equals(t[from + i])) {
                throw new IllegalArgumentException("non-canonical experience ref");
            }
        }
        return ref;
    }
}
