package com.monada.storage;

import java.io.IOException;
import java.util.Optional;

public interface ManifestStore {
    Optional<Manifest> load() throws IOException;

    void save(Manifest manifest) throws IOException;
}
