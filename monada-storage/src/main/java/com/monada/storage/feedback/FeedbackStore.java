package com.monada.storage.feedback;

import java.io.IOException;
import java.util.List;

public interface FeedbackStore {
    String DEFAULT_SEGMENT = "feedback/feedback-000001.log";

    void append(FeedbackEvent event) throws IOException;

    List<FeedbackEvent> findAll() throws IOException;

    List<FeedbackEvent> findByQuery(String query) throws IOException;
}
