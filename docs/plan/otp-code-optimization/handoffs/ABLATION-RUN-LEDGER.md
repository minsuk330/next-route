# A/H/F/HF ablation run ledger

Experiment: `otp-large-heap-ablation-20260914`  
Plan: [LARGE-HEAP-AGENT-PLAN.md](LARGE-HEAP-AGENT-PLAN.md)  
Inputs/artifact hashes: [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)

## P0 — tooling and inputs

- Isolated measurement-tool copy: `/private/tmp/otp-ablation-runner-20260914/`; the OTP checkout's untracked measurement scripts were not edited.
- Added repeatable `--case` selection, unknown/duplicate case rejection, and explicit `--seed`, `--when`, `--source-identity` metadata/forwarding in the scratch runner.
- `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s /private/tmp/otp-ablation-runner-20260914 -p 'test_*.py'`: **34 passed**. One fixture emits a harmless `ResourceWarning` while cleaning up its mocked HTTP 503.
- Docker read-only snapshot: `nextroute-postgres-1` and `nextroute-redis-1` remained `Up (healthy)`; no other container was stopped.

## P1 — build separation and correctness

| Run | Variant | Requests | Result | Full response comparison | Raw artifacts |
| --- | --- | ---: | --- | --- | --- |
| `ablation-a-correctness-20260914` | A | 2 | 2/2 success | reference only | `/private/tmp/otp-ablation-correctness-20260914/A` |
| `ablation-a-repeat-correctness-20260914` | A-repeat | 2 | 2/2 success | mismatch 0; manifest mismatch 0 | `/private/tmp/otp-ablation-correctness-20260914/A-repeat` |
| `ablation-h-correctness-20260914` | H-only | 2 | 2/2 success | mismatch 0; manifest mismatch 0 | `/private/tmp/otp-ablation-correctness-20260914/H-only` |
| `ablation-f-correctness-20260914` | F-only | 2 | 2/2 success | mismatch 0; manifest mismatch 0 | `/private/tmp/otp-ablation-correctness-20260914/F-only` |
| `ablation-hf-correctness-20260914` | HF | 2 | 2/2 success | mismatch 0; manifest mismatch 0 | `/private/tmp/otp-ablation-correctness-20260914/HF` |

Each comparison uses the same graph, date/time, seed, two selected ODs, and full correctness query requesting itinerary fields, route/trip/stop identities, times, and geometry. RSS coverage was `OBSERVED`, no run was OOM-killed, and runner cleanup reported no errors. A-repeat confirms the A response is deterministic for this two-OD fixture; it is not a broad correctness corpus.

H-focused tests: 18 passed, 0 failures/errors/skips. F Raptor module suite: 954 tests, 0 failures/errors, 2 skipped. F contract review confirmed that the mutable candidate is opt-in only at built-in scalar comparator call sites, debug/general comparator paths use immutable `Path.dummyPath`, and `ParetoSet.qualify` only compares—it does not append/retain the candidate. A dedicated F regression test for those contracts is still desirable and is not implied by the module suite.

## P2 — balanced c1 screening

Fixed workload: two ODs, c1, per-OD warmup 100, per-OD measured requests 200, stability window 100, tolerance 5%, timeout 900s; fresh container/JVM per cell. All four balanced blocks are to run serially. A first run is the performance-query response baseline; later runs compare full performance fingerprints against it.

| Block | Planned order | Run state | Output root |
| --- | --- | --- | --- |
| 1 | A → H → HF → F | complete (F uses fresh rerun after preserved interrupted attempt) | `/private/tmp/otp-ablation-p2-20260914/` |
| 2 | H → F → A → HF | complete | same |
| 3 | F → HF → H → A | complete (F materially host-swap contaminated; retain but exclude from clean comparison) | same |
| 4 | HF → A → F → H | complete (HF first attempt sampler-incomplete; fresh rerun substituted; host swap-in activity recorded) | same |

Block 1 / A completed in 7m32s. It needed 900 successful warmup requests before the three-window 5% stability gate passed (minimum requested warmup was 200 total). Measured requests were 400/400 successful; each OD contributed 200. Per-OD results: cityhall→gangnam p50/p95 `441.163/538.729ms`; bupyeong→seoul `226.875/264.694ms`. Measured RSS peak `3,371,937,792B`; GC pauses `284`, total pause `1,279.085ms`, maximum `11.028ms`. RSS status `OBSERVED`; container `OOMKilled=false`; cleanup errors none. Host swap used decreased from `1567.31M` to `1559.31M` (-8MB). This is exploratory screening on the current host, not PR evidence.

First run exact invocation:

```sh
python3 -B /private/tmp/otp-ablation-runner-20260914/run_isolated.py \
  --jar /private/tmp/otp-ablation-artifacts-20260914/A.jar \
  --graph /private/tmp/otp-graph-rebuild/graph.obj \
  --output-dir /private/tmp/otp-ablation-p2-20260914/block1-A \
  --run-id ablation-p2-b1-A-20260914 --variant A --concurrency 1 \
  --case cityhall-gangnam --case bupyeong-seoul --seed 20260908 \
  --when 2026-09-15T08:00:00+09:00 \
  --source-identity 'baseline source 02c0b4b537eb07bb0b284726397821682248fe20; P2 block 1 A' \
  --warmup-per-case 100 --requests-per-case 200 --require-warmup-stability \
  --warmup-stability-window 100 --warmup-stability-tolerance 0.05 \
  --warmup-stability-timeout-seconds 900 --gc g1 --xmx 10g \
  --image sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 \
  --container-memory 12g
```

H/F/HF runs in this block use the same command shape and add `--compare-to /private/tmp/otp-ablation-p2-20260914/block1-A/requests.jsonl` to require matching performance fingerprints.

Block 1 / H completed in 6m31s. Warmup: 1,000/1,000 success; measured: 400/400, with 200 per OD. Fingerprint mismatches: 0. Per-OD results: cityhall→gangnam p50/p95 `374.603/407.816ms`; bupyeong→seoul `168.728/183.486ms`. Measured RSS peak `2,953,670,656B`; GC pauses `84`, total `260.730ms`, maximum `3.781ms`. RSS status `OBSERVED`; container `OOMKilled=false`; cleanup errors none. Host swap used decreased from `1559.31M` to `1551.31M` (-8MB). This is one order-position observation, not a paired performance conclusion.

Block 1 / HF completed in 6m23s. Warmup: 800/800 success; measured: 400/400, 200 per OD. Fingerprint mismatches: 0. Per-OD results: cityhall→gangnam p50/p95 `414.955/513.797ms`; bupyeong→seoul `173.454/199.197ms`. Measured RSS peak `2,638,934,016B`; GC pauses `124`, total `281.564ms`, maximum `5.340ms`. RSS `OBSERVED`; `OOMKilled=false`; cleanup errors none. Host swap stayed `1551.31M`. This is one order-position observation, not a paired conclusion.

Block 1 / F first attempt was interrupted at the user's request. Raw remains at `/private/tmp/otp-ablation-p2-20260914/block1-F`: warmup 900/900 successful; measured 241/400 successful. It has no valid summary, fingerprint comparison, measured-end RSS coverage, or host swap-after value. `metrics.json` is `INCOMPLETE`; exclude its partial latency/RSS/GC. The runner stopped and removed only `otp-harness-30781`; cleanup errors were empty. A read-only check showed no remaining container/process and both Nextroute DB/Redis containers healthy.

After the user asked to continue, block 1 F was restarted from a **fresh JVM** at `/private/tmp/otp-ablation-p2-20260914/block1-F-rerun` (run ID `ablation-p2-b1-F-rerun-20260914`). It completed: warmup 500/500, measured 400/400 (200 per OD), A fingerprint mismatches 0. Cityhall→gangnam p50/p95 `507.422/611.154ms`; bupyeong→seoul `238.784/259.573ms`. RSS peak `3,482,099,712B`; GC pauses 228, total `905.059ms`, maximum `10.005ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap used remained `1383.31M`. The first partial F attempt remains preserved but excluded.

Block 1 is now complete: A → H → HF → F-rerun, all full measured samples successful, all candidate fingerprint comparisons mismatch 0. This is one block only; order and host effects remain, so no causal or performance conclusion is made. Mean cell wall time so far is about 6m33s; 12 cells remain, roughly 79 minutes at this pace.

Block 2 / H completed in 4m11s. Warmup 500/500, measured 400/400 (200 per OD), fingerprint mismatches 0. Cityhall→gangnam p50/p95 `363.936/430.067ms`; bupyeong→seoul `165.567/188.424ms`. RSS peak `2,575,421,440B`; GC pauses 198, total `464.837ms`, maximum `4.992ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap used stayed `1375.31M`.

Block 2 / F completed in 4m17s. Warmup 300/300, measured 400/400 (200 per OD), fingerprint mismatches 0. Cityhall→gangnam p50/p95 `481.555/546.867ms`; bupyeong→seoul `232.424/263.786ms`. RSS peak `3,028,144,128B`; GC pauses 307, total `1,209.141ms`, maximum `9.315ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap used decreased from `1375.31M` to `1367.31M` (-8MB).

Block 2 / A completed in 6m54s. Warmup 900/900, measured 400/400 (200 per OD), fingerprint mismatches 0 against block 1 A. Cityhall→gangnam p50/p95 `400.963/460.484ms`; bupyeong→seoul `213.319/246.335ms`. RSS peak `3,314,552,832B`; GC pauses 390, total `1,634.903ms`, maximum `20.049ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap used stayed `1367.31M`. The cityhall A p50 is about 9% lower than block 1 A, showing nontrivial run/order variation even for the same artifact; retain block pairing and avoid interpreting a single run as a code effect.

Block 2 / HF completed in 5m21s. Warmup 600/600, measured 400/400, fingerprint mismatches 0. Cityhall→gangnam p50/p95 `446.502/472.204ms`; bupyeong→seoul `184.564/192.022ms`. RSS peak `2,815,860,736B`; GC pauses 85, total `215.501ms`, maximum `4.689ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap used stayed `1367.31M`.

Block 3 / F completed in 10m15s. Warmup 700/700, measured 400/400, fingerprint mismatches 0. Cityhall→gangnam p50/p95 `537.457/616.214ms`; bupyeong→seoul `247.479/269.591ms`. RSS peak `3,285,110,784B`; GC pauses 200, total `875.530ms`, maximum `11.295ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Environment was pressure-contaminated: host swap total/used changed from `3072M/1367.31M` to `2048M/1261.31M`; cumulative counter deltas were pageins 24,106, pageouts 359, swapins 11,573 (about 181MiB), swapouts 8,882 (about 139MiB), assuming 16KiB pages. Keep the raw result for visibility, but exclude it from clean latency/causal comparisons.

Block 3 / HF completed in 4m34s. Warmup 400/400, measured 400/400 (200 per OD), fingerprint mismatches 0 against block 1 A. Cityhall→gangnam p50/p95 `481.859/544.865ms`; bupyeong→seoul `192.526/214.248ms`. RSS peak `2,463,690,752B`; GC pauses 139, total `314.811ms`, maximum `4.779ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap stayed `2048M/1261.31M`; counter deltas: pageins +783, pageouts +2, swapins +8 pages (~128KiB), swapouts +0. Classify as clean environment; single cell is not causal evidence. The next balanced-order cell is block 3 / H.

Block 3 / H completed in 3m54s. Warmup 400/400, measured 400/400 (200 per OD), fingerprint mismatches 0 against block 1 A. Cityhall→gangnam p50/p95 `400.261/460.186ms`; bupyeong→seoul `176.230/197.764ms`. RSS peak `2,617,188,352B`; GC pauses 193, total `463.769ms`, maximum `4.644ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap stayed `2048M/1261.31M`; counter deltas: pageins +825, pageouts +0, swapins +32 pages (~512KiB), swapouts +0. Classify as clean environment; single cell is not causal evidence. The next balanced-order cell is block 3 / A.

Block 3 / A completed in 3m38s. Warmup 300/300, measured 400/400 (200 per OD). Cityhall→gangnam p50/p95 `393.254/457.375ms`; bupyeong→seoul `210.342/239.946ms`. RSS peak `3,162,288,128B`; GC pauses 380, total `1,625.425ms`, maximum `10.173ms`; RSS `OBSERVED`, `OOMKilled=false`, cleanup errors none. Host swap stayed `2048M/1261.31M`; counter deltas: pageins +2,934, pageouts +0, swapins +8 pages (~128KiB), swapouts +0. Environment is clean. Across baseline A cells, cityhall p50 is `441.163ms` (block 1), `400.963ms` (block 2), and `393.254ms` (block 3), a 12% max/min spread; bupyeong is `226.875ms`, `213.319ms`, and `210.342ms`. This reinforces that single-cell candidate deltas are screening signals only. Block 3 is complete; next balanced-order cell is block 4 / HF.

Block 4 / HF first attempt completed all 400/400 requests with fingerprint mismatches 0 and no OOM, but RSS/GC collection is invalid: the memory sampler had a `986.328s` maximum gap (`metricsStatus=INCOMPLETE`), so do not use its apparent RSS peak or GC summary for comparisons. Latency/correctness raw is retained: cityhall→gangnam p50/p95 `445.916/594.037ms`; bupyeong→seoul `183.528/208.304ms`. Host swap used decreased `8MB`; pageins +10,478, pageouts +441, swapins +718 pages (~11.2MiB), swapouts +0. The exact OTP container exited normally (`OOMKilled=false`) and was removed with no cleanup errors. Treat as an incomplete benchmark cell and rerun from a fresh JVM with an idle-sleep inhibitor to protect sampler continuity.

Block 4 / HF rerun completed in 4m37s using a fresh JVM and a temporary idle-sleep inhibitor for the benchmark process only. Warmup 400/400, measured 400/400 (200 per OD), fingerprint mismatches 0. Cityhall→gangnam p50/p95 `489.405/609.877ms`; bupyeong→seoul `192.813/224.471ms`. RSS peak `2,571,010,048B`; GC pauses 126, total `305.017ms`, maximum `5.538ms`; RSS `OBSERVED`, max sampler gap `0.116s`, `OOMKilled=false`, cleanup errors none. Host swap used decreased from `1253.31M` to `1245.31M`; counter deltas: pageins +14,681, pageouts +526, swapins +120 pages (~1.875MiB), swapouts +0. This is the valid block 4 HF cell for P2 screening; keep the first attempt only as a documented sampler-gap failure. Since observed swap-in is nonzero, it is not formal no-swap evidence.

Block 4 / A completed in 9m07s using a temporary idle-sleep inhibitor. Warmup 1,100/1,100, measured 400/400 (200 per OD). Cityhall→gangnam p50/p95 `411.789/487.004ms`; bupyeong→seoul `217.352/246.925ms`. RSS peak `3,307,331,584B`; GC pauses 318, total `1,457.998ms`, maximum `10.093ms`; RSS `OBSERVED`, max sampler gap `0.110s`, `OOMKilled=false`, cleanup errors none. Host swap used decreased `32MB`; pageins +93,049, pageouts +4,119, swapins +1,884 pages (~29.4MiB), swapouts +0. Retain as P2 exploratory only because host swap activity increased; not formal no-swap evidence.

Block 4 / F completed in 4m20s using a temporary idle-sleep inhibitor. Warmup 300/300, measured 400/400 (200 per OD), fingerprint mismatches 0 against block 1 A. Cityhall→gangnam p50/p95 `485.672/558.904ms`; bupyeong→seoul `231.239/262.285ms`. RSS peak `3,226,148,864B`; GC pauses 212, total `935.476ms`, maximum `11.350ms`; RSS `OBSERVED`, max sampler gap `0.110s`, `OOMKilled=false`, cleanup errors none. Host swap usage was unchanged; pageins +2,187, pageouts +5, swapins +27 pages (~432KiB), swapouts +0. Retain as P2 exploratory screening only (nonzero swap-in means not formal no-swap evidence). The cityhall p50 is `485.672ms` versus block 4 A `411.789ms`; this agrees directionally with earlier F-only cells but does not replace P4 paired evidence.

Block 4 / H completed in 5m49s using a temporary idle-sleep inhibitor. Warmup 900/900, measured 400/400 (200 per OD), fingerprint mismatches 0 against block 1 A. Cityhall→gangnam p50/p95 `358.286/373.947ms`; bupyeong→seoul `163.937/170.936ms`. RSS peak `2,650,423,296B`; GC pauses 94, total `277.795ms`, maximum `9.507ms`; RSS `OBSERVED`, max sampler gap `0.112s`, `OOMKilled=false`, cleanup errors none. Host swap used decreased `8MB`; pageins +2,777, pageouts +509, swapins +591 pages (~9.23MiB), swapouts +0. Response and memory sampling are valid for P2 screening, but the nonzero swap-in means this is not formal no-swap evidence.

## P2 synthesis — screening only

Four balanced blocks (16 valid cells) are complete. Every valid cell returned 400/400 successful measured requests; every H/F/HF response fingerprint comparison had zero mismatches against A. The interrupted block 1 F partial and sampler-incomplete block 4 HF first attempt remain preserved but are not counted as valid cells. The block 3 F run is complete but excluded from medians for material host swap I/O. Per-cell details, RSS/GC, and host counter deltas above and in the manifest remain authoritative.

Block-level median p50, RSS peak, and total GC pause summarize the direction without pretending individual requests are independent repetitions:

| Variant | Cityhall→Gangnam p50 median (vs A) | Bupyeong→Seoul p50 median (vs A) | RSS peak median (vs A) | GC pause total median (vs A) |
| --- | ---: | ---: | ---: | ---: |
| A | 406ms | 215ms | 3.31GB | 1,542ms |
| H | 369ms (-9%) | 167ms (-22%) | 2.63GB (-20%) | 371ms (-76%) |
| F* | 486ms (+20%) | 232ms (+8%) | 3.23GB (-3%) | 935ms (-39%) |
| HF | 464ms (+14%) | 189ms (-12%) | 2.60GB (-21%) | 293ms (-81%) |

`*` F excludes block 3, whose host swap-in/out rose materially. Baseline A itself varied across four cells: cityhall p50 `393–441ms` (about 12% max/min spread), bupyeong `210–227ms` (about 8%). H's bupyeong p50 was lower than A in all four blocks. F-only cityhall p50 was slower than its within-block A in all three other blocks by about 15–20%, while its RSS median was only about 3% lower. HF shows the memory/GC reduction pattern of H and faster bupyeong p50, but cityhall p50 was mixed: one block faster and the other three slower than A. These are candidate-screening signals, not proof of causality: some cells had nonzero host swap-in, and the median is not a formal paired confidence interval.

The explicit within-block interaction check adds nuance: HF versus H was slower in every block (median p50 delta `+21.5%` cityhall, `+10.4%` bupyeong). HF versus F, excluding block 3, was faster in cityhall in two of three blocks (median `-7.3%`) and in bupyeong in all three (median `-20.6%`). Screening suggests H improves the F-only latency signal, while adding F to H carries a latency cost relative to H alone. P3 should test this mechanism directly.

P3 should prioritize F's CPU/JIT/comparator-cost hypothesis and the H×F interaction; retain H as the clearest broad memory/GC candidate. P4 is still required before a PR performance claim: same-host formal no-swap A/HF paired runs, at least five AB/BA pairs per concurrency, and the prescribed confidence intervals. Do not claim “always faster.”

## Environment and safety notes

- Host is an 18 GiB Apple Silicon Mac running Docker Desktop (VM reports 11 CPUs and about 14.9 GB RAM). It had about 1.6 GB of host swap in use before these runs. Runner records host swap usage and page counters before/after, but Docker Desktop VM swap is not directly observable in the captured host metrics.
- Treat any block with increased host swap-in/out as exploratory/inconclusive, not clean performance evidence. Do not stop Docker Desktop, PostgreSQL, or Redis to improve the result.
- Every run is one OTP container at a time. Output/raw files are preserved under `/private/tmp`; those may be lost on reboot, so final tables and hashes must be copied into the handoff documents.
- No commits, PRs, deployment, provider changes, or production changes are part of this experiment.

## P3 — JFR 결과 및 인라이닝 진단

두 OD(A/H/F/HF) 기본 `profile` JFR 8회는 모두 측정 200/200 성공했고 candidate fingerprint mismatch 0이었다. Allocation weight와 CPU stack은 measured start/end marker 사이에서 분석했다. 요약 표·가설 등급·한계는 [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md), 전체 결론은 [ABLATION-RESULT.md](ABLATION-RESULT.md)에 있다. 원시 JFR, marker, SHA-256은 manifest를 따른다.

두 OD sampled allocation weight/request (MB, 추정치) 결과:

| OD | 변형 | 전체 weight | `TripPatternForDates.<init>` | `int[]` class | `Path.dummyPath` |
| --- | --- | ---: | ---: | ---: | ---: |
| 시청→강남 | A / H / F / HF | 649.3 / 453.2 / 481.7 / 283.7 | 160.2 / 0.4 / 181.5 / 4.8 | 175.1 / 12.9 / 193.3 / 16.0 | 191.0 / 173.3 / 0.0 / 0.1 |
| 부평→서울 | A / H / F / HF | 361.7 / 164.9 / 311.8 / 109.4 | 157.5 / 3.6 / 165.5 / 4.5 | 172.3 / 10.9 / 175.0 / 12.0 | 68.8 / 51.2 / 0.0 / 0.0 |

해석: H-only에서 schedule constructor-site와 `int[]` JFR weight가 큰 폭으로 줄어 H 배열 제거 가설은 sampled evidence에서 지지된다. F-only에서는 `Path.dummyPath` site 표본이 두 OD 모두 사라져 dummy Path allocation 제거도 지지된다. JFR sample은 정확한 byte/객체 수가 아니며, 후속 direct counter는 H 배열 payload 산식과 호출 수를 보강했다. H 접근당 비용과 F latency 원인은 여전히 미확정이다.

기본 `profile`에는 `jdk.CompilerInlining`이 활성화돼 있지 않아, Java 23 `profile.jfc`에서 해당 이벤트만 활성화한 추가 cityhall 진단을 A/H/F/HF 한 번씩 수행했다. 이 네 run도 200/200 성공, mismatch 0이고 임시 OTP container cleanup 오류가 없었다. JIT decisions는 warmup 시작부터 measured 종료까지 필터했다. Comparator scalar chain이 네 변형에서 인라인된 사례가 있으며, F/HF에서는 `MutableDummyPath` scalar getter의 C1/C2 인라인 사례가 관측됐다. 따라서 관측 call-site 범위에서 “MutableDummyPath receiver라서 inlining이 깨졌다”는 단순 가설은 지지되지 않는다. JFR inlining event 건수는 runtime call counts가 아니다.

추가 JIT 프로파일은 성능 비교가 아니다. 각 실행은 global host swap I/O가 있었고 F diagnostic은 swap-in `157,828` page(16KiB 기준 약 2.41GiB), swap-out `23,919` page 증가가 기록됐다. VM swap total도 run 전후 바뀌었다. 네 p50/p95 값은 프로파일링 overhead·host pressure 때문에 비교에서 제외한다. Inlining event 설정 hash·JFR hash·marker·swap counter는 [ABLATION-MANIFEST.json](ABLATION-MANIFEST.json)의 `p3.inliningDiagnostics`에 보존했다.

### P3 — direct counter 진단

2026-09-15 별도 diagnostic source/JAR로 두 OD × A/H/F/HF = 8회 실행했다. 각 run concurrency 1, warmup 0, measured 200, `-Xmx10g`, OTP container memory 6GiB였다. 결과 비교는 해당 OD의 기존 A P3 request fingerprints를 사용했고, 전 run 200/200 성공·mismatch 0, RSS metrics `OBSERVED`, cleanup error 0이었다. 계측 JAR의 latency는 성능 비교에서 제외한다. 자세한 산식·해석·hash는 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md), run metadata와 SHA는 manifest의 `directCounters`에 있다.

| OD | qualifier calls/request | comparator directions/request | `getTripSchedule`/request | arrival/request | departure/request |
| --- | ---: | ---: | ---: | ---: | ---: |
| 시청→강남 | 3,154,236 | 20,071,584 | 674,940 | 4,283,857 | 11,065,176 |
| 부평→서울 | 1,024,335 | 2,597,485 | 269,178 | 1,783,394 | 5,632,888 |

A/H/F/HF의 케이스별 호출 합계와 candidate-set histogram은 동일했다. F/HF에서 comparator receiver pair는 `Path→MutableDummyPath` / reverse로 바뀐다. measured 200개 요청 배치의 H 배열 dimension 계측은 `TripPatternForDates` 787,200개, `Σstops×trips=5,259,269,400`, `Σtrips=82,364,600`이며 교체되는 두 배열의 원소 payload 산식은 A 42,074,155,200B, H 658,916,800B(CompressedOops 확인, header/alignment 제외)다. 이는 요청당 평균 A 210,370,776B, H 3,294,584B다. P3 직접 진단은 완료했다. P4 formal no-swap AB/BA gate는 아직 미완료다.

## P4 — feasibility pilot only (formal gate failed)

Run `p4-feasibility-c1-A-20260915`: original A JAR, c1, 4 OD, per-OD warmup 100 + measured 100, stable warmup gate enabled, `-Xmx10g`, container 12g. Warmup and measured requests both succeeded 400/400; RSS/GC metrics were `OBSERVED`, OOM was false, cleanup errors were empty. Summary p50/p95 `379.885/437.679ms` is not a performance result because the host swap gate failed.

The system counter rose by 7,675 swap-in pages (16KiB/page ≈119.9MiB), while swap-out stayed at zero. Swap usage snapshot fell 128MiB, which does not negate the counter evidence. The comparison file was P2 block-1 A and contained only `cityhall-gangnam` and `bupyeong-seoul`; the pilot had four ODs. The runner's 200 mismatch records are the 100 requests each for missing-baseline `cheonggye-sadang` and `bupyeong-gangnam`; the two shared cases had no comparison mismatches. Thus this was not a full 4-OD baseline correctness check. Raw files: `/private/tmp/otp-p4-feasibility-20260915/c1-A/`. See [P4 feasibility report](ABLATION-P4-FEASIBILITY.md) and manifest `p4Feasibility`.

No A/HF pair, c2/c4 cell, 5-pair block, or bootstrap was started. Formal P4 remains pending a host with zero swap-in/out deltas and a matching four-OD baseline. Existing Docker services were not intentionally stopped; the isolated runner cleaned up its own OTP harness.

### P4 A/A retry — full 4-OD response reference, still swap-contaminated

The first command used a 2-OD comparison file, so a second A c1 pilot was run without `--compare-to` at `/private/tmp/otp-p4-feasibility-retry-20260915/c1-A-run2/` (`p4-feasibility-retry-c1-A-run2-20260915`). Same graph/JAR/image, seed/time, 4 ODs, warmup 100 and measured 100 per OD, and stable warmup required. Warmup stable=true; warmup and measured both 400/400 successful; RSS/GC `OBSERVED`; OOM=false; runner exit 0; cleanup errors none. It provides a full 4-OD A response fingerprint baseline for later HF correctness comparison.

An offline A/A fingerprint comparison against the first A pilot matched 100/100 requests for each case (`cityhall-gangnam`, `cheonggye-sadang`, `bupyeong-seoul`, `bupyeong-gangnam`), total mismatch 0. This supports repeatability of the fixed A performance baseline. A separate full-detail A/HF correctness comparison is recorded later in this P4 ledger section. Host swap-in rose by 250 pages (16KiB/page ≈3.91MiB), swap-out by 0; host swap usage snapshot was unchanged at `1734.38M`. Therefore the retry remains invalid for formal performance evidence. Diagnostic p50/p95 `343.677/407.787ms` are excluded. Raw SHA-256 and counters are in manifest `p4Feasibility.retry`.

### P4 A feasibility recheck — full-corpus match, still swap-contaminated

After a short preflight window with no swap counter delta (03:33:46–03:36:32 KST) and 78% system-wide memory free, a third A/c1 feasibility run was executed at `/private/tmp/otp-p4-feasibility-recheck-20260915/c1-A-run3/` (`p4-feasibility-recheck-c1-A-run3-20260915`). It used the same 4 ODs, graph/JAR/image, seed/time, c1, per-OD warmup 100 and measured 100, and stable warmup gate. Warmup and measured both succeeded 400/400; the comparison against the second A run's four-case reference had `mismatchCount=0`, no missing cases, and `manifestMismatch={}`. RSS/GC were `OBSERVED` with 1,156 samples, no memory errors, OOM=false, and cleanup errors none. Existing Postgres/Redis remained healthy.

The diagnostic p50/p95 were `341.836/384.291ms`, peak process RSS `3,424,190,464B`, and total GC pause `1,185.147ms`; do not use these as performance evidence. Host `Swapins` increased from 3,546,190 to 3,546,278 (**+88 pages ≈1.38MiB**), while `Swapouts` stayed unchanged. This fails the formal no-swap prerequisite despite the preceding stable window. Raw SHA-256 is in `ABLATION-MANIFEST.json` under `p4Feasibility.thirdPilot` and in [P4 feasibility report](ABLATION-P4-FEASIBILITY.md).

### P4 correctness-only — A vs HF, all four OD cases

Run full-detail `--query-mode correctness` once per OD for A and HF with the same graph, date/time, seed, query, concurrency, JDK, image, heap, and container cap. A reference `/private/tmp/otp-p4-correctness-20260915/A/` (`p4-correctness-A-20260915`) succeeded 4/4. HF `/private/tmp/otp-p4-correctness-20260915/HF/` (`p4-correctness-HF-20260915`) succeeded 4/4 and compared against A with `mismatchCount=0`, `manifestMismatch={}`, and no missing baseline cases. The query includes complete itinerary fields, scheduled/estimated times, route/trip/stop IDs, coordinates, and leg geometry. This supports equality only for one request at each of these four fixed ODs; it is not broad OD coverage, repeated-result stability, or a performance run. P50/p95 are intentionally excluded.

Query SHA-256: `438fa19660e368798057294c94982adaffef07a029da9d54f5b84d64307d9f89`. JAR SHA-256: A `f7cacd4211a4cfe434767043cda7a0d72bfa1805ac63925c999ed70c3a44ee52`; HF `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`. Raw hashes are in manifest `p4Feasibility.fullCorrectness4Od`.

The full-corpus A reference is available and the third A run matched it, but all three feasibility runs failed the no-swap gate. No A/HF performance pair, c2/c4 cell, 5-pair block, or bootstrap has been started. The remaining prerequisite is a controlled host with zero swap-in/out deltas. Existing Docker services were not intentionally stopped; the isolated runner cleaned up only its own OTP harness.
