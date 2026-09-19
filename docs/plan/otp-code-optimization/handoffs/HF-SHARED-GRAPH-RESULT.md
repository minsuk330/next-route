# H+F shared-graph experiment result

Updated 2026-09-16. This is a fresh comparison of the H and F changes together
against the upstream baseline at `dev-2.x` commit
`a0b62f69fc543e84687752e17dae8a66b01c5e28`. The comparison uses one shared
Helsinki graph and does not reuse the historical mixed commit as a binary.

## Conclusion

The H+F build preserved the routing result, but this no-pressure experiment does
not support a universal speed or process-RSS win. In the official SpeedTest
matrix (six fresh balanced pairs), H+F was consistently slower in the Worker/RAPTOR metric (+22.1% mean)
and slower in Routing Total (+9.9% mean). Sampled whole-process RSS was almost
unchanged (−1.3% peak, −2.4% measured-window p95; CI crosses zero). The separate HTTP pilot showed
the same qualitative limitation: H+F reduced Young GC count and RSS p95 in one
10-minute run, but had higher request p95 latency.

The strongest current H+F claim is therefore **semantic equivalence plus lower
allocation pressure**, not a broad latency/RSS improvement. The latency result is
an exploratory local observation, not an upstream performance gate.

## Controlled conditions

- Same graph for every run: `/private/tmp/otp-a-only-helsinki-4wN2sL/graph.obj`,
  SHA-256 `178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`.
- A baseline JAR: `/private/tmp/otp-a-only-helsinki-4wN2sL/otp-a-only.jar`,
  SHA-256 `772ff39a2ef4f8551afab1b053b548742fadeeacab55c6fd4c905b78419d068f`.
- H+F shaded JAR: `/private/tmp/otp-hf-http-run-20260916/otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar`,
  SHA-256 `96a53bc8bdc695a370f2f4a7896943879e9daf5d574ad6f67e4a14e1bdf0fcc3`.
- Java 26.0.2.1 on macOS 26.5.2, Apple M3 Pro, 18 GiB RAM.
- SpeedTest JVM: `-Xmx8g -XX:+UseCompactObjectHeaders`; HTTP JVM:
  `-Xms8g -Xmx8g -XX:+UseG1GC`.
- Official Helsinki 408-case corpus, date `2026-02-19`, four SpeedTest samples
  (`-p md -n 4 -i 3 -0`). Runs were one JVM at a time.
- Docker was not started or changed by this experiment. Existing Postgres/Redis
  containers were left untouched.
- All valid runs recorded zero swap-in/out events. SpeedTest runs had 66–67%
  system-wide free memory at start; the HTTP 10-minute runs also had zero
  `SWAP_EVENT` and zero Full GC.

## Correctness gate

The two sequential HTTP A/H+F pairs each returned 408/408 HTTP 200 responses,
with zero transport or GraphQL errors. Fingerprint comparison found zero
mismatches within both pairs and across repeated A and H+F runs.

All six fresh SpeedTest A/H+F pairs (12 fresh JVM runs; four official samples
per run) exited 0, reported
233/408 official expected matches on both variants, and produced the same result
CSV SHA-256 `f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`.
The remaining 175 expected-file differences are identical on both variants and
are not 175 runtime failures.

## Official SpeedTest matrix

Values are the mean of the four official samples. RSS is the actual JVM PID
sampled at roughly 0.5 seconds; it is whole-process RSS, including graph
residency. Case p50/p95 are nearest-rank values from the final sample's 408
`Total times(ms)` values. Positive percentage below means H+F is higher/slower
than A.

| Pair | A Worker / Total (ms) | H+F Worker / Total (ms) | A RSS peak / p95 (MiB) | H+F RSS peak / p95 (MiB) | H+F Total change |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 02 | 47.5 / 88.5 | 56.3 / 93.8 | 5,203.4 / 5,196.9 | 5,088.8 / 5,088.8 | +6.0% |
| 03 | 49.0 / 87.8 | 57.0 / 94.5 | 5,159.3 / 5,159.0 | 5,068.7 / 4,980.9 | +7.6% |
| 04 | 48.3 / 88.8 | 62.8 / 102.3 | 5,220.6 / 5,018.9 | 5,131.1 / 5,129.1 | +15.2% |
| 05 | 48.5 / 87.5 | 59.0 / 96.5 | 5,210.0 / 5,208.7 | 5,057.0 / 5,056.7 | +10.3% |
| 06 | 45.8 / 85.3 | 58.5 / 95.8 | 5,150.1 / 5,148.1 | 5,152.8 / 5,152.5 | +12.3% |
| 07 | 48.8 / 88.5 | 58.0 / 95.5 | 4,716.6 / 4,716.5 | 4,764.0 / 4,313.3 | +7.9% |
| **mean** | **47.98 / 87.73** | **58.60 / 96.40** | **5,110.0 / 5,074.7** | **5,043.7 / 4,953.6** | **+9.88%** |

The table's rounded RSS values are derived from the raw KiB samples in the
matrix ledger. The exact paired means are: Worker +10.62 ms (+22.13%), Total
+8.67 ms (+9.88%), RSS median −380,397 KiB (−10.92%), RSS p95 −124,032 KiB
(−2.39%), and RSS peak −67,851 KiB (−1.30%). A 10,000-resample paired
bootstrap gives latency improvement (A−HF)/A of −22.14% [−26.33, −18.36]
for Worker and −9.89% [−12.51, −7.57] for Total. RSS p95 improvement is
2.40% [−0.04, +5.08] and sampled peak improvement is 1.29% [0.17, 2.28],
so the p95 interval crosses zero and neither metric supports a 5% RSS claim.
The RSS median is more variable than the measured-window p95 and is not a
retained-heap measurement.

Case-level latency was also higher for H+F: mean final-sample p50 79.8 ms vs
75.3 ms (+6.0%) and p95 204.5 ms vs 163.8 ms (+24.8%). The direction was the
same for case p50 and p95 in all six fresh pairs.

## HTTP no-pressure pilot

Short open-loop pilots at 4/8/12/16/24/32 RPS all returned HTTP 200 with zero
transport errors, zero Full GC, and no swap event. The direction was mixed; the
24/32 RPS rows are saturation exploration, not a no-pressure claim.

The selected 16 RPS, 10-minute pair scheduled 9,600 requests per variant:

| Variant | latency p50 / p95 / p99 (ms) | RSS p95 / sampled peak (MiB) | Young GC | Full GC | errors / swap |
| :--- | ---: | ---: | ---: | ---: | :--- |
| A | 84.22 / 180.13 / 456.22 | 6,263.6 / 6,314.4 | 434 | 0 | 0 / 0 |
| H+F | 93.76 / 239.23 / 480.33 | 5,836.4 / 6,237.8 | 316 | 0 | 0 / 0 |

In this one pair H+F was +32.8% on p95 and +11.3% on p50, while RSS p95 was
−6.8%, sampled peak −1.2%, and Young GC count −27.2%. This is a pilot
observation, not a statistical production result.

## JFR allocation diagnosis

Four additional fresh JVMs (two AB/BA pairs) used the same graph, SpeedTest
arguments, and JFR `profile` settings. JFR overhead means these latency values
are excluded from the matrix above. `ObjectAllocationSample.weight` is a
sampling estimate over the full recording (startup, graph load, warmup, and
measured routing); it is not exact allocated bytes, retained heap, or RSS.

| Whole-recording estimate (mean of two recordings) | A | H+F | H+F relative to A |
| --- | ---: | ---: | ---: |
| Allocation sample weight | 170.23 GB | 123.22 GB | −27.6% |
| `int[]` class weight | 23.75 GB | 4.20 GB | −82.3% |
| `TripPatternForDates.<init>` stack weight | 21.79 GB | 2.23 GB | −89.8% |
| `Path.dummyPath` stack weight | 26.99 GB | not sampled in either run | diagnostic only |
| Natural G1 collections (`G1New` + `G1Old`) | 107.5 | 93.5 | −13.0% |
| Natural G1 pause sum / max | 1,598 / 196.5 ms | 1,554 / 204.2 ms | −2.8% / +3.9% |
| After-GC heap median | 967.9 MiB | 958.9 MiB | −0.9% |

The allocation result is consistent with H removing the request-local timetable
arrays and F avoiding the temporary immutable dummy `Path`. It does not imply
that process RSS must fall by the same percentage. Each of the four recordings
had five `G1Full` events from the official forced-GC boundaries; those are not
counted as natural G1 collections above.

## Interpretation and limits

- H+F is functionally equivalent on the shared graph and official corpus under
  the gates above.
- Allocation pressure and natural GC activity decreased in the JFR diagnostic,
  but no material no-pressure RSS reduction was demonstrated.
- The current SpeedTest sample shows a repeatable local H+F latency regression,
  so the old Docker/VPS “roughly half RSS and faster” observation cannot be
  generalized to this controlled macOS run. It remains an environment- and
  workload-specific operational observation.
- These are local exploratory results, not upstream CI. A PR should present the
  semantic and allocation evidence, state the latency/RSS limitation explicitly,
  and avoid claiming a universal speedup.

## Raw evidence

- SpeedTest matrix: `/private/tmp/otp-hf-shared-graph-speedtest-20260916/matrix`
- JFR pair: `/private/tmp/otp-hf-shared-graph-speedtest-20260916/jfr`
- HTTP correctness/pilots: `/private/tmp/otp-hf-http-no-pressure-20260916`
- H+F source checkout: `/private/tmp/otp-hf-http-run-20260916`
- H-only allocation/JFR comparison: `H-ONLY-SHARED-GRAPH-RESULT.md`

No commit, pull request, deployment, or Docker OTP service was created by this
experiment.
