# book-footprint-v1 summary

Checks: **PASS** (204 / 204 passed).

## Disk, per phase

Bytes. `atoms` and `total` vary by a few bytes per record across runs (creation timestamps).

| Store | Phase | Input text bytes | Unique text bytes | Unique chunks | Atoms (unique/physical) | Dim | manifest | atoms | vectors | vectorIndex | feedback | total | B/atom | B/text byte | Append amp. | Unchanged |
| --- | --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --- |
| 25% REAL | EMPTY | 0 | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | n/a |
| 25% REAL | FRESH_IMPORT | 37101 | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | 1.713 | no |
| 25% REAL | REOPEN | 0 | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | n/a (no new text, 0 bytes appended) | yes |
| 25% REAL | REPEAT_IMPORT | 37101 | 37101 | 21 | 21/21 | 128 | 766 | 51159 | 10752 | 879 | 0 | 63556 | 3026.476 | 1.713 | n/a (no new text, 0 bytes appended) | yes |
| 50% REAL | EMPTY | 0 | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | n/a |
| 50% REAL | FRESH_IMPORT | 75233 | 75233 | 43 | 43/43 | 128 | 766 | 103757 | 22016 | 1825 | 0 | 128364 | 2985.209 | 1.706 | 1.706 | no |
| 50% REAL | REOPEN | 0 | 75233 | 43 | 43/43 | 128 | 766 | 103757 | 22016 | 1825 | 0 | 128364 | 2985.209 | 1.706 | n/a (no new text, 0 bytes appended) | yes |
| 50% REAL | REPEAT_IMPORT | 75233 | 75233 | 43 | 43/43 | 128 | 766 | 103757 | 22016 | 1825 | 0 | 128364 | 2985.209 | 1.706 | n/a (no new text, 0 bytes appended) | yes |
| 100% REAL | EMPTY | 0 | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | n/a |
| 100% REAL | FRESH_IMPORT | 150885 | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | 1.704 | no |
| 100% REAL | REOPEN | 0 | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | n/a (no new text, 0 bytes appended) | yes |
| 100% REAL | REPEAT_IMPORT | 150885 | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | n/a (no new text, 0 bytes appended) | yes |
| x4 DERIVED | EMPTY | 0 | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | n/a |
| x4 DERIVED | FRESH_IMPORT | 606672 | 606672 | 348 | 348/348 | 128 | 766 | 836788 | 178176 | 15092 | 0 | 1030822 | 2962.132 | 1.699 | 1.699 | no |
| x4 DERIVED | REOPEN | 0 | 606672 | 348 | 348/348 | 128 | 766 | 836788 | 178176 | 15092 | 0 | 1030822 | 2962.132 | 1.699 | n/a (no new text, 0 bytes appended) | yes |
| x4 DERIVED | REPEAT_IMPORT | 606672 | 606672 | 348 | 348/348 | 128 | 766 | 836788 | 178176 | 15092 | 0 | 1030822 | 2962.132 | 1.699 | n/a (no new text, 0 bytes appended) | yes |
| x16 DERIVED | EMPTY | 0 | 0 | 0 | 0/0 | 0 | absent | absent | absent | absent | absent | 0 | n/a | n/a | n/a | n/a |
| x16 DERIVED | FRESH_IMPORT | 2430342 | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352165 | 712704 | 61028 | 0 | 4126663 | 2964.557 | 1.698 | 1.698 | no |
| x16 DERIVED | REOPEN | 0 | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352165 | 712704 | 61028 | 0 | 4126663 | 2964.557 | 1.698 | n/a (no new text, 0 bytes appended) | yes |
| x16 DERIVED | REPEAT_IMPORT | 2430342 | 2430342 | 1392 | 1392/1392 | 128 | 766 | 3352165 | 712704 | 61028 | 0 | 4126663 | 2964.557 | 1.698 | n/a (no new text, 0 bytes appended) | yes |
| OVERLAP | IMPORT_50% | 75233 | 75233 | 43 | 43/43 | 128 | 766 | 103757 | 22016 | 1825 | 0 | 128364 | 2985.209 | 1.706 | n/a | n/a |
| OVERLAP | IMPORT_100% | 150885 | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | 1.703 | no |
| INTRA_BATCH_DUPLICATES | CLEAN_ONCE | 150885 | 150885 | 87 | 87/87 | 128 | 766 | 208150 | 44544 | 3717 | 0 | 257177 | 2956.057 | 1.704 | n/a | — |
| INTRA_BATCH_DUPLICATES | EVERY_CHUNK_TWICE | 301770 | 150885 | 87 | 87/87 | 128 | 766 | 208153 | 44544 | 3717 | 0 | 257180 | 2956.092 | 1.704 | n/a | — |

## Memory, per phase (informational; host-specific)

Heap and RSS are different measures and are not interchangeable. RSS peak is the process-lifetime high-water mark. Allocation is this thread's only.

| Store | Phase | Heap used MiB | Heap committed MiB | Heap pool-peak sum MiB | Heap after GC MiB | Thread alloc MiB | RSS MiB | RSS peak MiB |
| --- | --- | --: | --: | --: | --: | --: | --: | --: |
| 25% | FRESH_IMPORT | 10.5 | 36.0 | 19.1 | 3.1 | 4.8 | 79.8 | 79.8 |
| 25% | REOPEN | 7.1 | 36.0 | 7.1 | 3.3 | 2.0 | 83.0 | 83.0 |
| 25% | REPEAT_IMPORT | 3.3 | 36.0 | 3.3 | 3.3 | 0.9 | 83.2 | 83.2 |
| 50% | FRESH_IMPORT | 11.3 | 36.0 | 11.3 | 3.3 | 6.8 | 83.9 | 83.9 |
| 50% | REOPEN | 9.3 | 36.0 | 9.3 | 3.3 | 1.5 | 84.3 | 84.3 |
| 50% | REPEAT_IMPORT | 5.3 | 36.0 | 5.3 | 3.3 | 1.6 | 84.7 | 84.7 |
| 100% | FRESH_IMPORT | 5.7 | 36.0 | 17.7 | 3.3 | 13.6 | 85.8 | 85.8 |
| 100% | REOPEN | 11.3 | 36.0 | 11.3 | 3.3 | 2.7 | 86.0 | 86.0 |
| 100% | REPEAT_IMPORT | 9.3 | 36.0 | 9.3 | 3.3 | 3.0 | 86.5 | 86.5 |
| x4 | FRESH_IMPORT | 17.5 | 36.0 | 23.5 | 4.2 | 54.3 | 96.8 | 96.8 |
| x4 | REOPEN | 9.8 | 36.0 | 23.8 | 4.3 | 10.0 | 98.2 | 98.2 |
| x4 | REPEAT_IMPORT | 8.3 | 36.0 | 24.3 | 4.3 | 11.7 | 98.8 | 98.8 |
| x16 | FRESH_IMPORT | 32.1 | 108.0 | 76.1 | 8.9 | 215.9 | 171.9 | 171.9 |
| x16 | REOPEN | 41.9 | 108.0 | 79.9 | 8.9 | 39.2 | 175.8 | 175.8 |
| x16 | REPEAT_IMPORT | 34.7 | 108.0 | 88.7 | 8.9 | 46.2 | 178.7 | 178.7 |
| OVERLAP | IMPORT_50% | 11.5 | 108.0 | 81.5 | 8.7 | 6.8 | 178.8 | 178.8 |
| OVERLAP | IMPORT_100% | 18.7 | 108.0 | 18.7 | 8.8 | 8.3 | 178.8 | 178.8 |
| INTRA_BATCH_DUPLICATES | CLEAN_ONCE | 30.8 | 108.0 | 30.8 | 8.8 | 13.5 | 178.9 | 178.9 |
| INTRA_BATCH_DUPLICATES | EVERY_CHUNK_TWICE | 26.8 | 108.0 | 26.8 | 8.8 | 14.1 | 180.1 | 180.1 |

Retained heap of one reopened, queried instance (APPROXIMATE, after advisory GC): 25% 0.1 MiB, 50% 0.2 MiB, 100% 0.3 MiB, x4 1.2 MiB, x16 4.7 MiB.
