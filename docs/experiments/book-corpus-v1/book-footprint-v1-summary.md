# book-footprint-v1 summary

Checks: **PASS** (204 / 204 passed).

## Disk, per phase

Bytes. `atoms` and `total` vary by a few bytes per record across runs (creation timestamps).

| Store | Phase | Source text bytes | Unique chunks | Atoms (unique/physical) | Dim | manifest | atoms | vectors | vectorIndex | feedback | total | B/atom | B/text byte | Append amp. | Unchanged |
| --- | --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --- |
| 25% REAL | EMPTY | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | — |
| 25% REAL | FRESH_IMPORT | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | 1.713 | no |
| 25% REAL | REOPEN | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | n/a | yes |
| 25% REAL | REPEAT_IMPORT | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | n/a | yes |
| 50% REAL | EMPTY | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | — |
| 50% REAL | FRESH_IMPORT | 75233 | 43 | 43/43 | 128 | 766 | 103754 | 22016 | 1825 | 0 | 128361 | 2985.140 | 1.706 | 1.706 | no |
| 50% REAL | REOPEN | 75233 | 43 | 43/43 | 128 | 766 | 103754 | 22016 | 1825 | 0 | 128361 | 2985.140 | 1.706 | n/a | yes |
| 50% REAL | REPEAT_IMPORT | 75233 | 43 | 43/43 | 128 | 766 | 103754 | 22016 | 1825 | 0 | 128361 | 2985.140 | 1.706 | n/a | yes |
| 100% REAL | EMPTY | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | — |
| 100% REAL | FRESH_IMPORT | 150885 | 87 | 87/87 | 128 | 766 | 208150 | 44544 | 3717 | 0 | 257177 | 2956.057 | 1.704 | 1.704 | no |
| 100% REAL | REOPEN | 150885 | 87 | 87/87 | 128 | 766 | 208150 | 44544 | 3717 | 0 | 257177 | 2956.057 | 1.704 | n/a | yes |
| 100% REAL | REPEAT_IMPORT | 150885 | 87 | 87/87 | 128 | 766 | 208150 | 44544 | 3717 | 0 | 257177 | 2956.057 | 1.704 | n/a | yes |
| x4 DERIVED | EMPTY | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | — |
| x4 DERIVED | FRESH_IMPORT | 606672 | 348 | 348/348 | 128 | 766 | 836785 | 178176 | 15092 | 0 | 1030819 | 2962.124 | 1.699 | 1.699 | no |
| x4 DERIVED | REOPEN | 606672 | 348 | 348/348 | 128 | 766 | 836785 | 178176 | 15092 | 0 | 1030819 | 2962.124 | 1.699 | n/a | yes |
| x4 DERIVED | REPEAT_IMPORT | 606672 | 348 | 348/348 | 128 | 766 | 836785 | 178176 | 15092 | 0 | 1030819 | 2962.124 | 1.699 | n/a | yes |
| x16 DERIVED | EMPTY | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | — |
| x16 DERIVED | FRESH_IMPORT | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352162 | 712704 | 61028 | 0 | 4126660 | 2964.555 | 1.698 | 1.698 | no |
| x16 DERIVED | REOPEN | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352162 | 712704 | 61028 | 0 | 4126660 | 2964.555 | 1.698 | n/a | yes |
| x16 DERIVED | REPEAT_IMPORT | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352162 | 712704 | 61028 | 0 | 4126660 | 2964.555 | 1.698 | n/a | yes |
| OVERLAP | IMPORT_50% | 75233 | 43 | 43/43 | 128 | 766 | 103757 | 22016 | 1825 | 0 | 128364 | 2985.209 | 1.706 | n/a | — |
| OVERLAP | IMPORT_100% | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | 1.703 | no |
| INTRA_BATCH_DUPLICATES | CLEAN_ONCE | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | n/a | — |
| INTRA_BATCH_DUPLICATES | EVERY_CHUNK_TWICE | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | n/a | — |

## Memory, per phase (informational; host-specific)

Heap and RSS are different measures and are not interchangeable. RSS peak is the process-lifetime high-water mark. Allocation is this thread's only.

| Store | Phase | Heap used MiB | Heap committed MiB | Heap pool-peak sum MiB | Heap after GC MiB | Thread alloc MiB | RSS MiB | RSS peak MiB |
| --- | --- | --: | --: | --: | --: | --: | --: | --: |
| 25% | FRESH_IMPORT | 10.4 | 36.0 | 19.1 | 3.1 | 4.8 | 82.8 | 82.8 |
| 25% | REOPEN | 9.1 | 36.0 | 9.1 | 3.3 | 2.0 | 86.8 | 86.8 |
| 25% | REPEAT_IMPORT | 3.3 | 36.0 | 3.3 | 3.3 | 0.9 | 87.1 | 87.1 |
| 50% | FRESH_IMPORT | 11.3 | 36.0 | 11.3 | 3.3 | 6.8 | 88.0 | 88.0 |
| 50% | REOPEN | 9.3 | 36.0 | 9.3 | 3.3 | 1.5 | 88.4 | 88.4 |
| 50% | REPEAT_IMPORT | 3.3 | 36.0 | 3.3 | 3.3 | 1.6 | 88.7 | 88.7 |
| 100% | FRESH_IMPORT | 5.7 | 36.0 | 17.7 | 3.3 | 13.6 | 89.4 | 89.4 |
| 100% | REOPEN | 19.3 | 36.0 | 19.3 | 3.3 | 2.7 | 92.8 | 92.8 |
| 100% | REPEAT_IMPORT | 5.3 | 36.0 | 5.3 | 3.3 | 3.0 | 93.2 | 93.2 |
| x4 | FRESH_IMPORT | 15.3 | 36.0 | 25.3 | 4.2 | 54.3 | 102.1 | 102.1 |
| x4 | REOPEN | 15.6 | 36.0 | 15.6 | 4.3 | 10.0 | 103.4 | 103.4 |
| x4 | REPEAT_IMPORT | 14.3 | 36.0 | 14.3 | 4.3 | 11.7 | 103.5 | 103.5 |
| x16 | FRESH_IMPORT | 28.5 | 108.0 | 76.5 | 8.8 | 215.7 | 173.5 | 173.5 |
| x16 | REOPEN | 77.5 | 108.0 | 77.5 | 8.9 | 39.1 | 178.0 | 178.0 |
| x16 | REPEAT_IMPORT | 54.9 | 108.0 | 54.9 | 8.9 | 46.1 | 182.7 | 182.7 |
| OVERLAP | IMPORT_50% | 11.6 | 108.0 | 81.6 | 8.7 | 6.8 | 183.7 | 183.7 |
| OVERLAP | IMPORT_100% | 18.7 | 108.0 | 18.7 | 8.7 | 8.3 | 183.7 | 183.7 |
| INTRA_BATCH_DUPLICATES | CLEAN_ONCE | 30.7 | 108.0 | 30.7 | 8.7 | 13.5 | 183.7 | 183.7 |
| INTRA_BATCH_DUPLICATES | EVERY_CHUNK_TWICE | 26.7 | 108.0 | 26.7 | 8.7 | 14.1 | 183.8 | 183.8 |

Retained heap of one reopened, queried instance (APPROXIMATE, after advisory GC): 25% 0.1 MiB, 50% 0.2 MiB, 100% 0.3 MiB, x4 1.2 MiB, x16 4.7 MiB.
