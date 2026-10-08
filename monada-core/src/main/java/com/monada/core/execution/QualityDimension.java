package com.monada.core.execution;

/** Quality dimension of an observation. Dimensions are reported individually, never combined into a score. */
public enum QualityDimension {
    CORRECTNESS, TESTS, SECURITY, ARCHITECTURE, MAINTAINABILITY, COMPLEXITY
}
