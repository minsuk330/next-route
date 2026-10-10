# PLAN v1 리뷰 (Opus, 2026-10-10, 기준 bb1aba23) — 요약과 반영

| # | 지적 | 근거 | PLAN v2 반영 |
|---|---|---|---|
| 🔴R1 | `collectedAt` 은 수집마다 새로 찍힌다(`RealtimeSubwayCollector:100`). `eta + (seenAt−observedAt)` 를 tick 마다 재계산하면 행이 멈춘 동안 예정이 15s 씩 밀려 LATE·STALE 이 영영 안 뜨고 refreshed(225-227)가 깨져 ANCHOR push 연발 | 직접 확인 | sourceSeenAt = "그 recptnDt 를 처음 본 서버 시각"(상태에 고정), 보정은 앵커 생성 시 1회, recptnDt 240s 초과 STALE 이중 가드 |
| 🔴R2 | TYPICAL 직선이 recptnDt(1~2분 늦음)에 앵커돼 첫 관측에 이미 예정 경과 → 0→cap 점프(journey 202 재현), validUntil 즉시 만료(382) | `advanced` 332·`hold` 246·`anchored` 382 | 앵커 = sourceSeenAt, 시간축 계약을 PR-1, LATE 보정을 PR-2 로 당김 |
| 🔴R3 | 정차 직선이면 `estimatedNextStationAt` = 정차 끝 → `alightingAtOf` 가 운행 한 구간만큼 이르게 계산 | UpdateService 171-188, PayloadFactory 148-155 | 정차+운행 단일 직선으로 예정 의미 유지(R3 소멸) |
| 🔴R4 | 위치 DEPARTED 뒤 늦은 PREV_ARRIVED 가 정차 직선을 다시 만들어 링 정지·문구 오류 | `withAnchor` 184 | 단계 순서 DWELL<RUN<LATE<APPROACH<ARRIVED, 역행 관측은 refreshed |
| 🟡Y1 | 역당 push 현행 5~6 ≈ 변경 후. R1 미해결 시 30s 버킷마다 증가. `progressTarget` 버킷은 중복 | Decider 91-106 | 같은 관측 refreshed 계약, 시나리오 IT 역당 ≤6, progressTarget 지문 제외 |
| 🟡Y2 | 고정 15% 분할은 속도 두 번 꺾임 + 전환 push 1건 → 정차+운행 단일 직선 대안 | — | 채택 |
| 🟡Y3 | LIVE 는 실제 시간축, 보정 TYPICAL/MEASURED 는 늦은 축 → 출처 전환 시 ETA 튐 | SignalReader 177 | 앵커를 서버 최초 감지 시각으로(지연 ≤ ~20s), 전환 튐 < 원천 지연 시험 |
| 🟡Y4 | PR-1 도 지문 변화로 진행 중 링마다 일회성 ANCHOR push | — | 허용 명시, typical 은 현재 구간만 계산(`loadLater` 129) |
| 🟡Y5 | `advanced()` 가 승차역 도착(372)과 역 출발(161·318) 두 사건 처리 | — | PR-2 에서 분리 |
| 🟡Y6 | 위치 로그 6필드(`RedisSubwayTrainLogAdapter` 222-245) — 7번째 필드로 서버 시각 추가 가능 | — | 대신 상태의 sourceObservedAt 비교로 해결(로그 포맷 불변) |
| 🟢G1 | Redis 롤백 안전 확인(Boot 기본 매퍼, 레코드 정규 생성자, IT 147) | — | 새 enum 도 값 추가 금지 명시 |
| 🟢G2 | 계산기 22-50 "모르면 멈춘다" Javadoc 수정 누락 | — | PR-3 체크리스트 |
| 🟢G3 | GTFS 정차(`arrivalGap − run`)가 다른 패턴에서 뽑힐 수 있음(`SubwayLineOrder` 54-99) | — | 같은 패턴·음수→default 시험 |
| 🟢G4 | PASSED(89)·승차역(seq 0) 안전 | — | — |

빠진 수용 시험 8개(행 고정 STALE, 같은 관측 push 0, 단계 역행, 정차 중 하차 예정, 짧은 구간 점프 없음, 패턴 캐시 빔, validUntil > now, 출처 전환 튐) → PLAN v2 각 PR 시험에 배치.

판정: "리스트대로 고친 뒤 진행" — R1·R2 시간축 결정을 PR-1 계약으로 확정.
