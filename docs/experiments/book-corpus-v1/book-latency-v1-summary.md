# Book latency summary (Issue #168)

Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.

Host: AMD Ryzen 7 7730U with Radeon Graphics, 16 processors, OpenJDK 64-Bit Server VM 27+35, GC G1 Young Generation, G1 Concurrent GC, G1 Old Generation, max heap 3832 MiB, scratch btrfs (/dev/nvme0n1p6).

Parity: PASS (2990 top-10 result lists: 2190 compared directly, 800 through 25 child-JVM digests; 0 mismatches).

| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50 | fresh query p50 / p95 µs (n, repeated) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 25% | REAL | 21 | 36.2 | 62.1 | 30.71 | 675.7 | 1188.6 | 1182.5 | 185.4 / 248.3 / 303.0 (320) | 79853.2 / 67288.0 | 660.9 / 1402.1 (124) |
| 50% | REAL | 43 | 73.5 | 125.4 | 32.47 | 1312.7 | 847.4 | 902.6 | 167.0 / 209.6 / 245.8 (320) | 89346.0 / 64409.7 | 826.8 / 2091.5 (124) |
| 100% | REAL | 87 | 147.3 | 251.2 | 62.30 | 1401.1 | 599.0 | 1271.7 | 214.8 / 237.1 / 255.5 (320) | 68190.1 / 61822.0 | 1097.6 / 2276.0 (124) |
| x4 | DERIVED | 348 | 592.5 | 1006.7 | 242.74 | 1437.0 | 669.6 | 3275.1 | 1010.7 / 1309.9 / 1624.3 (320) | 76271.9 / 64893.2 | 1993.4 / 3240.1 (124) |
| x16 | DERIVED | 1392 | 2373.4 | 4029.9 | 904.94 | 1526.5 | 919.5 | 13812.1 | 3078.1 / 4557.2 / 5618.2 (320) | 67703.4 / 73186.3 | 7894.8 / 13016.0 (124) |

REAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real vocabulary and length, not real-book shape). The machine-readable report holds every series.
