package com.monada.storage;

import com.monada.core.FrequencyVector;

public record StoredVector(String atomId, FrequencyVector vector) {}
