# Book latency summary (Issue #168)

Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.

Host: AMD Ryzen 7 7730U with Radeon Graphics, 16 processors, OpenJDK 64-Bit Server VM 27+35, GC G1 Young Generation, G1 Concurrent GC, G1 Old Generation, max heap 3832 MiB, scratch btrfs (/dev/nvme0n1p6).

Parity: PASS (2215 top-10 comparisons, 0 mismatches).

| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50 | fresh query p50 / p95 µs (n, repeated) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 25% | REAL | 21 | 36.2 | 62.1 | 26.53 | 733.4 | 1237.9 | 1505.1 | 208.6 / 294.6 / 378.2 (320) | 87139.0 / 65129.2 | 705.9 / 1351.6 (124) |
| 50% | REAL | 43 | 73.5 | 125.4 | 34.68 | 1229.8 | 632.5 | 801.6 | 175.6 / 204.4 / 221.5 (320) | 81310.0 / 67260.5 | 925.5 / 2133.0 (124) |
| 100% | REAL | 87 | 147.3 | 251.2 | 66.59 | 1305.3 | 625.6 | 1311.6 | 231.7 / 259.0 / 286.1 (320) | 71288.6 / 62918.4 | 1103.9 / 2273.7 (124) |
| x4 | DERIVED | 348 | 592.5 | 1006.7 | 259.73 | 1355.8 | 998.3 | 4069.2 | 840.1 / 1212.5 / 1450.9 (320) | 73778.1 / 73567.7 | 2269.1 / 4976.1 (124) |
| x16 | DERIVED | 1392 | 2373.4 | 4029.9 | 993.90 | 1307.4 | 1432.1 | 18669.4 | 3003.3 / 4485.6 / 4952.4 (320) | 73681.1 / 75751.0 | 7070.8 / 11604.8 (124) |

REAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real vocabulary and length, not real-book shape). The machine-readable report holds every series.
