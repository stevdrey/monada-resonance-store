package com.monada.storage.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileFeedbackStoreTest {

    @TempDir
    Path root;

    @Test
    void appendAndFindAllRoundTrip() throws IOException {
        var store = new FileFeedbackStore(root);
        var e1 = new FeedbackEvent("q1", "ka_a", FeedbackSignal.POSITIVE, 0.05,
                Instant.parse("2026-05-01T00:00:00Z"));
        var e2 = new FeedbackEvent("q1", "ka_b", FeedbackSignal.NEGATIVE, -0.1,
                Instant.parse("2026-05-01T00:00:01Z"));
        store.append(e1);
        store.append(e2);

        List<FeedbackEvent> all = store.findAll();
        assertEquals(List.of(e1, e2), all);
        assertTrue(Files.isRegularFile(root.resolve(FileFeedbackStore.DEFAULT_SEGMENT)));
    }

    @Test
    void findByQueryFiltersEvents() throws IOException {
        var store = new FileFeedbackStore(root);
        var a = new FeedbackEvent("alpha", "ka_1", FeedbackSignal.POSITIVE, 0.05, Instant.EPOCH);
        var b = new FeedbackEvent("beta", "ka_2", FeedbackSignal.NEGATIVE, -0.05, Instant.EPOCH);
        var c = new FeedbackEvent("alpha", "ka_3", FeedbackSignal.POSITIVE, 0.05, Instant.EPOCH);
        store.append(a);
        store.append(b);
        store.append(c);

        assertEquals(List.of(a, c), store.findByQuery("alpha"));
        assertEquals(List.of(b), store.findByQuery("beta"));
        assertEquals(List.of(), store.findByQuery("missing"));
    }

    @Test
    void escapesQuotesAndBackslashesOnRoundTrip() throws IOException {
        var store = new FileFeedbackStore(root);
        var event = new FeedbackEvent(
                "query with \"quotes\" and \\ backslash",
                "ka_\"escaped\"",
                FeedbackSignal.POSITIVE,
                0.25,
                Instant.parse("2026-05-01T00:00:00Z"));
        store.append(event);
        assertEquals(List.of(event), store.findAll());
    }

    @Test
    void blankLinesAreTolerated() throws IOException {
        var store = new FileFeedbackStore(root);
        store.append(new FeedbackEvent("q", "a", FeedbackSignal.POSITIVE, 0.05, Instant.EPOCH));
        Path logFile = root.resolve(FileFeedbackStore.DEFAULT_SEGMENT);
        Files.writeString(logFile,
                Files.readString(logFile, StandardCharsets.UTF_8) + "\n\n",
                StandardCharsets.UTF_8);
        assertEquals(1, store.findAll().size());
    }

    @Test
    void rejectsInvalidEventFields() {
        assertThrows(NullPointerException.class,
                () -> new FeedbackEvent(null, "a", FeedbackSignal.POSITIVE, 0.05, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new FeedbackEvent("q", "", FeedbackSignal.POSITIVE, 0.05, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new FeedbackEvent("q", "a", FeedbackSignal.POSITIVE, Double.NaN, Instant.EPOCH));
    }
}
