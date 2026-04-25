package com.monada.storage;

import java.io.IOException;

public interface ManifestStore {
    void initialize() throws IOException;
}
