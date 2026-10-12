package com.monada.api.execution;

import com.monada.core.execution.ExecutionEvent;
import com.monada.storage.execution.ProjectionCheckpoint.Entry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test helper: collects the entries a {@link Projector} streams for one event (production never materializes them). */
final class ProjectorSteps {
    /** The entries of one event and the text of every atom it projects, by atom ID. */
    record Step(List<Entry> entries, Map<String, String> texts) {
    }

    private ProjectorSteps() {
    }

    static Step apply(Projector projector, ExecutionEvent event) {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        try {
            projector.apply(event, (entry, text) -> {
                entries.add(entry);
                if (text != null) {
                    texts.put(entry.atomId(), text);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Step(List.copyOf(entries), Collections.unmodifiableMap(texts));
    }
}
