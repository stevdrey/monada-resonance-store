# Book latency summary (Issue #168)

Exploratory, one host, informational only. Page cache: warm or unknown (never dropped); no row is an OS cold-cache measurement. Percentiles are nearest-rank; `n/a` means too few samples.

Host: AMD Ryzen 7 7730U with Radeon Graphics, 16 processors, OpenJDK 64-Bit Server VM 27+35, GC G1 Young Generation, G1 Concurrent GC, G1 Old Generation, max heap 3832 MiB, scratch btrfs (/dev/nvme0n1p6).

Parity: PASS (2990 top-10 result lists: 2190 compared directly, 800 through 25 child-JVM digests; 0 mismatches).

| scale | kind | chunks | text KiB | store KiB | ingest p50 ms | ingest chunks/s | open p50 µs (in-process) | first query p50 µs | query p50 / p95 / p99 µs (n) | fresh open µs first / repeated p50 | fresh query p50 / p95 µs (n, repeated) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 25% | REAL | 21 | 36.2 | 62.1 | 27.12 | 715.2 | 1166.3 | 1219.7 | 231.3 / 344.5 / 461.8 (320) | 90471.5 / 68294.3 | 771.1 / 1516.7 (124) |
| 50% | REAL | 43 | 73.5 | 125.4 | 37.19 | 1146.5 | 859.5 | 982.3 | 181.6 / 234.6 / 291.8 (320) | 84035.6 / 72984.1 | 874.3 / 2446.9 (124) |
| 100% | REAL | 87 | 147.3 | 251.2 | 68.12 | 1278.7 | 738.4 | 1500.7 | 229.6 / 268.8 / 368.7 (320) | 70543.4 / 67556.5 | 1119.7 / 2416.9 (124) |
| x4 | DERIVED | 348 | 592.5 | 1006.7 | 251.69 | 1377.1 | 1042.0 | 4407.0 | 858.6 / 1090.4 / 2381.2 (320) | 85181.1 / 72922.9 | 2426.7 / 4055.4 (124) |
| x16 | DERIVED | 1392 | 2373.4 | 4029.9 | 970.94 | 1417.3 | 1173.2 | 16616.3 | 3053.6 / 3968.0 / 5695.7 (320) | 75841.3 / 78422.8 | 7098.7 / 11382.9 (124) |

REAL rows ingest the frozen chunks unmodified; DERIVED rows add replica-tagged copies (real vocabulary and length, not real-book shape). The machine-readable report holds every series.
