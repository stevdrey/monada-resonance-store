# Book latency summary (Issue #168)

Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.

Host: AMD Ryzen 7 7730U with Radeon Graphics, 16 processors, OpenJDK 64-Bit Server VM 27+35, GC G1 Young Generation, G1 Concurrent GC, G1 Old Generation, max heap 3832 MiB, scratch btrfs (/dev/nvme0n1p6).

Parity: PASS (2215 top-10 comparisons, 0 mismatches).

| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50 | fresh query p50 / p95 µs (n, repeated) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 25% | REAL | 21 | 36.2 | 62.1 | 33.41 | 641.8 | 1266.3 | 1412.3 | 248.9 / 329.2 / 398.4 (320) | 74971.5 / 62936.4 | 659.5 / 1295.6 (124) |
| 50% | REAL | 43 | 73.5 | 125.4 | 39.68 | 1073.0 | 782.7 | 1007.2 | 185.9 / 218.6 / 259.5 (320) | 75362.8 / 67140.2 | 968.9 / 2158.1 (124) |
| 100% | REAL | 87 | 147.3 | 251.2 | 70.07 | 1207.0 | 745.5 | 1497.7 | 261.0 / 287.9 / 317.7 (320) | 89291.3 / 67419.1 | 1115.9 / 2369.8 (124) |
| x4 | DERIVED | 348 | 592.5 | 1006.7 | 365.63 | 922.2 | 1125.3 | 4856.5 | 972.5 / 1380.5 / 2446.3 (320) | 80510.3 / 74194.2 | 2533.0 / 4709.5 (124) |
| x16 | DERIVED | 1392 | 2373.4 | 4029.9 | 961.69 | 1425.7 | 928.0 | 13194.4 | 2800.0 / 5051.1 / 5583.8 (320) | 89140.2 / 73970.2 | 6717.3 / 10892.0 (124) |

REAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real vocabulary and length, not real-book shape). The machine-readable report holds every series.
