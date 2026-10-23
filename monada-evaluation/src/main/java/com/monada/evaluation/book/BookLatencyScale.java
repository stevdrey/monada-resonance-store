package com.monada.evaluation.book;

import com.monada.core.KnowledgeAtom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One point of the book latency data-size ladder (Issue #168): either a fraction of the frozen chunks (real text,
 * unmodified) or a deterministic replica multiplier over all of them (derived text, labelled as such).
 *
 * <p>A fraction {@code p%} keeps the evenly spaced ordinals {@code i} with {@code floor((i+1)p/100) > floor(ip/100)}
 * (Bresenham spacing), so every scale spans the whole book instead of its first chapters, and keeps
 * {@code floor(n p/100)} chunks. A multiplier {@code xN} keeps every chunk once as is and then {@code N-1} replicas of
 * the whole set, replica {@code r} of a chunk being {@code "[replica r] " + text}: the prefix gives each replica its
 * own content-derived atom id, so the Store really holds {@code N} times the atoms. Both are pure functions of the
 * chunk list and the spec: no clock, randomness or hash order.
 */
record BookLatencyScale(String id, String spec, Kind kind, int percent, int multiplier) {
    private static final Pattern PERCENT = Pattern.compile("([1-9][0-9]{0,2})%");
    private static final Pattern MULTIPLIER = Pattern.compile("x([1-9][0-9]{0,3})");

    /** Whether the measured texts are the book's own chunks or derived from them. */
    enum Kind {
        /** A subset (or all) of the frozen chunks, text unmodified. */
        REAL,
        /** Replica-tagged copies of the frozen chunks: real-book length and vocabulary, not real-book shape. */
        DERIVED
    }

    /**
     * The texts one scale ingests, in ingest order, plus what they are.
     *
     * @param atomIds every content-derived atom id of {@code texts}, in first-occurrence order (computed once)
     */
    record Selected(BookLatencyScale scale, List<String> texts, List<Integer> ordinals, Set<String> atomIds,
                    long textBytes) {
        Selected {
            texts = List.copyOf(texts);
            ordinals = List.copyOf(ordinals);
            atomIds = Collections.unmodifiableSet(new LinkedHashSet<>(atomIds));
        }

        /** Distinct atoms the Store holds after ingesting {@link #texts()}. */
        int uniqueAtoms() {
            return atomIds.size();
        }
    }

    static final String DEFAULT_LADDER = "25%,50%,100%,x4,x16";

    /** Parses a comma-separated ladder such as {@value #DEFAULT_LADDER}; order is kept, duplicates are refused. */
    static List<BookLatencyScale> parseLadder(String ladder) {
        List<BookLatencyScale> scales = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String token : ladder.split(",", -1)) {
            BookLatencyScale scale = parse(token.strip());
            if (!ids.add(scale.id())) {
                throw new IllegalArgumentException("duplicate scale " + scale.spec() + " in ladder " + ladder);
            }
            scales.add(scale);
        }
        return List.copyOf(scales);
    }

    static BookLatencyScale parse(String spec) {
        String token = spec.toLowerCase(Locale.ROOT);
        Matcher percent = PERCENT.matcher(token);
        if (percent.matches()) {
            int p = Integer.parseInt(percent.group(1));
            if (p > 100) {
                throw new IllegalArgumentException("scale " + spec + " is above 100%; use xN to replicate");
            }
            // x1 and 100% would measure the same texts twice under two names: only 100% is accepted.
            return new BookLatencyScale("p" + p, p + "%", Kind.REAL, p, 1);
        }
        Matcher multiplier = MULTIPLIER.matcher(token);
        if (multiplier.matches() && Integer.parseInt(multiplier.group(1)) >= 2) {
            int n = Integer.parseInt(multiplier.group(1));
            return new BookLatencyScale("x" + n, "x" + n, Kind.DERIVED, 100, n);
        }
        throw new IllegalArgumentException("bad scale '" + spec + "': expected 1%..100% or x2..x9999");
    }

    /**
     * Fails when this scale would select no chunk of {@code chunkCount}: count arithmetic only, so a ladder can be
     * checked against a corpus without building any scale.
     */
    void requireSelects(int chunkCount) {
        if ((long) chunkCount * percent / 100 < 1) {
            throw new IllegalArgumentException("scale " + spec + " selects no chunk of " + chunkCount);
        }
    }

    /** The texts of this scale over {@code chunks} (ordinal order). */
    Selected select(List<String> chunks) {
        requireSelects(chunks.size());
        List<String> texts = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        int n = chunks.size();
        for (int i = 0; i < n; i++) {
            if ((long) (i + 1) * percent / 100 > (long) i * percent / 100) {
                texts.add(chunks.get(i));
                ordinals.add(i);
            }
        }
        int base = texts.size();
        for (int r = 1; r < multiplier; r++) {
            for (int j = 0; j < base; j++) {
                texts.add(replica(r, texts.get(j)));
                ordinals.add(ordinals.get(j));
            }
        }
        Set<String> atoms = new LinkedHashSet<>();
        long bytes = 0;
        for (String text : texts) {
            atoms.add(KnowledgeAtom.text(text).id());
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
        }
        return new Selected(this, texts, ordinals, atoms, bytes);
    }

    static String replica(int r, String text) {
        return "[replica " + r + "] " + text;
    }

    String label() {
        return kind == Kind.REAL ? "REAL (frozen chunks, text unmodified)"
                : "DERIVED (replica-tagged, not real-book shape)";
    }
}
