# Baden HTTP P6 run ledger

상태: `USER_STOPPED_PARTIAL / ENVIRONMENT_CONTAMINATED` (2026-09-17)

## A/H 역할 정의 (소스 검증 기준)

- **A = upstream 원본**: `arrivalTimes[]`/`departureTimes[]` 평탄 배열을 만들고
  요청마다 stop×trip 값을 복사한다. SHA-256:
  `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6`.
- **H = 최적화 코드**: 평탄 배열을 제거하고 `TripTimes[]`와 `offsetsByTrip[]`를
  참조한다. SHA-256:
  `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d`.

`otp-h-only.jar`/`otp-baseline.jar` basename과 `pair*-A`/`pair*-H` raw 디렉터리
접미사는 과거 실행 bookkeeping 값일 뿐이다. 기존 raw의 의미론적 역할은 반드시
`status.txt`의 JAR SHA로 판정한다. 아래의 예전 run tag와 새 분석표가 다를 수 있는
것은 이 소스 기준으로 재분류했기 때문이다.

현재 보존 raw의 semantic 매핑은 다음과 같다(접미사를 역할로 읽지 않는다).

| raw 디렉터리 접미사 | 실제 semantic role |
| --- | --- |
| `pair01-A`, `pair02-A`, `pair03-A`, `pair04-H`, `pair05-H` | H 최적화 (`TripTimes[]`/`offsetsByTrip`) |
| `pair01-H`, `pair02-H`, `pair03-H`, `pair04-A`, `pair05-A`, `pair06-H` | A 원본 (`arrivalTimes[]`/`departureTimes[]`) |

분석기와 manifest는 `jarSha256`에 따라 이 매핑을 자동 반영한다.

고정 자산은 `H-ONLY-BADEN-HTTP-NO-PRESSURE-AGENT-PLAN.md`와 동일하다. 모든 run은
native Java 26, `-Xms512m -Xmx10g -XX:+UseG1GC`, 동일 graph/corpus, 8 requests/s,
32 workers, 20,000 scheduled requests를 사용한다. Docker는 사용하지 않는다.

| pair | 순서 | run | 상태 | raw |
| --- | --- | --- | --- | --- |
| 01 | A→H | P6-01-A-baseline | 완료 | `/private/tmp/otp-baden-http-native-20260917-p6/pair01-A/` |
| 01 | A→H | P6-01-H-only | 무효(포트 충돌·중복 runner) | `/private/tmp/otp-baden-http-native-20260917-p6/pair01-H/` |
| 02 | H→A | P6-02-H-only / P6-02-A-baseline | H 완료·A 재실행 중 | `/private/tmp/otp-baden-http-native-20260917-p6/pair02-{H,A}/` |
| 03 | H→A | P6-03-H-only / P6-03-A-baseline | 이전 시도 무효·fresh 재실행 대기 | `/private/tmp/otp-baden-http-native-20260917-p6/pair03-{H,A}/` |
| 04 | A→H | P6-04-A-baseline / P6-04-H-only | 대기 | `/private/tmp/otp-baden-http-native-20260917-p6/pair04-{A,H}/` |
| 05 | A→H | P6-05-A-baseline / P6-05-H-only | 대기 | `/private/tmp/otp-baden-http-native-20260917-p6/pair05-{A,H}/` |
| 06 | H→A | P6-06-H-only / P6-06-A-baseline | 대기 | `/private/tmp/otp-baden-http-native-20260917-p6/pair06-{H,A}/` |
| 01-correction | A→H | P6-01-correction-A / P6-01-correction-H | pair06-A 후 자동 대기 | `/private/tmp/otp-baden-http-native-20260917-p6/pair01-correction/{A,H}/` |

P6-01-A 요약은 임시 analyzer로 생성했으며, 최종 표에는 모든 run 완료 후 같은
분석기를 적용한다.

- request records: 20,000; HTTP status/transport gate는 status file과 JSONL에서 확인
- service p50/p95/p99/max: 122.26 / 285.28 / 1,098.82 / 3,074.65 ms
- transit-only service p50/p95: 124.72 / 278.28 ms (18,747 records)
- sampled RSS median/p95/max: 8,257.7 / 8,688.1 / 9,084.1 MiB
- sampled heap EU max / OU max: 5,496 / 3,097 MiB
- GC pause events/sum/p95/max: 754 / 6,966.37 / 39.58 / 180.57 ms; Full GC 0
- host counter delta: Pageouts +15,433, Swapins +309, Swapouts 0

P6-01-A의 host swap-in/pageout delta가 0이 아니고, P6-01-H는 runner 중복과
18080 포트 충돌이 확인되어 무효 처리했으므로, 현재 P6도 no-pressure
PR 증거가 아니라 `ENVIRONMENT_CONTAMINATED`로 보류한다. 이 run을 삭제하지 않고
후속 pair에도 동일하게 기록한다. P6-01-H 이후 자동 continuation은 다음 순서로
실행된다: `02 H→A`, `03 H→A`, `04 A→H`, `05 A→H`, `06 H→A`. P6-01은
clean A/H 보정 run을 별도로 추가해 pair를 복구한다.

첫 충돌 재발 방지를 위해 `http-experiment/run_baden_native_http.zsh`에
JVM 기동 전 `lsof` 포트 preflight를 추가했다. 18080이 점유된 경우 새 JVM을
시작하지 않고 `preflight=port_in_use`, `ready=0`, `generatorExit=port_in_use`를
status에 기록해 run을 명확히 무효화한다. 이미 실행 중인 pair02-H에는 영향을 주지 않는다.

P7 전처리로 P6-01-A의 GC overlap을 산출했다. GC pause와 겹치지 않은 18,776건의
service p95는 256.6ms, 겹친 1,224건의 p95는 2,400.2ms였다(잠정 값; 전체 pair
완료 후 동일 parser로 재생성). 따라서 전체 HTTP p95와 GC-free p95를 분리해 보고한다.

## 실험 중 즉시 기록된 압박 이벤트

- **P6-01-H-only, 2026-09-16 18:39:07 UTC (sampler epoch `1789583947468923000`):**
  run 시작 시점 대비 host `Swapins`가 약 `+482,798`, `Swapouts`가 약
  `+627,711`, `Pageouts`가 약 `+1,060`으로 증가했다. `vm.swapusage`는
  `used=2,240.75MiB`로 관측됐고, sampler의 jstat `FGC` counter는 1,
  `RSS=9,555.25MiB`, `OU=2,947.8MiB`였다. 이 시점에는 swap/page I/O가
  확실히 발생했으므로 P6-01-H를 즉시 `ENVIRONMENT_INVALID`로 표시한다.
  `FGC=1`은 jstat counter이므로 최종 Full GC 횟수는 unified GC log의
  pause/cause line으로 별도 판정한다. 요청은 계속 실행해 장기 부하 관찰값을
  보존하되, 이 run의 latency를 no-pressure PR 성능 근거로 사용하지 않는다.

- **P6-01-H 무결성 판정(2026-09-17 확인):** run directory의 server log에
  `Cannot bind to port 18080. Is it already in use?`가 있었고, `status.txt`에는
  `ready=0`와 `generatorExit=server_not_ready`가 남아 있었다. 동시에 같은
  디렉터리에 다른 runner가 기록한 20,000 요청과 정상 종료 줄이 섞여 있어 단일
  JVM·단일 listener의 증거가 아니다. 따라서 기존 P6-01-H는 전체 폐기하지 않고
  raw를 보존하되 `ENVIRONMENT_INVALID`로 고정한다. P6-01 A→H 보정 pair는
  P6-06-A 뒤에 fresh A와 fresh H를 연속 실행한다.

무효 run의 사후 진단값도 별도 보존했다. P6-01-H의 20,000 records는 service
p50/p95/p99/max `130.39 / 431.72 / 2,721.37 / 21,990.22ms`였지만, listener와
runner 정체성이 섞였으므로 A와의 성능비·bootstrap에는 포함하지 않는다.
마지막 요청 직후 시도한 NMT attach는 `AttachNotSupportedException`으로 실패해
이 무효 run에는 유효한 NMT after snapshot도 없다.
무결성 검사 결과도 동일하다: P6-01-A는 `valid=true`, P6-01-H는
`valid=false`이며 `ready=['0']`, `port_bind_failure`,
`generatorExit=['server_not_ready','0']`가 검출됐다. 검사기는
`http-experiment/verify_baden_run_integrity.py`로 보존한다.

- **P6-02-H-only, 2026-09-16 19:08:48 UTC:** 자동 pressure monitor가 첫
  `Swapins +8`을 측정 구간 초기에 기록했다(`pressure-events.log`). 시작 시
  swap usage는 1,952.62MiB였고, 이후 swap/pageout counter가 계속 증가했다.
  `ready=1`·포트 충돌 없음인 단일 JVM이지만 host no-pressure gate는 실패했으므로
  P6-02-H도 `ENVIRONMENT_INVALID`로 표시한다. 요청은 계속 실행한다.

P6-02-H의 측정 전 NMT snapshot에서는 Java heap `committed=8,272MiB`, 전체 NMT
`committed=8,603MiB`가 이미 관측됐다. 이는 `-Xmx10g`가 예약 상한일 뿐, graph
기동·인덱싱 후 committed heap과 host resident/swap이 별도로 움직인다는 점을
확인하는 진단값이다. 최종 run 간 비교에서는 NMT before/after를 함께 병기한다.

P6-02-H는 `ready=1`, `generatorExit=0`, `serverExit=0`, 20,000/20,000 요청으로
무결성 검사 `valid=true`를 통과했다. HTTP status error와 transport error는 0건이고
routing error response는 78건이었다. service latency는 p50/p95/p99/max
`144.02/346.31/1,179.70/5,239.43ms`, transit 18,747건은
`147.56/332.49/715.72/5,239.43ms`였다. RSS median/p95/max는
`9,402.4/9,660.8/9,959.0MiB`, heap EU max/OU max는 `6,096/3,111.8MiB`,
YGC/YGCT/FGC/GCT는 `629/11.399s/0/12.683s`였다. unified GC pause는
1,111회·합계 12,822.2ms·p95 48.8ms·max 187.2ms였다. GC-overlap 분류는
GC_FREE 18,239건(p95 305.5ms), GC_OVERLAP 1,761건(p95 1,849.1ms)이다.
Host delta는 Pageouts +147,921, Swapins +5,104, Swapouts 0이므로
`ENVIRONMENT_INVALID`이다. 원자료 요약은
`/private/tmp/otp-baden-http-native-20260917-p6/pair02-H-summary.json`,
무결성은 `pair02-H-integrity.json`, overlap은 `pair02-H-gc-overlap.json`에 보존했다.

- **P6-02-A-baseline, 2026-09-16 19:51:44 UTC:** 측정 초기에 pressure monitor가
  `Swapins +12`와 `Pageouts +203`을 기록했고, 19:51:52 UTC에는 `Swapins +16`으로
  증가했다(`pair02-A/pressure-events.log`). `swapusage used`는 약 1,880.62MiB로
  변하지 않았지만 strict no-pressure gate는 이미 실패했으므로 A run도
  `ENVIRONMENT_INVALID`로 분류한다. 요청은 계속 실행한다.

- **P6-02-A-baseline, 2026-09-16 19:52:48 UTC:** 이후 `Swapins` delta가
  `+1,808`으로 급증했고 `Pageouts`는 `+203`이었다. `swapusage used`는
  1,848.62MiB로 관측됐다. 이 이벤트는 위 분류를 재확인하며, 해당 A run의
  no-pressure 성능 비교 사용을 금지한다.

- **P6-02-A-baseline, 2026-09-16 19:53:21–19:53:23 UTC:** pressure monitor가
  `Swapins +1,884`, `Pageouts +2,806`을 기록했고, 19:53:23 UTC에는
  `Pageouts +2,815`로 증가했다. `swapusage used`는 1,848.62MiB였다.
  이 run은 계속 실행하지만 `ENVIRONMENT_INVALID`로 유지한다.

- **P6-02-A-baseline 재실행, 2026-09-16 20:14:05 UTC 이후:** 이전 부분 실행을
  보존하고 새 디렉터리에서 fresh JVM으로 재시작했다. pressure monitor가 첫
  `Swapins +4`를 관측했고, 20:29:27 UTC에는 `Swapins +148`, `Pageouts +37,814`,
  `Swapouts 0`까지 증가했다. `vm.swapusage used`는 1,752.44MiB로 관측됐고,
  memory pressure free는 76–82% 범위였다. swap-in/pageout이 0이 아니므로 이
  재실행도 `ENVIRONMENT_INVALID`이며, 요청 처리는 계획대로 끝까지 보존한다.

- **P6-02-A-baseline 재실행, 2026-09-16 20:41:50 UTC:** pressure monitor가
  `Swapins +2,572`, `Pageouts +53,815`, `Swapouts 0`을 관측했다. 직전 보고값
  대비 swap-in이 급증한 이벤트로, 해당 시점의 요청 수는 약 13,896건이었다.
  이 run의 no-pressure 성능 비교 사용을 금지하고, 장기 부하 raw는 계속 보존한다.

원시 자료는 `/private/tmp`에 있으며 영속 저장소가 아니다. P9에서 각 run의 hash와
영속 보관 위치를 추가하지 않는 한 formal 완료로 표시하지 않는다.

## 재시작으로 보존한 무효 시도

- `pair02-A-mixed-status-20260917/`: 이전 runner가 status에 종료 줄을 남긴 뒤
  fresh A가 같은 디렉터리를 재사용해 status identity가 혼합됐다. 원자료는 보존하고
  strict completion gate에서 제외했다.
- `pair03-H-preflight-invalid-20260917/`: P6-02 A listener가 남아 있을 때
  `port_in_use`로 기동하지 못한 시도다.
- `pair03-A-out-of-order-interrupted-20260917/`: 위 H 실패 후 A가 먼저 시작되어
  순서가 깨진 시도다. 일부 요청을 처리했지만 비교 pair에서 제외했다.

2026-09-16 20:59 UTC부터 수정된 `run_p6_remaining.zsh`를 사용해 P6-02 A부터
동일 순서로 새 디렉터리에 재실행한다. `finishedAt`만 있는 preflight 실패나
중단 run은 더 이상 자동 skip하지 않는다.

- **P6-02-A fresh 재실행, 2026-09-16 21:01:35 UTC:** 약 865건 처리 시점에
  `Swapins +31`, `Pageouts 0`, `Swapouts 0`을 처음 관측했다. 이는 `vm_stat`
  시작값 대비 호스트 전체 swap-in 페이지 델타이며, 약 0.5MiB(16KiB page 기준)다.
  수치는 유효한 관측값이나 delta가 0이 아니므로 strict no-pressure gate는
  실패한다. 요청 처리는 중단하지 않고 장기 raw에 보존한다.

- **P6-02-A fresh 재실행, 2026-09-16 21:06:00–21:09:00 UTC:** host counter가
  `Swapins +713`에서 `+745`로, `Pageouts +3,813`에서 `+4,298`로 증가했다
  (`Swapouts 0`). 약 2,984→4,427건 구간의 관측이며, 호스트 swap/page I/O가
  지속되어 no-pressure 증거로 사용할 수 없다. 실행은 계속 보존한다.

- **P6-02-A fresh 재실행, 2026-09-16 21:23:07 UTC:** 약 11,197건 처리 시점에
  host `Swapins +10,162`, `Pageouts +8,909`, `Swapouts 0`을 관측했다. 이전
  관측보다 swap-in이 급증한 명확한 압박 이벤트이며, 이 run은
  `ENVIRONMENT_INVALID`로 고정한다. 요청 처리는 중단하지 않는다.

- **P6-02-A fresh 최종(2026-09-16 21:41:27 UTC):** 20,000/20,000 요청과
  `ready=1`, HTTP status error/transport error 0으로 종료했다. transit service
  p50/p95/p99/max는 `124.11/277.36/537.49/3266.75ms`, RSS
  median/p95/max는 `8447.3/9759.2/9762.6MiB`, heap EU max/OU max는
  `5528/3069.1MiB`였다. unified GC pause는 784회·합계 5731.2ms·p95
  29.39ms·max 147.13ms·Full GC 0이었다. 최종 host delta는 Pageouts
  `+13,669`, Swapins `+14,103`, Swapouts `0`이므로 `ENVIRONMENT_INVALID`이다.
  GC overlap은 GC_FREE 18,788건(p95 257.0ms), GC_OVERLAP 1,212건(p95
  2405.4ms)이다. 분석 결과는 `/private/tmp/otp-baden-http-native-20260917-p6/`
  아래 `pair02-A-summary.json`, `pair02-A-integrity.json`,
  `pair02-A-gc-overlap.json`에 보존했다.

- **P6-03-H-only, 2026-09-16 21:41:30 UTC 이후:** fresh H가 단일 listener로
  기동됐고, 약 855건 시점에 host `Swapins +24`, `Pageouts +450`,
  `Swapouts 0`을 관측했다. 따라서 이 run도 strict no-pressure 증거가 아닌
  `ENVIRONMENT_INVALID` 관찰 run으로 기록한다. 요청은 계획대로 계속한다.

- **P6-03-H-only, 2026-09-16 21:44:12 UTC:** 약 1,186건 시점에
  `Swapins +68`, `Pageouts +3,232`, `Swapouts 0`으로 증가했다. host 압박
  카운터가 계속 변하므로 no-pressure gate 실패 상태를 재확인한다.

- **P6-03-H-only, 2026-09-16 21:51:03 UTC:** 약 4,472건 시점에
  `Swapins +92`, `Pageouts +8,301`, `Swapouts 0`, memory-pressure free 71%를
  관측했다. 압박 상태가 지속되므로 이 run은 no-pressure 비교에서 제외한다.

- **P6-03-H-only, 2026-09-16 21:54:35 UTC:** 약 6,157건 시점에
  `Swapins +252`, `Pageouts +15,781`, `Swapouts 0`, free 74%를 관측했다.
  host swap/page I/O가 계속되어 `ENVIRONMENT_INVALID` 판정을 유지한다.

- **P6-03-H-only, 2026-09-16 22:02:50 UTC:** 약 10,117건 시점에
  `Swapins +304`, `Pageouts +17,951`, `Swapouts 0`으로 증가했다. 압박 관측을
  계속 기록하며 run은 중단하지 않는다.

- **P6-03-H-only, 2026-09-16 22:11:14 UTC:** 약 14,152건 시점에
  `Swapins +880`, `Pageouts +26,481`, `Swapouts 0`으로 증가했다. 호스트
  swap/page I/O가 지속되어 환경 오염 판정을 유지한다.

- **P6-03-A-baseline, 2026-09-16 22:30:50 UTC:** 약 3,415건 시점에
  `Swapins +885`, `Pageouts +7,155`, `Swapouts 0`을 관측했다. fresh A run도
  host 압박 상태이므로 `ENVIRONMENT_INVALID`로 기록하고 요청은 계속 처리한다.

- **P6-03-A-baseline, 2026-09-16 22:39:03 UTC:** 약 7,359건 시점에
  `Swapins +901`, `Pageouts +11,224`, `Swapouts 0`을 관측했다. host 압박이
  지속되는 상태로 no-pressure gate 실패를 유지한다.

- **P6-03-A-baseline, 2026-09-16 22:51:00 UTC:** 약 13,095건 시점에
  `Swapins +1,092`, `Pageouts +15,497`, `Swapouts 0`을 관측했다. host 압박이
  지속되어 환경 오염 판정을 유지한다.

- **P6-03-A-baseline, 2026-09-16 22:59:34 UTC:** 약 17,210건 시점에
  `Swapins +1,152`, `Pageouts +20,187`, `Swapouts 0`으로 증가했다. host 압박
  상태를 계속 기록한다.

- **P6-03-A-baseline 최종(2026-09-16 23:05:29 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP/transport error 0으로 종료했다. transit service
  p50/p95/p99/max는 `122.18/278.75/522.38/3753.10ms`, RSS
  median/p95/max는 `8466.8/9120.9/9399.2MiB`, heap EU max/OU max는
  `5432/3097.1MiB`였다. unified GC pause는 767회·합계 6179.0ms·p95
  33.68ms·max 141.60ms·Full GC 0이었다. 최종 host delta는 Pageouts
  `+20,405`, Swapins `+1,338`, Swapouts `0`이므로 `ENVIRONMENT_INVALID`다.
  GC overlap은 GC_FREE 18,817건(p95 252.4ms), GC_OVERLAP 1,183건(p95
  2400.8ms)이다. summary/integrity/overlap JSON은 P6 root에 보존했다.

- **P6-04-A-baseline, 2026-09-16 23:05:51 UTC 이후:** fresh A가 단일 listener로
  기동됐고 약 274건 시점에 `Swapins +4`, `Pageouts +76`, `Swapouts 0`을
  관측했다. 이 pair도 압박 환경에서 실행을 계속하되 no-pressure 증거에서는
  제외한다.

- **P6-04-A-baseline, 2026-09-16 23:09:14 UTC:** 약 1,701건 시점에
  `Swapins +16`, `Pageouts +7,429`, `Swapouts 0`으로 증가했다. host 압박
  이벤트를 계속 기록하며 run은 중단하지 않는다.

- **P6-04-A-baseline, 2026-09-16 23:16:45 UTC:** 약 5,304건 시점에
  `Swapins +20`, `Pageouts +8,023`, `Swapouts 0`으로 증가했다. no-pressure
  gate 실패 상태를 유지한다.

- **P6-04-A-baseline, 2026-09-16 23:19:42 UTC:** 약 6,726건 시점에
  `Swapins +24`, `Pageouts +16,876`, `Swapouts 0`을 관측했다. host 압박
  상태를 계속 기록한다.

- **P6-04-A-baseline, 2026-09-16 23:27:13 UTC:** 약 10,330건 시점에
  `Swapins +7,450`, `Pageouts +18,291`, `Swapouts 0`으로 급증했다. 명확한
  host swap/page I/O이므로 `ENVIRONMENT_INVALID`를 유지하지만 요청은 계속한다.

- **P6-04-A-baseline, 2026-09-16 23:35:20 UTC:** 약 14,226건 시점에
  `Swapins +13,370`, `Swapouts +5,314`, `Pageouts +23,678`로 증가했다.
  swap-out까지 발생한 명확한 호스트 압박 이벤트이며, 이 run은
  `ENVIRONMENT_INVALID`로 고정한다. 사용자 지시에 따라 요청 처리는 계속한다.

- **P6-03-H-only 최종(2026-09-16 22:23:26 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP/transport error 0으로 종료했다. transit service
  p50/p95/p99/max는 `135.06/294.04/538.19/3643.37ms`, RSS
  median/p95/max는 `9512.7/9660.1/9822.8MiB`, heap EU max/OU max는
  `6080/3025.1MiB`였다. unified GC pause는 1,116회·합계 8,165.1ms·p95
  35.34ms·max 148.41ms·Full GC 0이었다. 최종 host delta는 Pageouts
  `+36,695`, Swapins `+896`, Swapouts `0`이므로 `ENVIRONMENT_INVALID`다.
  GC overlap은 GC_FREE 18,344건(p95 273.6ms), GC_OVERLAP 1,656건(p95
  1875.8ms)이다. summary/integrity/overlap JSON은 P6 root에 보존했다.

- **P6-04-A-baseline 최종(2026-09-16 23:47:22 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP status/transport error 0으로 종료했다. transit
  18,747건의 service latency p50/p95/p99/max는
  `129.71/296.29/600.50/3941.71ms`였고, 전체 20,000건은
  `126.23/305.91/1260.12/3941.71ms`였다. RSS median/p95/max는
  `8339.1/9202.5/9422.8MiB`, heap EU max/OU max는 `5304/3175.6MiB`였다.
  unified GC pause는 789회·합계 6532.0ms·p95 38.68ms·max 168.75ms·Full GC
  0이었다. 최종 host delta는 Pageouts `+40,972`, Swapins `+13,871`,
  Swapouts `+5,314`로 명확한 압박이므로 `ENVIRONMENT_INVALID`이다. GC overlap은
  GC_FREE 18,779건(p95 279.0ms), GC_OVERLAP 1,221건(p95 2430.0ms)이다.
  분석 결과는 P6 root의 `pair04-A-summary.json`, `pair04-A-integrity.json`,
  `pair04-A-gc-overlap.json`에 보존했다. 이후 `pair04-H`를 같은 조건으로
  시작했다.

- **P6-04-H-only, 2026-09-17 00:09:03 UTC:** 약 10,194건 시점에 host
  `Pageouts +19,711`, `Swapins +7,468`, `Swapouts 0`을 관측했다. RSS는 약
  `9,475MiB`, jstat heap은 EU 약 `1,384MiB`/OU 약 `2,898MiB`였고, YGC 330,
  FGC 0이었다. swap/page I/O가 명확하므로 H run도 `ENVIRONMENT_INVALID`이며,
  사용자 지시에 따라 20,000건까지 중단하지 않고 계속 처리한다.

- **P6-04-H-only, 2026-09-17 00:14:14 UTC:** 약 12,675건 시점에 host
  `Pageouts +23,253`, `Swapins +8,136`, `Swapouts 0`으로 증가했다. RSS는
  약 `8,151MiB`, jstat EU/OU는 약 `4,760/2,901MiB`, YGC 398, FGC 0이었다.
  host swap/page I/O가 지속되므로 `ENVIRONMENT_INVALID` 판정을 유지한다.

- **P6-04-H-only 최종(2026-09-17 00:29:21 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP status/transport error 0으로 종료했다. transit
  18,747건의 service latency p50/p95/p99/max는
  `182.12/476.59/1559.72/7298.53ms`였다. RSS median/p95/max는
  `8733.9/9419.4/9530.6MiB`, heap EU max/OU max는 `6112/3857.1MiB`였다.
  unified GC pause는 1,092회·합계 18,177.3ms·p95 60.74ms·max 252.16ms·Full GC
  0이었다. 최종 host delta는 Pageouts `+58,687`, Swapins `+9,616`, Swapouts
  `0`으로 `ENVIRONMENT_INVALID`다. GC overlap은 GC_FREE 17,883건(p95
  412.5ms), GC_OVERLAP 2,117건(p95 2397.4ms)이다. 분석 결과는 P6 root의
  `pair04-H-summary.json`, `pair04-H-integrity.json`, `pair04-H-gc-overlap.json`에
  보존했다. 다음 순서인 `pair05-A`가 fresh JVM으로 시작됐다.

- **P6-05-A-baseline, 2026-09-17 00:30:11 UTC:** 약 505건 시점에 host
  `Swapins +4`, `Pageouts +29`, `Swapouts 0`을 처음 관측했다. 따라서 pair05-A도
  `ENVIRONMENT_INVALID`로 고정하고, 사용자 지시에 따라 전체 20,000건을 계속
  처리한다.

- **P6-05-A-baseline, 2026-09-17 00:37:24 UTC:** 약 3,640건 시점에 host
  `Pageouts +545`, `Swapins +506`, `Swapouts 0`으로 증가했다. RSS는 약
  `9146MiB`, EU/OU는 약 `1256/2901MiB`, FGC 0이었다. 지속적인 host I/O로
  `ENVIRONMENT_INVALID` 판정을 유지한다.

- **P6-05-A-baseline 최종(2026-09-17 01:11:19 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP status/transport error 0으로 종료했다. transit
  18,747건의 service latency p50/p95/p99/max는
  `117.61/266.81/484.33/2805.85ms`였다. RSS median/p95/max는
  `8555.4/9120.0/9624.8MiB`, heap EU max/OU max는 `5360/3137.1MiB`였다.
  unified GC pause는 782회·합계 5814.3ms·p95 29.55ms·max 151.70ms·Full GC
  0이었다. 최종 host delta는 Pageouts `+11,001`, Swapins `+1,069`, Swapouts
  `0`으로 `ENVIRONMENT_INVALID`다. GC overlap은 GC_FREE 18,867건(p95
  246.8ms), GC_OVERLAP 1,133건(p95 2287.9ms)이다. 분석 결과는 P6 root의
  `pair05-A-summary.json`, `pair05-A-integrity.json`, `pair05-A-gc-overlap.json`에
  보존했다. 다음 순서인 `pair05-H`가 fresh JVM으로 시작됐다.

- **P6-05-H-only, 2026-09-17 01:11:37 UTC:** 약 428건 시점에 host
  `Swapins +4`, `Pageouts +41`, `Swapouts 0`을 처음 관측했다. H run도
  `ENVIRONMENT_INVALID`로 고정하고 20,000건까지 계속 처리한다.

- **P6-05-H-only, 2026-09-17 01:15:01 UTC:** 약 1,601건 시점에 host
  `Pageouts +1,079`, `Swapins +24`, `Swapouts 0`으로 증가했다. RSS는 약
  `9027MiB`, EU/OU는 약 `5029/2933MiB`, FGC 0이었다. host I/O가 지속되어
  no-pressure gate 실패를 유지한다.

- **P6-05-H-only 최종(2026-09-17 01:53:16 UTC):** 20,000/20,000 요청,
  integrity `valid=true`, HTTP status/transport error 0으로 종료했다. transit
  18,747건의 service latency p50/p95/p99/max는
  `223.62/536.54/1248.43/6835.95ms`였다. RSS median/p95/max는
  `6993.3/9143.6/9378.9MiB`, heap EU max/OU max는 `6088/3021.5MiB`였다.
  unified GC pause는 1,089회·합계 24,138.5ms·p95 69.13ms·max 144.53ms·Full GC
  0이었다. 최종 host delta는 Pageouts `+20,154`, Swapins `+933`, Swapouts
  `0`으로 `ENVIRONMENT_INVALID`다. GC overlap은 GC_FREE 17,511건(p95
  469.1ms), GC_OVERLAP 2,489건(p95 2419.9ms)이다. 분석 결과는 P6 root의
  `pair05-H-summary.json`, `pair05-H-integrity.json`, `pair05-H-gc-overlap.json`에
  보존했다. 다음 순서인 `pair06-H`가 fresh JVM으로 시작됐다.

- **P6-06-H-only, 2026-09-17 01:53:35 UTC:** 약 263건 시점에 host
  `Pageouts +46`, `Swapins 0`, `Swapouts 0`을 관측했다. swap-in은 아직 없지만
  pageout이 비제로이므로 strict no-pressure gate는 즉시 실패했으며, H run을
  `ENVIRONMENT_INVALID`로 기록하고 계속 처리한다.

- **P6-06-H-only 중단(2026-09-17 02:30 UTC경):** 사용자의 중단 요청에 따라
  실행 중인 Java와 후속 P6 대기 스크립트를 종료했다. 요청 로그는
  `17,602/20,000`건(모두 HTTP 200, transport error 0)이며, `finishedAt`·server/
  generator exit는 기록되지 않아 integrity `valid=false`다. 부분 표본의 transit
  service p50/p95/p99/max는 `201.79/542.86/1421.19/9067.56ms`, RSS
  median/p95/max는 `7947.0/9106.9/9502.1MiB`, heap EU max/OU max는
  `6096/3675.8MiB`였다. unified GC pause는 963회·합계 26,593.3ms·p95
  101.5ms·max 363.1ms·Full GC 0이었다. 측정 후 host counter snapshot은
  중단으로 남지 않았지만, pressure log에는 02:28:43–02:28:46 UTC 기준
  swap used 약 `1049.94MiB`, Pageouts 약 `61,932`, Swapins `1,666`가 기록되어
  strict no-pressure gate 실패를 확인한다. GC overlap은 GC_FREE 15,499건
  (p95 467.0ms), GC_OVERLAP 2,103건(p95 2247.3ms)이다. 이 run은 완주 표본이나
  paired bootstrap에 포함하지 않는다.

- **P6 중단 후 정리(2026-09-17 02:34 UTC):** 현재까지의 4개 완전 pair
  (pair02–05)와 pair01의 유효/무효 기록, pair06-H partial을 재분석해
  `p6-run-summaries.json`, `p6-paired-bootstrap.json`, `p6-gc-overlap.json`,
  `p6-integrity.json`을 생성했다. bootstrap은 완료 4개 pair만 포함하며
  no-pressure pair는 `0/4`다. raw 전체는 `.artifacts/otp-baden-p6/`에
  `archiveMode=partial-stop`으로 압축했고 `SHA256SUMS.txt` 검증을 통과했다.
