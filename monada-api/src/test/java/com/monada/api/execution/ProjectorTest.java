package com.monada.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.monada.core.execution.ScopeId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectorTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");

    @Test
    void onlyFirstProjectionsOfAnAtomCarryTextToStore() {
        Projector p = new Projector();
        p.apply(Experiences.start(SCOPE, "x-1", "Repair the flaky login test"));
        p.apply(Experiences.attempt(SCOPE, "x-1", "a1", 1, null));
        Projector.Step first = p.apply(Experiences.finish(SCOPE, "x-1", "a1", "Waited for the cookie", null));
        assertEquals(1, first.texts().size());
        assertEquals(1, first.entries().size());

        p.apply(Experiences.attempt(SCOPE, "x-1", "a2", 2, "a1"));
        Projector.Step same = p.apply(Experiences.finish(SCOPE, "x-1", "a2", "Waited for the cookie", null));
        assertEquals(Map.of(), same.texts(), "identical text: the atom is already stored");
        assertEquals(1, same.entries().size(), "but a second ref is still projected");

        // Correcting the finish away and back: the original atom was projected before, so nothing to store.
        Projector.Step away = p.apply(Experiences.correctFinish(SCOPE, "x-1", "a2", "x-1-a2-fix", "x-1-a2-fin", 2,
                "Mocked the session", null));
        assertEquals(1, away.texts().size());
        Projector.Step back = p.apply(Experiences.correctFinish(SCOPE, "x-1", "a2", "x-1-a2-fix2", "x-1-a2-fix", 3,
                "Waited for the cookie", null));
        assertEquals(Map.of(), back.texts());
        assertEquals(2, back.entries().size(), "retire + project");
    }
}
