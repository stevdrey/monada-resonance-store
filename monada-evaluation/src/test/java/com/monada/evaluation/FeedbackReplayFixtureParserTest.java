package com.monada.evaluation;

import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackReplayFixtureParserTest {

    private static final String VALID_ROW =
            "query text\tquery text\tka_target\tPOSITIVE\t0.05\t2026-01-01T00:00:00Z"
                    + "\tMATCHING_EVALUATION_QUERY";

    @Test
    void parsesCommentsBlankLinesAndRepeatedEventsInFileOrder() throws IOException {
        var events = parse("# fixture\n\n" + VALID_ROW + "\n" + VALID_ROW + "\n");

        var expected = new FeedbackReplayEvent(
                "query text",
                "query text",
                "ka_target",
                FeedbackSignal.POSITIVE,
                0.05,
                Instant.parse("2026-01-01T00:00:00Z"),
                FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY);
        assertEquals(List.of(expected, expected), events);
    }

    @Test
    void parsesNegativeSignalAndUnmatchedScopeCaseInsensitively() throws IOException {
        var events = parse(
                "other query\tunmatched:key\tka_target\tnegative\t-0.25"
                        + "\t2026-01-01T00:00:01Z\tunmatched_evaluation_query\n");

        assertEquals(FeedbackSignal.NEGATIVE, events.getFirst().signal());
        assertEquals(FeedbackReplayExpectedScope.UNMATCHED_EVALUATION_QUERY,
                events.getFirst().expectedScope());
    }

    @Test
    void rejectsWrongFieldCountAndBlankFields() {
        assertInvalid("query\tkey\tlabel\n", "exactly 7");
        assertInvalid("query\t\tlabel\tPOSITIVE\t0.05\t2026-01-01T00:00:00Z"
                + "\tMATCHING_EVALUATION_QUERY\n", "blank field");
    }

    @Test
    void rejectsInvalidSignalDeltaTimestampAndScope() {
        assertInvalid(replaceField(VALID_ROW, 3, "NEUTRAL"), "line 1");
        assertInvalid(replaceField(VALID_ROW, 4, "NaN"), "line 1");
        assertInvalid(replaceField(VALID_ROW, 4, "-0.05"), "line 1");
        assertInvalid(replaceField(VALID_ROW, 5, "not-an-instant"), "line 1");
        assertInvalid(replaceField(VALID_ROW, 6, "GLOBAL"), "line 1");
    }

    @Test
    void rejectsFixtureWithoutEvents() {
        assertInvalid("# comments only\n\n", "contains no feedback replay events");
    }

    private List<FeedbackReplayEvent> parse(String content) throws IOException {
        return new FeedbackReplayFixtureParser(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "test fixture").parse();
    }

    private void assertInvalid(String content, String expectedMessage) {
        IOException exception = assertThrows(IOException.class, () -> parse(content));
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
    }

    private String replaceField(String row, int index, String replacement) {
        String[] fields = row.split("\\t", -1);
        fields[index] = replacement;
        return String.join("\t", fields) + "\n";
    }
}
