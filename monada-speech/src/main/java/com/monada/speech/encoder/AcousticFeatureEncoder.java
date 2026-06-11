package com.monada.speech.encoder;

import com.monada.core.FrequencyVector;

import java.io.IOException;
import java.nio.file.Path;

public interface AcousticFeatureEncoder {

    FrequencyVector encode(Path audioPath) throws IOException;
}
