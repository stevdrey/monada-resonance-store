package com.monada.index;

import com.monada.core.FrequencyVector;
import com.monada.core.ResonanceResult;

import java.io.IOException;
import java.util.List;

public interface ResonanceIndex {
    List<ResonanceResult> search(FrequencyVector queryVector, int topK, double threshold) throws IOException;
}
