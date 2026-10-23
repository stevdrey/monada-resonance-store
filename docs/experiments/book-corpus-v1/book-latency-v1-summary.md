# Book latency summary (Issue #168)

Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.

Host: AMD Ryzen 7 7730U with Radeon Graphics, 16 processors, OpenJDK 64-Bit Server VM 27+35, GC G1 Young Generation, G1 Concurrent GC, G1 Old Generation, max heap 3832 MiB, scratch btrfs (/dev/nvme0n1p6).

Parity: PASS (2990 top-10 result lists: 2190 compared directly, 800 through 25 child-JVM digests; 0 mismatches).

| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50 | fresh query p50 / p95 µs (n, repeated) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 25% | REAL | 21 | 36.2 | 62.1 | 28.01 | 722.7 | 1303.1 | 1072.9 | 176.1 / 250.2 / 279.3 (320) | 79899.0 / 65573.0 | 681.7 / 1517.8 (124) |
| 50% | REAL | 43 | 73.5 | 125.4 | 43.92 | 974.6 | 930.0 | 1036.9 | 189.8 / 300.4 / 397.3 (320) | 87441.7 / 80303.1 | 1110.8 / 2560.7 (124) |
| 100% | REAL | 87 | 147.3 | 251.2 | 69.91 | 1251.3 | 953.7 | 1576.8 | 243.0 / 359.9 / 454.7 (320) | 82471.1 / 68038.5 | 1095.2 / 2428.8 (124) |
| x4 | DERIVED | 348 | 592.5 | 1006.7 | 238.69 | 1441.1 | 802.9 | 3720.5 | 1099.2 / 1436.6 / 1628.3 (320) | 70976.9 / 69343.8 | 2228.4 / 3731.5 (124) |
| x16 | DERIVED | 1392 | 2373.4 | 4029.9 | 923.11 | 1496.8 | 983.2 | 14297.4 | 2963.0 / 4475.0 / 5059.4 (320) | 68695.6 / 72878.3 | 6813.4 / 9958.9 (124) |

REAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real vocabulary and length, not real-book shape). The machine-readable report holds every series.
