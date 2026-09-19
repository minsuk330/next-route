# PR evidence — OTP A/H/F/HF optimization

갱신: 2026-09-15. 범위: `codex/otp-phase0-3` 기반 H/F 코드 최적화의 현재 근거 수준. **P4 성능 결론은 미확정이며 이 문서는 성공 주장을 승인하지 않는다.**

## 현재 근거로 말할 수 있는 것

- 고정한 두 OD의 correctness query에서 A/H/F/HF 응답 fingerprint mismatch가 0이었다. 범위는 그 corpus뿐이며 전국·전체 OD 정합성 증명은 아니다.
- H는 timetable 배열 교체 대상의 원소 payload 산식을 200-request 진단 배치에서 42.07GB→0.659GB로 낮춘다(약 98.43%). 이는 두 배열의 원소 payload 모델이지 전체 allocation·retained heap·RSS 감소율이 아니다. JFR sampled allocation도 같은 방향을 보였다.
- F는 관측한 두 OD의 `Path.dummyPath` allocation site weight를 낮췄다. 직접 카운터상 comparator 호출 수와 candidate-set histogram은 A/H/F/HF에서 같았다. CPU self-time과 전체 latency 개선은 증명되지 않았다.
- P4 A reference는 4개 OD에 100 measured fingerprints씩 포함하고, 별도 A run과 offline 비교했을 때 각 OD mismatch 0이었다. A baseline 재현성 결과와 HF full-detail 비교 결과는 별도로 구분한다.
- 별도의 full-detail correctness query에서 A/HF 네 OD 각 1회, 성공 4/4, `mismatchCount=0`, `manifestMismatch={}`였다. 노선·정류장/여행 ID, 시간, 좌표와 geometry까지 비교했다. 이는 네 고정 OD에 대한 scoped correctness evidence이지 전체 OD나 성능 우위 증명은 아니다.
- 기존 VPS 관측은 별도 환경에서 H+F의 운영상 메모리/GC 특성을 보여 준다. 이 자료만으로 충분한 heap에서 CPU 성능 우위까지 추론하지 않는다.

## 아직 주장할 수 없는 것

- “넉넉한 heap에서도 HF가 A보다 빠르다”, “모든 OD/동시성에서 빠르다”, 또는 P4 공식 비교를 통과했다.
- H payload 모델 감소가 실제 전체 heap/RSS를 같은 비율로 줄인다.
- F latency 변동의 원인이 JIT 인라이닝 실패, comparator 증가, 후보 증가라는 설명. 관측한 call site/OD에서는 이를 지지하지 않았다.
- P4 A feasibility 세 run의 p50/p95를 성능 비교 근거로 사용한다. 세 run 모두 host swap-in gate 미통과다. 첫 비교의 기준 corpus는 두 OD가 빠졌지만, 두 번째 A run이 전체 4-OD reference를 만들었고 세 번째 run에서 이 기준과 mismatch 0을 재확인했다. HF는 별도 correctness query에서 네 고정 OD에 대해 1회씩 일치했지만, 이것은 성능 우위나 광범위한 정확성 증명이 아니다.

## 공식 성능 근거에 필요한 미완료 항목

참조: [P4 feasibility 판정](ABLATION-P4-FEASIBILITY.md), [실행 계획](LARGE-HEAP-AGENT-PLAN.md), [종합 결과](ABLATION-RESULT.md), [run ledger](ABLATION-RUN-LEDGER.md).

- swap-in/out delta 0인 측정 host에서 feasibility gate 통과 (현재 Mac에서 4-OD A/HF full-detail correctness는 scoped pass)
- 같은 host에서 A/HF c1·c2·c4 각각 최소 5 AB/BA pair, 총 30회
- 요청 표본이 아닌 paired run 단위 bootstrap 10,000회 및 OD별 p50/p95/RSS CI
- 정합성·RSS/GC 수집 coverage·OOM·cleanup gate 통과 기록

P4의 결과가 개선, 회귀, 또는 차이 불명확 중 무엇이든 그대로 보고한다. P4가 완료되기 전에는 PR 설명에서 performance win을 확정형으로 쓰지 않는다.

## Baden H-only HTTP 지속 부하 — 중단 시점 자료 (2026-09-17)

공식 Baden-Württemberg `travelSearch.csv`에서 만든 동일 open-loop HTTP workload를
두 JAR로 실행했다. 역할은 basename이 아니라 소스/바이트코드와 SHA로 고정한다.

- A = upstream 원본: `arrivalTimes[]`/`departureTimes[]` 평탄 배열 복사,
  SHA `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6`
- H = 최적화: `TripTimes[]`/`offsetsByTrip` 참조,
  SHA `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d`

역할의 소스·SHA 원본 기록은 [H-ONLY-A-H-ROLE-DEFINITION.md](H-ONLY-A-H-ROLE-DEFINITION.md)다.

실제 legacy basename은 각각 `otp-h-only.jar`(A)와 `otp-baseline.jar`(H)였고,
파일명은 역할 근거로 사용하지 않는다. pair02–05의 4개
완전 pair는 모두 HTTP/transport error 0 및 fingerprint mismatch 0이었지만, 모든
run에서 macOS host pageout 또는 swap-in delta가 0이 아니었다. 따라서 이 자료는
`ENVIRONMENT_CONTAMINATED / DIAGNOSTIC_ONLY`이며 성능 승리나 no-pressure 운영
효과의 PR 근거로 사용할 수 없다.

P6의 6-pair 계약은 사용자의 중단 요청으로 끝까지 수행하지 않았다. raw `pair06-H`
(semantic A 원본)는 17,602/20,000건에서 중단됐고 raw `pair06-A` 및 pair01 교정 pair는
미실행이다. 이
partial 로그와 완료 run의 GC-overlap, RSS/heap, NMT, paired bootstrap(완료 4 pair)은
재현성·후속 실험 설계를 위한 진단 자료로만 보존한다.

상세 결과: [H-ONLY-BADEN-HTTP-NO-PRESSURE-P6-FORMAL-RESULT.md](H-ONLY-BADEN-HTTP-NO-PRESSURE-P6-FORMAL-RESULT.md)
및 [H-ONLY-BADEN-HTTP-NO-PRESSURE-RUN-LEDGER.md](H-ONLY-BADEN-HTTP-NO-PRESSURE-RUN-LEDGER.md).
재현 자산과 실행 명령은 [H-ONLY-BADEN-HTTP-NO-PRESSURE-MANIFEST.json](H-ONLY-BADEN-HTTP-NO-PRESSURE-MANIFEST.json),
raw archive는 `.artifacts/otp-baden-p6/`의 `SHA256SUMS.txt`에 기록했다.
