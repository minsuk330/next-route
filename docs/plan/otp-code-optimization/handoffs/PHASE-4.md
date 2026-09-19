# Phase 4 handoff — 최종 동시 개선 검증 (이전 계약 기록)

> 이 문서의 `COMPLETE / VERIFIED`는 이전 `-Xmx3500m` 및 이전 workload 계약의 historical 판정이다. 현재 상위 계획의 상태는 `P4_FORMAL_PENDING`이며, 최신 작업은 [LARGE-HEAP-AGENT-PLAN.md §P4-R](LARGE-HEAP-AGENT-PLAN.md#p4-r--데이터-25에서-메모리-압박-없는-성능-회귀-검증-다음-작업)의 GTFS 약 25% 축소 graph에서 A/HF 지연 비열등성 검증이다. 아래 수치와 상태는 삭제하지 않고 과거 근거로 보존한다.

- Phase 상태: COMPLETE
- 현재 최종 후보 H+F 상태: VERIFIED
- 이전 B/C/G/F/H 조합 상태: NOT_MET (c1 개별 OD 지연 회귀)
- 작업 위치: `/Users/wa/golbob/개인/OpenTripPlanner`
- branch: `codex/otp-phase0-3`
- baseline commit: `02c0b4b537eb07bb0b284726397821682248fe20`
- graph: `/private/tmp/otp-seoul-dev-graph/graph.obj`
- graph SHA-256: `8b597d8bbc1c8588acf0c829735da7ff22ea9d160bd7cb272e23a11ea3002385`

## 완료한 검증

이전 작업트리 B/C/G/H/F shaded JAR(`/private/tmp/otp-h-final.jar`)과 원본 A를
동시성 1에서 안정화 예열 100회/OD, 측정 100회/OD로 세 쌍 교차 측정했다.
각 실행은 400/400 성공, RSS `OBSERVED`, 컨테이너 OOM 없음이었다.

| 쌍 | p50 개선 | p95 개선 | peak RSS 개선 |
| --- | ---: | ---: | ---: |
| 1 | +6.96% | −4.40% | +44.35% |
| 2 | +8.36% | +6.45% | +40.63% |
| 3 | +7.24% | −0.10% | +40.69% |

3쌍 중앙은 p50 `+7.24%`, p95 `−0.10%`, RSS `+40.69%`다. 특히
`cityhall-gangnam` p50이 `−7.76%`, `−5.71%`, `−6.39%`로 세 번 연속 5%를
넘게 악화했다. 계획의 개별 OD 회귀 기준을 위반하므로 B/C/F/H는 c1 gate에서
`NOT_MET`으로 조기 탈락했고, 이 조합의 c2/c4 추가 측정과 soak는 생략한다.

이전 조합의 전체 Maven test는 총 8,225개 실행, failures/errors 0, skipped 20,
`BUILD SUCCESS`였다. 해당 correctness corpus는 14건(성공 8, 의도된 routing error 6),
validation error 0, 원본 raw 대비 mismatch 0이었다.

## H+F 최종 후보 근거

H+F 후보 JAR(`/private/tmp/otp-trip-times-candidate.jar`, SHA-256
`28eb50f341fcbbe712ec2f10cdc76b7eb977a3b3d966ba825c4396b138849236`)는 B/C가
제외된 소스에서 생성됐다. c1 10쌍, c2/c4 5쌍, c4 30분 soak가 모두 계획의
성능 조건을 통과한 기록이 있다. correctness도 새로 재실행해
`/private/tmp/otp-hf-final-corpus-2`에서 14건, mismatch 0을 확인했다.

H+F 결과는 이전 B/C/G/F/H 결과와 합산하지 않는다. B/C/G 구현은 실제 작업트리에서
제거했고, 현재 주 코드는 H+F와 일치한다. 기존 H+F 정식 gate의 JAR와 현재 재빌드
JAR는 `otp-project-info.properties`를 제외한 모든 class entry가 동일하다.

현재 H+F shaded JAR는 `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`
(SHA-256 `54bbdbcf7b6df4b110b7e7621eeea3f49d653b51248b59fa367a43bc2ec0db7f`)이며,
correctness 14건 mismatch 0과 영향 모듈 전체 8,225개 테스트·측정기 테스트 33개를
통과했다. 따라서 H+F의 계획상 최종 상태는 `VERIFIED`다.

## 후속 작업

1. B/C/G 회귀 테스트는 실패 가설의 증거로 보존하고, 최종 성능 근거에는 포함하지 않는다.
2. 커밋·PR·배포는 별도 요청 없이는 하지 않는다.
