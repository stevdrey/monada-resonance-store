package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ExecutionEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EventPayloadCodecTest {

    @Test
    void everyKindRoundTripsExactlyAndEncodingIsDeterministic() throws Exception {
        for (ExecutionEvent event : Events.fullRun()) {
            byte[] once = EventPayloadCodec.encode(event);
            assertArrayEquals(once, EventPayloadCodec.encode(event));
            String text = new String(once, StandardCharsets.UTF_8);
            assertFalse(text.contains("\t") || text.contains("\n") || text.contains("\r"), text);
            assertEquals(event, EventPayloadCodec.decode(text));
        }
    }

    @Test
    void goldenPayloadPinsFieldOrderAndEscaping() {
        String payload = new String(EventPayloadCodec.encode(Events.started("e1")), StandardCharsets.UTF_8);
        assertEquals("1|EXECUTION_STARTED|e1|scope-1|task-1|exec-1||1||2026-01-01T00:00:00Z|2026-01-01T00:00:00Z"
                + "|Fix \\p the\\tbug\\n\\\\ ünï 😀 \\x0001 ok|rev-1|ctx-1|constraints-1|policy|2|2"
                + "|CORRECTNESS|TESTS", payload);
    }

    @Test
    void unicodeAndStructuralCharactersSurvive() throws Exception {
        String tricky = "a|b\\c\td\ne\rf \u0000   😀 é \\p \\n";
        ExecutionEvent event = Events.started("e1", Events.SCOPE, Events.EXEC, tricky);
        String text = new String(EventPayloadCodec.encode(event), StandardCharsets.UTF_8);
        assertEquals(event, EventPayloadCodec.decode(text));
    }

    @Test
    void strictDecodingRejectsExtraMissingAndBadTokens() {
        String good = new String(EventPayloadCodec.encode(Events.finished("e6", Events.ATTEMPT)),
                StandardCharsets.UTF_8);
        for (String bad : new String[] {good + "|extra", good.substring(0, good.lastIndexOf('|')),
                good.replace("COMPLETED", "NOPE"), good.replace("|~solved", "|solved"),
                good.replace("lesson \\p learned", "lesson \\q learned")}) {
            var e = assertThrows(EventPayloadCodec.PayloadException.class, () -> EventPayloadCodec.decode(bad), bad);
            assertEquals(LedgerDiagnosticCategory.MALFORMED_RECORD, e.category());
        }
        var future = assertThrows(EventPayloadCodec.PayloadException.class,
                () -> EventPayloadCodec.decode("7|ANYTHING"));
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA, future.category());
        assertTrue(future.getMessage().contains("7"));
    }
}
