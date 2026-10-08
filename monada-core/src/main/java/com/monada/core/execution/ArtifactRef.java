package com.monada.core.execution;

import java.util.Objects;
import java.util.Optional;

/** Opaque pointer to caller-held evidence. Never dereferenced by the library. */
public record ArtifactRef(String kind, String reference, Optional<String> digest) {
    public ArtifactRef {
        kind = Validation.opaque(kind, "artifact kind");
        reference = Validation.opaque(reference, "artifact reference");
        Objects.requireNonNull(digest, "digest");
        digest = digest.map(d -> Validation.opaque(d, "artifact digest"));
    }

    public static ArtifactRef of(String kind, String reference) {
        return new ArtifactRef(kind, reference, Optional.empty());
    }

    public static ArtifactRef of(String kind, String reference, String digest) {
        return new ArtifactRef(kind, reference, Optional.of(digest));
    }
}
