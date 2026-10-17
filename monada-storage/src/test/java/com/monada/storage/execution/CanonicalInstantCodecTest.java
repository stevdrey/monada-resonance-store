package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.SourceProvenance;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The #158 phase 2 instant fast path ({@link EventPayloadCodec#parseInstant}, {@link EventPayloadCodec#appendInstant})
 * and the cheaper {@code encode} must behave exactly as {@link Instant#parse}, {@link Instant#toString()} and the
 * pre-#158 codec ({@link LegacyEventPayloadCodec}): the same instants, the same exceptions and messages, the same
 * bytes, and the same canonical-form verdict of the re-encode check.
 */
class CanonicalInstantCodecTest {
    /** Canonical, non-canonical, out-of-range, leap-second, offset and malformed instant tokens. */
    static final List<String> INSTANT_FORMS = List.of(
            // Instant.toString() forms
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:01.500Z", "2026-01-01T00:00:00.123456Z",
            "2026-01-01T00:00:00.123456789Z", "1970-01-01T00:00:00Z", "0000-01-01T00:00:00Z",
            "0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z", "2024-02-29T12:34:56Z",
            "2000-02-29T00:00:00.001Z", "2026-01-01T00:00:00.123450Z",
            // parseable, but not what toString() prints
            "2026-01-01T00:00:01.5Z", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000000Z",
            "2026-01-01T00:00:00.000000000Z", "2026-01-01T00:00:01.500000Z", "2026-01-01T00:00:01.500000000Z",
            "2026-01-01T00:00:00.12345678Z", "2026-01-01T00:00Z",
            "2026-01-01t00:00:00z", "2026-01-01T00:00:00z", "2026-01-01t00:00:00Z",
            // offsets
            "2026-01-01T00:00:00+00:00", "2026-01-01T01:00:00+01:00", "2026-01-01T00:00:00-00:30",
            "2026-01-01T00:00:00+01", "2026-01-01T00:00:00+0100", "2026-01-01T00:00:00.500+02:00",
            // out of range or invalid fields
            "2026-13-01T00:00:00Z", "2026-00-01T00:00:00Z", "2026-02-29T00:00:00Z", "2100-02-29T00:00:00Z",
            "2026-04-31T00:00:00Z", "2026-01-32T00:00:00Z", "2026-01-00T00:00:00Z", "2026-01-01T24:00:00Z",
            "2026-12-31T24:00:00Z", "2026-01-01T23:60:00Z", "2026-01-01T00:00:61Z", "2026-01-01T99:99:99Z",
            // leap seconds
            "2026-12-31T23:59:60Z", "2026-06-30T23:59:60Z", "2026-12-31T23:59:60.500Z", "2026-01-01T12:00:60Z",
            // years outside 0000-9999
            "+10000-01-01T00:00:00Z", "-0001-12-31T23:59:59Z", "+2026-01-01T00:00:00Z", "-2026-01-01T00:00:00Z",
            "-1000000000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z", "+1000000001-01-01T00:00:00Z",
            "10000-01-01T00:00:00Z",
            // malformed
            "", "Z", "2026-01-01T00:00:00", "2026-01-01T00:00:00.Z", "2026-01-01T00:00:00.1234567890Z",
            "2026-01-01 00:00:00Z", "2026-1-01T00:00:00Z", " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z ",
            "2026-01-01T0a:00:00Z", "2026-01-01T00:00:00,500Z", "2026-01-01T00:00:00.5a0Z",
            "２０２６-01-01T00:00:00Z", "2026-01-01T00:00:00.١٢٣Z", "2026/01/01T00:00:00Z",
            "2026-01-01T00:00:00.123.Z", "2026-01-01T00:00:00Z[UTC]", "2026-01-01T00:00:00UTC");

    private static final QualityDimension[] DIMENSIONS = QualityDimension.values();

    private static String outcome(Supplier<?> action) {
        try {
            Object value = action.get();
            return value instanceof Instant i ? "instant " + i.getEpochSecond() + "/" + i.getNano() : "value " + value;
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static void assertSameParse(String text) {
        assertEquals(outcome(() -> Instant.parse(text)), outcome(() -> EventPayloadCodec.parseInstant(text)), text);
    }

    @Test
    void parseMatchesInstantParseForEveryListedForm() {
        INSTANT_FORMS.forEach(CanonicalInstantCodecTest::assertSameParse);
    }

    @Test
    void parseMatchesInstantParseForEverySingleCharacterChangeOfCanonicalForms() {
        String alphabet = "0123456789-:.TZtz+ /a٠";
        for (String canonical : List.of("2024-02-29T23:59:59Z", "2026-12-31T23:59:59.999Z",
                "0000-01-01T00:00:00.000001Z", "9999-12-31T23:59:59.123456789Z")) {
            for (int i = 0; i < canonical.length(); i++) {
                for (char c : alphabet.toCharArray()) {
                    String changed = canonical.substring(0, i) + c + canonical.substring(i + 1);
                    assertSameParse(changed);
                    assertSameParse(canonical.substring(0, i) + c + canonical.substring(i));
                }
                assertSameParse(canonical.substring(0, i) + canonical.substring(i + 1));
            }
        }
    }

    @Test
    void parseMatchesInstantParseForSeededRandomFieldValues() {
        Random random = new Random(158);
        for (int n = 0; n < 20_000; n++) {
            String text = String.format("%04d-%02d-%02dT%02d:%02d:%02d", random.nextInt(10_000),
                    random.nextInt(14), random.nextInt(33), random.nextInt(26), random.nextInt(62),
                    random.nextInt(62));
            int digits = new int[] {0, 3, 6, 9, 1, 2, 4, 5, 7, 8}[random.nextInt(10)];
            if (digits > 0) {
                text += "." + String.format("%09d", random.nextInt(1_000_000_000)).substring(0, digits);
            }
            assertSameParse(text + "Z");
        }
    }

    private static List<Instant> sampleInstants() {
        List<Instant> instants = new ArrayList<>(List.of(Instant.EPOCH, Instant.MIN, Instant.MAX,
                Instant.ofEpochSecond(-1), Instant.ofEpochSecond(-1, 999_999_999), Instant.ofEpochSecond(0, 1)));
        long first = LocalDate.of(0, 1, 1).toEpochDay() * 86_400;
        long end = LocalDate.of(10_000, 1, 1).toEpochDay() * 86_400;
        for (long boundary : new long[] {first, end}) {
            for (long delta = -2; delta <= 2; delta++) {
                instants.add(Instant.ofEpochSecond(boundary + delta));
                instants.add(Instant.ofEpochSecond(boundary + delta, 999_999_999));
            }
        }
        Random random = new Random(1580);
        int[] nanos = {0, 1, 999_999_999, 500_000_000, 1_000, 1_000_000, 123_000, 120_000_000, 100};
        for (int n = 0; n < 20_000; n++) {
            long seconds = n % 10 == 0 ? random.nextLong(-(1L << 40), 1L << 40)
                    : random.nextLong(first - 1000, end + 1000);
            int nano = n % 2 == 0 ? nanos[random.nextInt(nanos.length)] : switch (random.nextInt(3)) {
                case 0 -> random.nextInt(1000) * 1_000_000;
                case 1 -> random.nextInt(1_000_000) * 1_000;
                default -> random.nextInt(1_000_000_000);
            };
            instants.add(Instant.ofEpochSecond(seconds, nano));
        }
        return instants;
    }

    @Test
    void appendInstantPrintsExactlyToString() {
        for (Instant instant : sampleInstants()) {
            StringBuilder sb = new StringBuilder("x|");
            EventPayloadCodec.appendInstant(sb, instant);
            assertEquals("x|" + instant, sb.toString());
            assertSameParse(instant.toString());
        }
    }

    private static List<ExecutionEvent> sampleEvents() {
        List<ExecutionEvent> events = new ArrayList<>(Events.fullRun());
        events.add(Events.noBillableStage("e9", Events.ATTEMPT));
        List<String> summaries = List.of("plain", "|", "\\", "\\\\p", "~", "a\u007fb", "c\u0085d", "\u009f",
                "\u0000\u001f", "tab\tlf\ncr\r", "😀 pair \ud83d\ude00", "ünï|\\x0001", "\u2028\u2029\ufeff");
        List<Instant> instants = sampleInstants();
        for (int i = 0; i < 400; i++) {
            ExecutionEvent started = ExecutionEvent.executionStarted(EventId.of("s" + i), Events.SCOPE, Events.TASK,
                    ExecutionId.of("x" + i), new SourceProvenance("r|" + i, "c", "k\\"),
                    new EvaluationPolicy("p", "1", Set.of(DIMENSIONS[i % DIMENSIONS.length])),
                    summaries.get(i % summaries.size()), instants.get(i));
            events.add(started);
        }
        return events;
    }

    @Test
    void encodeIsByteIdenticalToTheLegacyCodec() throws Exception {
        for (ExecutionEvent event : sampleEvents()) {
            byte[] payload = EventPayloadCodec.encode(event);
            assertArrayEquals(LegacyEventPayloadCodec.encode(event), payload, event.toString());
            String text = new String(payload, StandardCharsets.UTF_8);
            assertEquals(decodeOutcome(() -> LegacyEventPayloadCodec.decode(text), true),
                    decodeOutcome(() -> EventPayloadCodec.decode(text), false), text);
        }
    }

    private interface Decode {
        ExecutionEvent decode() throws Exception;
    }

    /** The decoded event and the re-encode check verdict, or the payload exception's category and message. */
    private static String decodeOutcome(Decode decode, boolean legacy) {
        try {
            ExecutionEvent event = decode.decode();
            byte[] encoded = legacy ? LegacyEventPayloadCodec.encode(event) : EventPayloadCodec.encode(event);
            return "decoded " + event + " -> " + new String(encoded, StandardCharsets.UTF_8);
        } catch (EventPayloadCodec.PayloadException e) {
            return "rejected " + e.category() + ": " + e.getMessage();
        } catch (LegacyEventPayloadCodec.PayloadException e) {
            return "rejected " + e.category() + ": " + e.getMessage();
        } catch (Exception e) {
            return "threw " + e;
        }
    }

    record Variant(String payload, String form) {
    }

    /** Payloads of {@code event} with each instant token replaced by every listed form. */
    static List<Variant> instantVariants(ExecutionEvent event, int... tokenIndexes) {
        String[] tokens = new String(EventPayloadCodec.encode(event), StandardCharsets.UTF_8).split("\\|", -1);
        List<Variant> variants = new ArrayList<>();
        for (int index : tokenIndexes) {
            for (String form : INSTANT_FORMS) {
                String[] changed = tokens.clone();
                changed[index] = form;
                variants.add(new Variant(String.join("|", changed), form));
            }
        }
        return variants;
    }

    @Test
    void decodeAndCanonicalCheckMatchTheLegacyCodecForEveryInstantForm() {
        List<Variant> variants = new ArrayList<>();
        // envelope occurredAt / recordedAt are tokens 9 and 10; a stage's startedAt / endedAt are 17 and 18
        variants.addAll(instantVariants(Events.stage("e3", Events.ATTEMPT), 9, 10, 17, 18));
        variants.addAll(instantVariants(Events.started("e1"), 9, 10));
        variants.addAll(instantVariants(Events.correctFinish("e8", "e6", Events.ATTEMPT), 9, 10));
        int canonicalCount = 0;
        for (Variant variant : variants) {
            String payload = variant.payload();
            String legacy = decodeOutcome(() -> LegacyEventPayloadCodec.decode(payload), true);
            assertEquals(legacy, decodeOutcome(() -> EventPayloadCodec.decode(payload), false), payload);
            if (legacy.startsWith("decoded ") && legacy.endsWith(" -> " + payload)) {
                canonicalCount++;
                assertEquals(variant.form(), Instant.parse(variant.form()).toString(),
                        "only toString() forms pass the re-encode check");
            }
        }
        assertTrue(canonicalCount > 0);
    }
}
