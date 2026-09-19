# H+F shared-graph run ledger

All runs use the shared Helsinki graph SHA
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0` and the
official 408-case corpus. A is the baseline JAR; HF is the combined H+F JAR.
The raw directories are outside Git under `/private/tmp`.

The production matrix uses pair IDs 02–07 because the earlier exploratory
`HF-01` run was not part of the paired bootstrap. Thus the table below contains
six fresh, balanced A/HF pairs.

An initial `jfr-01-A` directory contains a preflight failure (wrong default Java
and incomplete classpath) and is explicitly excluded; the valid first A run is
`jfr-01-A2`.

## SpeedTest matrix

Each row is a fresh JVM with `-Xmx8g -XX:+UseCompactObjectHeaders` and
`-p md -n 4 -i 3 -0`. Worker and Total are means of four samples. RSS is the
actual JVM PID sampled at roughly 0.5 s. Every row exited 0, reported 233/408
expected matches, had result SHA
`f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`, and had
zero swap events.

| Pair | Variant | Worker (ms) | Total (ms) | Case p50/p95 (ms) | RSS median (KiB) | RSS p95 (KiB) | RSS peak (KiB) | Samples |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 02 | A | 47.5 | 88.5 | 76 / 166 | 3,469,296 | 5,321,616 | 5,328,240 | 301 |
| 02 | HF | 56.3 | 93.8 | 78 / 201 | 3,023,856 | 5,210,912 | 5,210,976 | 316 |
| 03 | A | 49.0 | 87.8 | 75 / 163 | 3,459,296 | 5,282,768 | 5,283,120 | 299 |
| 03 | HF | 57.0 | 94.5 | 78 / 202 | 3,316,528 | 5,100,400 | 5,190,368 | 318 |
| 04 | A | 48.3 | 88.8 | 77 / 172 | 3,565,936 | 5,139,328 | 5,345,872 | 301 |
| 04 | HF | 62.8 | 102.3 | 84 / 220 | 2,967,648 | 5,252,240 | 5,254,224 | 345 |
| 05 | A | 48.5 | 87.5 | 75 / 164 | 3,542,832 | 5,333,744 | 5,335,056 | 298 |
| 05 | HF | 59.0 | 96.5 | 80 / 203 | 3,026,864 | 5,178,096 | 5,178,416 | 326 |
| 06 | A | 45.8 | 85.3 | 73 / 157 | 3,479,008 | 5,271,664 | 5,273,728 | 289 |
| 06 | HF | 58.5 | 95.8 | 79 / 200 | 3,008,688 | 5,276,192 | 5,276,448 | 323 |
| 07 | HF | 58.0 | 95.5 | 80 / 201 | 3,282,688 | 4,416,816 | 4,878,320 | 321 |
| 07 | A | 48.8 | 88.5 | 76 / 161 | 3,392,288 | 4,829,728 | 4,829,840 | 300 |

## JFR diagnosis

These four runs (two AB/BA pairs) are separate from the SpeedTest matrix. They use
`settings=profile` and `-Xlog:gc*`; profiler overhead is excluded from latency
claims. Both exited 0, produced the same result SHA and expected-match count,
and recorded zero swap events.

| Label | Variant | Exit | JFR SHA-256 | Allocation samples | Natural G1 / Full events | Pause sum / max (ms) | RSS median / peak (KiB) |
| :--- | :--- | ---: | :--- | ---: | ---: | ---: | ---: |
| jfr-01-A2 | A | 0 | `a8ff27d9f5e83e4b219c55162aecc31adde19af32b2ec311aabecc67a19c0607` | 44,350 | 108 / 5 | 1,628 / 203 | 3,335,664 / 5,368,896 |
| jfr-01-HF | HF | 0 | `ecac71db2bd4a89fe0f25a725c873bf0fa709c6ccb6c1c1e7e0be557a3445d11` | 47,712 | 94 / 5 | 1,519 / 184 | 3,131,120 / 5,317,536 |
| jfr-02-HF | HF | 0 | `e6226765fb42da818451f94f8b8f1cbe973dcb4e1750b04a6d53abf0774c850e` | 50,757 | 93 / 5 | 1,588 / 224 | 3,360,160 / 4,765,664 |
| jfr-02-A | A | 0 | `3e34d0428f6b0d335093eca5cc7ecefff47948243794e6ea3733a9b4244988d8` | 45,280 | 107 / 5 | 1,569 / 190 | 3,394,176 / 5,209,584 |

`jdk.GarbageCollection` counts above classify `G1New` + `G1Old` as natural;
the five `G1Full` events are the official forced-GC boundaries.

## HTTP no-pressure runs

HTTP raw runs are under `/private/tmp/otp-hf-http-no-pressure-20260916`. The
correctness gate used two A/HF sequential pairs (408 requests each). The
10-minute 16 RPS pilot used 9,600 requests per variant. All responses were
HTTP 200, transport errors were zero, Full GC was zero, and swap events were
zero. See `HF-SHARED-GRAPH-RESULT.md` for the latency/RSS table and pilot
interpretation.

## Build and source identity

- HF source checkout: `/private/tmp/otp-hf-http-run-20260916`.
- Base commit: `a0b62f69fc543e84687752e17dae8a66b01c5e28`.
- HF diff is limited to `TripPatternForDates`, its test, `DestinationArrivalPaths`,
  `PathConfig`, and `McRangeRaptorConfig`.
- HF shaded JAR SHA-256:
  `96a53bc8bdc695a370f2f4a7896943879e9daf5d574ad6f67e4a14e1bdf0fcc3`.
- Maven shaded build and `TripPatternForDatesTest`/Raptor tests passed before
  the matrix.
