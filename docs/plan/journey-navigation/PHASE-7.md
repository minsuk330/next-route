# Phase 7 — 복구·보존·개인정보·운영 계측

- 상위: [PLAN.md §Phase 7](./PLAN.md) · 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md) O1~O17
- 선행: Phase 1~6 전부 main. Phase 6 은 ⑤(수락)까지로 마감(SC-18)
- 작성일: 2026-08-13

## 0. 이 단계가 다른 단계와 다른 점

Phase 1~6 은 **사용자에게 보이는 기능**을 한 줄기로 쌓았다. Phase 7 은 그렇지 않다 — 마스터 PLAN 의
목록이 **성격이 다른 네 덩어리를 한 자리에 묶어 둔 것**이다.

| 덩어리 | 성격 | 안 하면 어떻게 되나 |
|---|---|---|
| **A. 복구** | 서버·Redis 재시작 뒤 진행 중인 여정을 되살린다 | 배포 한 번에 **진행 중인 안내가 전부 멈춘다.** 사용자는 앱이 켜져 있는데 아무 알림도 못 받는다 |
| **B. 보존(retention)** | 끝난 여정·알림·telemetry 를 기간 뒤 지운다 | 표가 무한히 자란다. 지금 디스크가 17G 다 |
| **C. 개인정보** | 계정 삭제 시 즉시 차단 + 24시간 내 물리 삭제, 로그 마스킹 | 법적 요구다. 그리고 **되돌릴 수 없는 실수의 영역**이다 |
| **D. 계측·SLO** | O1~O17 지표·대시보드·SLO 분모 정의 | 무엇이 고장났는지 못 본다. 이미 관측 3축은 살아 있다 |

**넷을 한 PR 로 묶으면 안 된다.** A 는 배포 안전성, B 는 디스크, C 는 법적 요구이자 파괴적 작업,
D 는 관측이다. 실패 시 대가도, 되돌리는 방법도 전부 다르다.

---

## 1. 지금 있는 것 / 없는 것 (2026-08-13 코드 확인)

### A. 복구

| 부품 | 상태 |
|---|---|
| `JourneyDueEvaluatorScheduler` | 있다. `next_evaluation_at` 이 지난 여정을 집어 평가한다 |
| `JourneyLeaseExpiryScheduler` | 있다. lease 가 끝난 여정을 `EXPIRED` 로 접는다 |
| `JourneyNotificationRelayLoop` | 있다. PENDING 을 claim 해 발송한다 |
| `JourneyNotificationOutboxStateService.reclaimStale` | **있다**(`:57`) — 고착 `PROCESSING` 회수는 이미 동작한다 |
| **due index rebuild** | **필요 없다.** 평가 대상은 `journey_session.next_evaluation_at`(DB)이 소유하고 Redis ZSET 는 도입하지 않았다(journey-load-harness RESULTS 결론). 재시작해도 DB 에 그대로 있다 |
| **ACTIVE Journey bootstrap** | **필요 없다 — 이미 자기치유다.** §1-b 참고 |
| Redis 에만 있는 상태 | 재탐색 cooldown·남은 구간 캐시·실시간 캐시. **전부 잃어도 기능이 살아야 하는 값**으로 설계돼 있다(fail-open) |

→ **A 는 이미 서 있다.** 남은 것은 "재시작 뒤 실제로 재개되는가"를 **시험으로 못 박는 일**뿐이다.

### 1-b 왜 bootstrap 코드가 필요 없나 (실측)

`claimDueBatch`(`JourneySessionRepository.java:163-177`)가 집는 조건이 전부다.

```sql
WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL
  AND s.next_evaluation_at IS NOT NULL
  AND s.next_evaluation_at <= :now
ORDER BY s.next_evaluation_at
```

- **평가 대상은 DB 가 소유한다.** 재시작해도 `next_evaluation_at` 이 그대로 남아 있고, 시각이
  과거이므로 다음 tick(기본 5초)이 **오래 기다린 것부터** 집는다. 되살릴 목록을 따로 만들 필요가 없다
- **평가 중 죽은 워커도 스스로 풀린다.** claim 이 `next_evaluation_at` 을 `now + lease`(기본 60초)로
  덮으므로, 그 워커가 죽으면 **최대 60초 뒤** 다른 워커가 같은 여정을 집는다. 잃은 소유권으로 늦게
  끝난 워커가 최신 결과를 덮는 것은 `finishEvaluation` 의 CAS 가 막는다
- **`EXPIRED` 는 복원되지 않는다.** `status = 'ACTIVE'` 조건이 그것을 보장한다 — 요구사항의
  "종료 이후 평가·알림 중단"이 코드 한 줄로 이미 지켜져 있다
- **고착 `PROCESSING` 회수도 있다** — `JourneyNotificationOutboxStateService.reclaimStale`(`:57`)
- **Redis 에만 있는 값은 전부 fail-open 설계**다(재탐색 cooldown 은 fail-closed 지만, 잠금이 열려
  통과가 늘어날 뿐 기능이 죽지 않는다)

**남는 진짜 위험은 "60초"라는 숫자 하나다.** 배포로 워커가 죽으면 그 순간 평가 중이던 여정이 최대
60초 늦게 재개된다. 대기 단계 평가 주기가 10초인 것에 비하면 6배다 — 그 지연을 줄이려면
`@PreDestroy` 로 진행 중 claim 의 `next_evaluation_at` 을 되돌려 놓아야 한다(PR-1 의 선택지).

### B. 보존

| 부품 | 상태 |
|---|---|
| 여정 표(session·leg·revision·event·transfer·notification·telemetry·proposal) | 삭제 job **없음** |
| `bus_arrival_raw` 등 수집 표 | ML 쪽에 VPS cron retention 이 있다(gdrive archive 계열) |
| Loki·Prometheus | 30일 retention 설정돼 있다(`ops/observability`) |
| `journey_telemetry_event` | V41 로 표는 있고 **`telemetryId` unique·30일 보존은 없다** |

→ **B 는 통째로 새로 만든다.** 선례는 ML 쪽 cron 이고, 여정 표는 애플리케이션 배치가 맞다(FK 순서 때문).

### C. 개인정보

| 부품 | 상태 |
|---|---|
| 계정 삭제 API | **없다.** `TossUnlinkCallbackController`(토스 연결끊기 콜백)만 있다 |
| `UserPushToken` | 표·저장소 있다. 삭제 경로는 확인 필요 |
| 정밀 좌표 미저장 | **이미 계약이다** — progress 는 요약만 저장한다(`JourneyProgressService` 주석) |
| 로그 마스킹 | 확인 필요. push token·좌표가 로그에 나가는지 |
| telemetry 원본 보존 | 없다(B 와 같은 작업) |

→ **C 가 가장 위험하다.** 물리 삭제는 되돌릴 수 없고, FK 가 걸린 표를 순서대로 지워야 한다.

### D. 계측·SLO

| 부품 | 상태 |
|---|---|
| 관측 3축(Loki·Prometheus·Tempo) | **라이브**(grafana.refit-100.site) |
| 여정 지표 | `JourneyEvaluationMetrics`·`JourneyNotificationMetrics`·`JourneyRerouteMetrics`·`JourneyTelemetryMetrics` 있다 |
| telemetry 수집 API | 있다(`JourneyTelemetryController`, Phase 4) |
| **SLO 분모 정의** | 없다. "예상 4xx 제외 API 성공률" 같은 정의가 코드/대시보드에 없다 |
| 대시보드 | 관측 대시보드는 있고 **여정 전용 패널**은 확인 필요 |
| O4·O6·O7(예측 정확도 후검증) | **없다.** 실제 탑승 결과와 예측을 맞대는 배치가 필요하다 |

→ **D 는 절반 있다.** 남은 것은 SLO 정의·여정 대시보드·예측 후검증이고, **후검증은 별개 프로젝트 크기**다.

---

## 2. PR 분할 (제안)

| | 슬러그 | 티어 | 내용 | 왜 이 순서 |
|---|---|---|---|---|
| **PR-1** | `journey-restart-recovery` | **S** | 재시작 복구를 시험으로 못 박는다(+ 선택: graceful shutdown 으로 60초 지연 단축) | 복구는 이미 동작한다(§1-b). **시험이 없어서 다음 사람이 그것을 모르는 것**이 문제다 |
| **PR-2** | `journey-retention-job` | **M** | 끝난 여정·알림·telemetry 30일 보존 배치(worker 프로필) | 디스크 17G. 표가 자라는 속도를 지금 재 두는 것이 낫다 |
| **PR-3** | `journey-telemetry-hardening` | **S~M** | `telemetryId` unique + 보존 기간(마이그레이션 1건) | PR-2 와 같은 표를 건드리므로 붙여서 한다 |
| **PR-4** | `journey-account-deletion` | **L** | 계정 삭제: 즉시 차단 → push token 제거 → 24시간 내 물리 삭제 | **되돌릴 수 없다.** 앞의 셋으로 삭제 순서·FK 를 이미 익힌 뒤에 한다 |
| **PR-5** | `journey-slo-dashboard` | **M** | SLO 분모 정의 + 여정 전용 Grafana 대시보드 | 앞의 것들이 만든 지표를 모아 본다 |
| **후속** | `journey-prediction-feedback` | **L** | O4·O6·O7 — 실제 결과로 ETA·확률 후검증 | 별개 프로젝트 크기다. Phase 7 에서 떼어낸다 |

**PR-1·PR-2·PR-3 은 서로 독립**이라 병렬 가능. PR-4 는 앞의 것들 뒤, PR-5 는 마지막.

## 3. 착수 전 정할 것

- **PR-1(확인 완료)**: 방치는 **없다**(§1-b). 그래서 티어는 **S** — 시험만 추가한다. 남은 선택은
  하나: 배포 시 최대 60초 재개 지연을 `@PreDestroy` 로 줄일지. **넣지 않는 쪽을 권한다** —
  진행 중 claim 을 되돌리는 코드가 새 경합(되돌리는 사이 다른 워커가 집음)을 만들고, 60초는
  사용자가 알림 하나를 늦게 받는 정도다
- **PR-2**: 보존 기간의 소유자 — 설정값(`journey.retention.*`)으로 두고 기본 30일
- **PR-4**: 삭제의 의미 — **물리 삭제**인지 익명화인지. 마스터 PLAN 은 "24시간 이내 물리 삭제"라고
  적었으나, 그러면 ML 학습 데이터(`bus_arrival_raw` 등)에 남은 흔적과의 관계를 정해야 한다
- **마이그레이션 번호**: 지금 **V43 까지 썼다**. PR-3 이 V44 를 쓰면 보류 중인 M7 플랜(V43~V45 로
  적혀 있음)과 다시 어긋나므로, **M7 착수 시점에 그쪽 번호를 밀기로 이미 정했다**

## 4. 이 문서의 상태

§3 의 확인 항목(재시작 방치 여부)은 **이 문서를 쓰면서 코드로 확인했다** — 방치는 없고 PR-1 은
**S** 다(§1-b).

**착수 순서 권고**: PR-2(보존) → PR-1(복구 시험) → PR-3(telemetry) → PR-4(계정 삭제) → PR-5(SLO).
PR-2 를 먼저 두는 이유는 **표가 자라는 속도를 지금 재 두는 것이 가장 시급**하기 때문이다(디스크 17G).
PR-1 은 급하지 않다 — 기능은 이미 동작하고 시험이 없을 뿐이다.
