# Library Consumption Contract (Issue #94)

## Purpose

Monada Forge and the existing Monada Neuron bridge consume Monada Resonance Store as a **local embedded
Java 27 library**: versioned Maven artifacts, no copied sources, no service. This document records the
published coordinates, the dependency graph, the JPMS module names and the exact verification commands.

Ownership boundaries are unchanged (see [ADR 0002](../adr/0002-memory-owned-by-resonance-store.md) and
[execution-memory-contract-v1](execution-memory-contract-v1.md)). Publication does not change retrieval,
storage, ranking or feedback behavior.

## Published Artifacts

All artifacts use group `com.monada` and the current project version (`0.1.0-SNAPSHOT`). Each publication
contains the binary jar, a `-sources.jar`, a Maven POM and Gradle Module Metadata (`.module`). Jars embed
`META-INF/LICENSE` and POMs declare the Apache License 2.0.

| Artifact | Automatic-Module-Name | Packages | Depends on (`api` = exposed to consumers) |
| --- | --- | --- | --- |
| `com.monada:monada-core` | `com.monada.core` | `com.monada.core`, `.execution` | — |
| `com.monada:monada-encoder` | `com.monada.encoder` | `com.monada.encoder` (+ `lexical/en` resources) | `api` core |
| `com.monada:monada-storage` | `com.monada.storage` | `com.monada.storage`, `.audit`, `.feedback`, `.execution` | `api` core |
| `com.monada:monada-index` | `com.monada.index` | `com.monada.index` | `api` core, storage |
| `com.monada:monada-learning` | `com.monada.learning` | none yet (placeholder jar) | `implementation` core |
| `com.monada:monada-api` | `com.monada.api` | `com.monada.api`, `.execution` | `api` core, encoder, storage; `implementation` index, learning |

`api` vs `implementation` follows an audit of public signatures:

- `MonadaMemory` / `MonadaMemoryOptions` expose `KnowledgeAtom`, `MonadaRecall` (core),
  `TextNormalizer`, `LexicalExpansionOptions`, `LexicalEnrichmentPipeline` (encoder),
  `EncodingProfile` and `FeedbackSignal` (storage, via the feedback API).
- `MonadaQuery` constructors are package-private, so `ResonanceIndex` is not part of the API surface;
  index and learning are runtime-only for API consumers.
- Index signatures expose storage stores (`AtomStore`, `FrequencyStore`, `FeedbackStore`) and core types.

Consumers declare **only** `com.monada:monada-api`. They see api/core/encoder/storage at compile time and
the full six-artifact graph at runtime. No third-party dependency is required.

**Not published:** `monada-evaluation` and `monada-speech`. They are never forced on library consumers.
No preview, incubator or `--add-opens` flags are required.

## Consumer Examples

Gradle (class path):

```kotlin
dependencies {
    implementation("com.monada:monada-api:0.1.0-SNAPSHOT")
}
```

JPMS (`module-info.java`); require only the modules whose types your code uses:

```java
module my.consumer {
    requires com.monada.api;
    requires com.monada.core;     // KnowledgeAtom, MonadaRecall
    requires com.monada.encoder;  // TextNormalizer, LexicalExpansionOptions (if used)
    requires com.monada.storage;  // FeedbackSignal (if used)
}
```

```java
MonadaMemory memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
KnowledgeAtom atom = memory.remember("OrientDB is a multi-model database ...");
MonadaRecall recall = memory.resonate("graph and document database").topK(3).execute();
memory.feedback("graph and document database", atom.id(), FeedbackSignal.POSITIVE);
MonadaMemory reopened = MonadaMemory.open(root); // persisted atoms, vectors and feedback reload
```

## Verification Commands

```bash
./gradlew test
./gradlew publishLibrariesToVerificationRepository
./gradlew verifyLibraryConsumer
```

- `publishLibrariesToVerificationRepository` deletes and recreates the temporary local Maven repository
  `build/verification-repo` and publishes the six artifacts into it. No remote registry or credential is
  configured anywhere in the build.
- `verifyLibraryConsumer` publishes first, then runs the isolated build in
  [`integration-tests/library-consumer`](../../integration-tests/library-consumer) with
  `verifyConsumer`. That build has its own `settings.gradle.kts`, no `includeBuild`, no `project(...)`
  substitution and no manually supplied jars. Its **only** repository is the verification repository, so
  any undeclared or third-party dependency fails resolution.

The consumer build can also be run directly after publishing:

```bash
./gradlew -p integration-tests/library-consumer verifyConsumer -PmonadaRepo=$PWD/build/verification-repo -PmonadaVersion=0.1.0-SNAPSHOT
```

`verifyConsumer` runs, for both `classpath-consumer` and `module-consumer`:

1. `printResolution`: prints the resolved components and fails unless `compileClasspath` is exactly
   `{api, core, encoder, storage}` and `runtimeClasspath` is exactly the six `com.monada` artifacts at the
   published version.
2. `run`: open → remember → recall → feedback → reopen → recall in a temporary directory, plus an
   invalid-argument error path (`topK(0)`). After reopen it asserts that the same atom is still top-1 and
   that its score is strictly higher than before the POSITIVE feedback, proving the feedback event was
   persisted and reloaded (without feedback the reopened score is identical). It prints `java.class.path` / `jdk.module.path` and the
   runtime module of each exposed type. The JPMS consumer asserts the named modules `com.monada.api`,
   `com.monada.core`, `com.monada.encoder`, `com.monada.storage` and that `com.monada.index` and
   `com.monada.learning` are resolved in the boot layer; the class-path consumer asserts the unnamed module.
3. Execution history (Issue #97): in a separate directory, `ExecutionMemory` open → record → identical retry
   (`IDEMPOTENT`) → history → close → reopen → load execution. It uses only `monada-api`, `monada-core` and
   `monada-storage` types, so the compile classpath above is unchanged.

## Reproducibility

Archives are built without file timestamps and with reproducible entry order. Republishing produces
byte-identical jars and sources jars; only the Maven SNAPSHOT timestamp in file names and
`maven-metadata.xml` changes.

## Compatibility

- No production source, storage schema, ranking or default changed. `ExactQueryKeyStrategy` remains the
  default. The protected `:monada-evaluation:run` output is unchanged.
- Switching selected dependencies from `implementation` to `api` only adds compile-time visibility to
  projects that already depended on those modules (evaluation, speech tests).
- `./gradlew :monada-api:run` (CLI demo) and `:monada-storage:runIntegrityAudit` keep working.

## Limits

- Version is `0.1.0-SNAPSHOT`; no release version is cut and nothing is published remotely.
- Modules are **automatic modules** (stable `Automatic-Module-Name`), not full `module-info` descriptors.
  Automatic modules export and open all packages; a future explicit descriptor may narrow that surface.
  The module names above are the stable contract.
- `monada-learning` currently has no sources; it is published to keep the production graph and module
  name stable.
- The `monada-api` jar still contains the demo `com.monada.api.Main` entry point.
- `MonadaMemory` has no `close()`; lifecycle remains caller-owned through the store directory.
- `ExecutionMemory` (`com.monada.api.execution`) is `AutoCloseable` and holds an exclusive writer lock on its
  root until closed (single writer, one scope per instance; see section 17 of the execution memory contract).
- No Javadoc jar is published.

## Future Work

- Actual Forge and Neuron integration against these coordinates (separate track issues; not authorized here).
- Remote publication, signing and release versioning.
- Explicit JPMS descriptors once package exports are designed deliberately.
