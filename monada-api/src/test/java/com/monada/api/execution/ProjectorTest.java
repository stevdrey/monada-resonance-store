package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ScopeId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectorTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");

    @Test
    void everyProjectionCarriesItsTextAndOnlyLiveAtomsAreKeptForCompaction() {
        Projector p = new Projector();
        p.apply(Experiences.start(SCOPE, "x-1", "Repair the flaky login test"));
        p.apply(Experiences.attempt(SCOPE, "x-1", "a1", 1, null));
        Projector.Step first = p.apply(Experiences.finish(SCOPE, "x-1", "a1", "Waited for the cookie", null));
        String cookie = first.texts().keySet().iterator().next();
        assertEquals(1, first.entries().size());

        p.apply(Experiences.attempt(SCOPE, "x-1", "a2", 2, "a1"));
        Projector.Step same = p.apply(Experiences.finish(SCOPE, "x-1", "a2", "Waited for the cookie", null));
        assertEquals(Set.of(cookie), same.texts().keySet(), "identical text, same atom, a second ref");

        Projector.Step away = p.apply(Experiences.correctFinish(SCOPE, "x-1", "a2", "x-1-a2-fix", "x-1-a2-fin", 2,
                "Mocked the session", null));
        String mocked = away.texts().keySet().iterator().next();
        assertEquals(Set.of(cookie, mocked), p.liveTexts().keySet());

        // Both attempts move away from the cookie text: its atom is retired and no longer kept for a rebuild.
        p.apply(Experiences.correctFinish(SCOPE, "x-1", "a1", "x-1-a1-fix", "x-1-a1-fin", 2, "Mocked the session",
                null));
        assertEquals(Set.of(mocked), p.liveTexts().keySet());
        assertEquals(Set.of(cookie), p.retired());
        assertTrue(p.liveTexts().get(mocked).endsWith("Mocked the session"));
    }

    @Test
    void entriesAreStreamedToTheSinkInOrder() throws java.io.IOException {
        Projector streamed = new Projector();
        Projector collected = new Projector();
        java.util.List<String> seen = new java.util.ArrayList<>();
        java.util.List<com.monada.core.execution.ExecutionEvent> events = new java.util.ArrayList<>();
        events.add(Experiences.start(SCOPE, "x-1", "Repair the flaky login test"));
        String previous = null;
        for (int i = 1; i <= 3; i++) {
            events.add(Experiences.attempt(SCOPE, "x-1", "a" + i, i, previous));
            events.add(Experiences.finish(SCOPE, "x-1", "a" + i, "Waited " + i, null));
            previous = "a" + i;
        }
        events.add(Experiences.correctStart(SCOPE, "x-1", "x-1-retitle", 2, "Repair the flaky signup test"));
        java.util.List<String> expected = new java.util.ArrayList<>();
        for (var event : events) {
            streamed.apply(event, (entry, text) -> {
                assertEquals(entry.kind() == com.monada.storage.execution.ProjectionCheckpoint.EntryKind.PROJECT,
                        text != null, "text only for PROJECT entries");
                seen.add(entry.kind() + ":" + entry.atomId() + ":" + entry.ref().eventId().value());
            });
            collected.apply(event).entries()
                    .forEach(e -> expected.add(e.kind() + ":" + e.atomId() + ":" + e.ref().eventId().value()));
        }
        assertEquals(expected, seen);
        assertEquals(3 + 2 * 3, seen.size(), "3 projections, then a RETIRE + PROJECT per attempt");
        assertTrue(seen.get(3).startsWith("RETIRE:") && seen.get(4).startsWith("PROJECT:"));
    }
}
