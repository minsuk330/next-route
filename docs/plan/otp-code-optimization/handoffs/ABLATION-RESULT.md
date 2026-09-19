# A/H/F/HF 큰 heap 실험 종합 결과

작성: 2026-09-14. 갱신: 2026-09-15. 상태: **P2 탐색 비교 완료, P3 JFR 및 직접 카운터 진단 완료, P4 formal no-swap gate 미통과. 현 자료만으로 “충분한 heap에서 최적화본이 더 빠르다”는 PR 주장은 확정할 수 없다.**

## 핵심 결론

- H는 두 OD JFR에서 timetable constructor allocation과 `int[]` sampled weight를 크게 낮췄다. 큰 heap에서 메모리·GC 측면의 가장 명확한 후보이며, P2 p50도 두 OD 모두 A보다 빠른 방향이었다.
- F는 `Path.dummyPath` 표본 allocation을 없앴지만 F-only p50은 P2 4개 블록 중 clean하게 쓸 수 있는 3개에서 A보다 느린 방향이었다. 시청→강남은 +15–20%, 부평→서울은 더 작은 +2–10% 범위다. F 회귀의 원인은 아직 확정하지 못했다.
- HF는 H의 메모리 이득을 유지하고 F의 dummy Path allocation도 줄이지만, P2에서는 시청→강남 지연 열세와 OD별 혼합 결과가 있었다. 단일 “항상 빠름” 결론은 안 된다.
- F가 바꾼 `MutableDummyPath` receiver가 comparator getter inlining을 깨뜨린다는 단순 가설은 추가 JFR에서 지지되지 않았다. 관측 call-site에서 MutableDummyPath getter와 comparator scalar chain이 인라인됐다. 직접 카운터에서도 두 OD의 F/HF comparator 호출 수와 candidate-set histogram은 A/H와 정확히 같았다. 따라서 호출량·후보 폭증은 F 회귀 설명으로 지지되지 않는다. H accessor 호출 수 역시 A/H/F/HF가 같지만 접근당 비용은 미측정이다.
- P4 feasibility A run 세 번은 모두 c1에서 warmup 안정화와 400/400 성공을 보였지만 host swap-in이 +7,675/+250/+88 pages(약 119.9/3.91/1.38MiB) 증가해 모두 formal no-swap gate를 통과하지 못했다. 첫 run은 잘못된 2-OD baseline과 비교해 신규 OD 200건이 baseline 부재로 mismatch 처리됐다. 두 번째 run은 전체 4-OD A reference를 생성했고, 세 번째 run은 이를 기준으로 400/400 fingerprints mismatch 0을 재확인했다. 별도의 full-detail correctness query에서도 A/HF는 네 OD 각각 1회 비교에 mismatch 0을 보였다. 정식 성능 비교용 30-run P4 matrix는 시작하지 않았다. [pilot·correctness 상세](ABLATION-P4-FEASIBILITY.md).
- 30-run P4 formal no-swap evidence는 아직 없다. 일부 P2/P3 실행에서 host swap I/O가 있었고, JFR latency와 P4 pilot latency는 diagnostic-only다.

## P2 10GB 탐색 요약

P2는 두 OD, c1, 균형 순서 4블록, A/H/F/HF 16개 유효 셀이다. 각 유효 셀은 400/400 요청 성공, candidate fingerprint mismatch 0이었다. P2 block 3 F와 sampler가 끊긴 block 4 HF 첫 시도는 각각 swap 영향·RSS 불완전으로 clean medians에서 제외했다.

| 변형 | 시청→강남 p50 median | 부평→서울 p50 median | RSS peak median | GC pause median |
| --- | ---: | ---: | ---: | ---: |
| A | 406ms | 215ms | 3.31GB | 1,542ms |
| H | 369ms (−9%) | 167ms (−22%) | 2.63GB (−20%) | 371ms (−76%) |
| F* | 486ms (+20%) | 232ms (+8%) | 3.23GB (−3%) | 935ms (−39%) |
| HF | 464ms (+14%) | 189ms (−12%) | 2.60GB (−21%) | 293ms (−81%) |

`*` F는 block 3의 큰 swap I/O를 중앙값에서 제외했다. A 자체의 OD별 p50은 블록에 따라 cityhall 약 12%, bupyeong 약 8% 흔들렸다. 따라서 이 표는 인과 효과나 CI가 아니라 screening이다. 상세 cell·순서·host-counter delta는 [run ledger](ABLATION-RUN-LEDGER.md)에 있다.

## P3 현재 판정

두 OD A/H/F/HF standard JFR의 measured window를 분석했고, cityhall 네 변형은 inlining event를 켠 custom JFR로 추가 확인했다. 여기에 별도 계측 JAR로 직접 counter 8개 실행(두 OD × A/H/F/HF, 각 200회)을 완료했다. **H의 schedule-array 원소 payload 모델 감소와 F의 dummy Path allocation 제거는 각각 JFR 및 직접 counter/receiver 자료와 부합한다. F의 인라이닝 실패·비교 횟수 증가 가설은 관측 범위에서 지지되지 않는다.**

JFR 상세는 [ABLATION-P3-JFR-RESULT.md](ABLATION-P3-JFR-RESULT.md), 정확한 차원·호출 수와 payload 산식은 [ABLATION-P3-COUNTER-RESULT.md](ABLATION-P3-COUNTER-RESULT.md)를 참조한다. 이 실행의 지연값은 계측 오버헤드·예열 조건 때문에 성능 비교에서 제외한다.

## PR에 쓸 수 있는 주장

- 현재 탐색/JFR 자료는 H가 sampled allocation, RSS, GC를 줄이는 방향이라는 근거다.
- 현재 탐색/JFR 자료는 F가 해당 임시 dummy Path allocation을 줄인다는 근거다.
- H/F/HF는 고정 두 OD의 routing response fingerprint에서 mismatch 0을 보였다.

## 아직 주장할 수 없는 것

- “H+F는 heap이 충분해도 기존보다 항상 빠르다.”
- “F의 지연 열세 원인은 JIT 인라이닝 실패다.” (관측한 JIT event와 오히려 맞지 않음.)
- “H의 accessor 호출 비용은 의미 없다.” (호출 수가 같음은 확인했지만 접근당 self-time/cache 비용은 미계측.)
- “JFR weighted MB는 실제 bytes allocated, retained heap, RSS 감소량이다.”
- “P4 formal no-swap paired gate를 통과했다.”
- “세 P4 feasibility A run의 p50/p95를 성능 근거로 채택한다.” 세 run 모두 host swap-in gate 미통과다. A/HF correctness는 네 고정 OD에서 통과했지만 성능 비교가 아니므로 성능 결론은 없다.

다음 순서는 이미 수행한 A/HF correctness evidence를 보존하고, swap-in/out이 0인 controlled host에서 feasibility gate를 통과한 뒤 A/HF AB/BA P4 matrix를 수행하는 것이다. 새 architecture/host면 correctness query도 해당 환경에서 재확인한다. P4의 목적은 성능 결과를 확정하는 것이며 개선을 전제하지 않는다. PR 주장 범위는 [PR-EVIDENCE.md](PR-EVIDENCE.md)에 따르고, 현재 결론은 그 문서의 “아직 주장할 수 없는 것”에 제한된다.
