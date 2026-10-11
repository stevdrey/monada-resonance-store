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
}
