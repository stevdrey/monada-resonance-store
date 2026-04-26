# Skill: Storage Development

Use this skill when implementing or modifying storage classes.

## Goal

Keep Monada Resonance Store storage local, inspectable, and reliable for the MVP.

The storage layer persists:

- original Knowledge Atoms;
- Frequency Vectors;
- indexes that map atom IDs to physical locations;
- optional feedback events.

## MVP Disk Layout

```text
monada-memory/
├── manifest.json
├── atoms/
│   └── segment-000001.log
├── vectors/
│   └── segment-000001.f32
├── indexes/
│   ├── atom-offsets.idx
│   └── vector-map.idx
└── feedback/
    └── feedback-000001.log
```

## Design Rules

- Prefer append-only writes for atom and feedback logs.
- Keep atom content separate from vector data.
- Keep vector files fixed-width when possible.
- Make file formats easy to inspect or document.
- Do not introduce an external database for the MVP.
- Do not implement compaction until explicitly requested.

## Java Guidance

Prefer Java NIO:

- `Path`;
- `Files`;
- `FileChannel`;
- `ByteBuffer`;
- `StandardOpenOption`.

Use clear error messages when storage is invalid or corrupted.

## Storage Responsibilities

Storage classes may:

- create directories;
- append atoms;
- read atoms;
- append vectors;
- read vectors;
- load manifest data;
- persist offset/index metadata.

Storage classes must not:

- calculate resonance scores;
- tokenize content;
- encode text;
- decide ranking;
- expose internal mutable byte buffers unsafely.

## Testing Checklist

Add tests for:

- memory directory creation;
- append atom;
- read atom by id or offset;
- append vector;
- read vector by position;
- persistence across reopen;
- corrupted/missing files when relevant.

## MVP Bias

Choose simple and correct over fast.

Optimization comes after the retrieval model is measurable.
