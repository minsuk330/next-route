# 알림 효과 계측 (Phase 5 ⑦, L) — 실행 플랜

- 슬러그: `journey-alert-telemetry`
- 브랜치: `feat/journey-alert-telemetry` (**fresh main 기준 독립 브랜치**)
- 마이그레이션: **V41 `journey_telemetry_event`**
- 선행: ②③④⑤⑥ 전부 main 머지 완료
- 상위: [PHASE-5.md](../journey-navigation/PHASE-5.md) §⑦ / REQUIREMENTS §6 O8·O11~O15 / SCOPE-CHANGES SC-3 4항
- **티어 승격**: PHASE-5 표는 M 이었으나 API·스키마 신설이 붙어 L 로 올린다
- **codex 설계 리뷰 반영 완료(2026-08-11).** 지적의 처분은 §11

## 0. 한 줄

**앱이 "알림을 눌렀다·화면에 들어왔다·행동했다"를 보내 줄 창구를 만들고, 그 원본을 한 표에
쌓는다. 같은 기록이 두 번 와도 한 번만 센다.**

## 1. 왜 서버 지표만으로는 안 되나

발송 쪽은 이미 다 잰다 — ③이 넣은 `journey.notification.{enqueued,claimed,sent,failed,canceled}`
와 `push.{token.deactivated,no.active.token}`, ⑤가 갈라 놓은 취소 사유 8종.

없는 것은 **받은 뒤**다. 눌렀는지, 누르고 무엇을 했는지는 폰 안에서만 일어난다. 그 절반이 비면
알림을 늘릴지 줄일지 판단할 근거가 없다.

| ID | 재는 것 | 아는 쪽 |
|---|---|---|
| O11 | 포그라운드 체류 비율 | 앱 |
| O12 | 화면 진입 원인(직접·resume·push) | 앱 |
| O13 | 알림 선택률·선택까지 걸린 시간 | 발송=서버, 선택=앱 |
| O14 | 환승 의사결정 구간 재진입률 | 앱 |
| O15 | alert-to-action 지연 | 알림=서버, 행동=앱 |
| O8 | 잘못된·중복·지연 알림 | 양쪽 대조 |

모바일은 **FCM 수신과 foreground·background·cold start 선택 콜백까지 이미 구현**돼 있다.
전송만 붙이면 되므로 창구가 죽은 코드가 되지 않는다.

## 2. 확정 사항 (구현 중 재논의 금지)

| # | 결정 | 이유 |
|---|---|---|
| D1 | **원본을 저장한다**(숫자만 남기는 안은 기각) | alert-to-action 은 두 사건의 **시각 차이**라, 카운터만으로는 상관을 못 낸다 |
| D2 | **공통 컬럼 + `jsonb` 상세** | 이벤트 종류마다 실리는 값이 다르다. 전부 컬럼으로 박으면 앱이 이벤트를 하나 늘릴 때마다 서버 마이그레이션이 모바일 배포와 묶인다 |
| D3 | **`telemetry_id` unique 로 중복 제거.** 재전송은 `ON CONFLICT DO NOTHING` | 지하철에서 응답을 못 받은 앱이 같은 batch 를 다시 보낸다. 두 번 세면 선택률이 부풀려진다 |
| D4 | **best-effort 다.** 이 API 의 실패가 여정 상태·발송·폴링에 영향을 주지 않는다 | SC-3 인수 기준. 계측이 본체를 흔들면 계측을 꺼야 한다 |
| D5 | **사용자는 토큰에서만 읽는다.** 본문의 `userId` 는 무시한다 | 남의 여정에 기록을 심을 수 있으면 지표가 아니라 구멍이다 |
| D6 | **좌표·정밀 위치를 받지 않는다.** 메트릭 태그에 식별자를 넣지 않는다 | REQUIREMENTS §6 문단이 명시. 태그에 journeyId·userId·telemetryId 가 들어가면 시계열 수가 폭발한다 |
| D7 | **보관은 30일**로 정하되 **삭제 배치는 이 PR 밖**이다. 기준 시각은 `received_at` | 사용자 결정. 기기 시각으로 지우면 시계를 미래로 돌린 기기의 데이터가 영원히 남고, 과거로 돌린 기기의 데이터는 받자마자 지워진다 |
| D9 | 경로는 **여정 스코프**다 — `POST /api/journeys/{journeyId}/telemetry/batch` | 상위 PLAN §227 이 이미 정한 계약이다. 경로에 여정이 있으면 소유권 확인이 한 자리에서 끝난다. 여정 밖 이벤트는 이번 범위 밖 |
| D10 | 유일성은 **`unique(user_id, telemetry_id)`** | 기기를 물려받아 계정이 바뀌어도 남의 ID 가 내 이벤트를 삼키지 않는다. 앱이 전역 UUID 를 쓴다는 보장에 기대지 않는다 |
| D11 | `alertId` 도 **소유권을 확인**한다 — `(id, user_id, journey_id)` 가 맞아야 한다 | 안 하면 남의 알림 id 로 "내가 눌렀다"를 심을 수 있고, O13·O15 가 엉뚱한 발송에 붙는다 |
| D12 | 수집 시점에 **알림 메타를 스냅샷**한다(`alert_checkpoint`·`alert_sent_at`) | 알림 행이 지워지면 숫자 id 만으로는 발송-행동 상관을 못 낸다. FK 는 여전히 안 건다 |
| D13 | 같은 ID 에 다른 내용이 오면 **먼저 쓴 것이 이긴다**(first-write-wins) | 검출하려면 기존 행을 매번 읽어야 한다. 지금 규모에서 값이 안 맞는 비용이라 계약으로 못박고 후속으로 넘긴다 |
| D8 | 집계 질의는 이 PR 에서 만들지 않는다. **원본 + 최소 카운터**까지 | 대시보드 질의는 데이터가 실제로 쌓인 뒤 모양을 보고 짠다 |

## 3. 스키마 (V41)

```sql
create table journey_telemetry_event (
    id                bigserial primary key,
    telemetry_id      varchar(64)  not null,          -- 앱이 만든 멱등 키
    user_id           bigint       not null,
    journey_id        bigint       not null,          -- 경로에서 온다(D9)
    route_revision    int,
    event_type        varchar(40)  not null,
    occurred_at       timestamptz  not null,          -- 기기 시각. 순서·보관의 근거로 쓰지 않는다
    received_at       timestamptz  not null,          -- 서버 시각. 보관·지연 계산의 기준
    alert_id          bigint,                         -- journey_notification.id (FK 없음)
    alert_checkpoint  varchar(40),                    -- 수집 시점 스냅샷(D12)
    alert_sent_at     timestamptz,                    -- 수집 시점 스냅샷(D12)
    detail            jsonb,
    created_at        timestamp(6) not null,
    updated_at        timestamp(6) not null,
    constraint uk_journey_telemetry_event_user_key unique (user_id, telemetry_id)
);
create index ix_journey_telemetry_event_journey on journey_telemetry_event (journey_id, occurred_at);
create index ix_journey_telemetry_event_type_time on journey_telemetry_event (event_type, occurred_at);
create index ix_journey_telemetry_event_received on journey_telemetry_event (received_at);
```

- `occurred_at` 은 **기기 시각이라 믿지 않는다.** 시계가 틀어진 기기가 섞이므로 순서·지연 계산은
  `received_at` 을 함께 본다. **보관 삭제의 기준도 `received_at`** 이다(D7) — 그래서 그 컬럼에만
  인덱스가 따로 붙는다
- `alert_checkpoint`·`alert_sent_at` 은 앱이 보내는 값이 아니라 **서버가 알림 행에서 복사**한다.
  나중에 알림 행이 지워져도 "어떤 알림에 대한 반응이었나"가 남는다(D12)
- `alert_id` 에 FK 를 걸지 않는다. 알림 행은 지워질 수 있고(②의 보관 과제), 계측이 그 삭제를
  막으면 안 된다
- `detail` 에 좌표를 넣지 않는다(D6). 서버가 들어온 값을 그대로 저장하므로 **허용 키 목록**으로
  거른다 — 앱이 실수로 좌표를 실어도 표에 남지 않아야 한다
- `.gitignore` 가 migration 을 파일별 whitelist 로 관리한다. `!...V41__create_journey_telemetry_event.sql`
  줄을 반드시 추가한다

## 4. 이벤트 어휘

REQUIREMENTS §6 문단이 요구하는 것에 맞춘다.

| event_type | 언제 | detail 예 |
|---|---|---|
| `ALERT_OPENED` | push 를 눌러 앱에 들어옴 | `{"surface":"LOCK_SCREEN"}` |
| `ALERT_PRESENTED` | OS 알림 또는 앱 내부 배너로 보임 | `{"surface":"IN_APP_BANNER"}` |
| `ALERT_SUPPRESSED` | 중복이라 표시하지 않음 | `{"reason":"DEDUP"}` |
| `SCREEN_ENTERED` | 여정 화면 진입 | `{"origin":"PUSH \| RESUME \| DIRECT"}` |
| `FOREGROUND_SPAN` | 포그라운드 체류 한 구간 | `{"seconds":42}` |
| `LOCAL_CHECKPOINT` | 앱이 소유한 하차·접근 체크포인트 | `{"state":"TRIGGERED \| PRESENTED \| DEDUP_SUPPRESSED \| OPENED","checkpointOccurrenceId":"..."}` |

- **모르는 `event_type` 은 거부하지 않고 저장한다.** 앱이 먼저 배포되는 순서가 정상이고, 여기서
  400 을 내면 새 이벤트를 붙이는 동안 배치 전체가 버려진다. 대신 지표에 `unknown` 으로 센다
- 어휘는 서버 enum 이 아니라 **문자열**이다. enum 으로 두면 앱이 값을 하나 늘릴 때마다 서버
  배포가 앞서야 한다

## 5. API

```
POST /api/journeys/{journeyId}/telemetry/batch        (인증 필요)
{
  "events": [
    {"telemetryId":"...", "eventType":"ALERT_OPENED", "occurredAt":"2026-08-11T09:03:00Z",
     "routeRevision":1, "alertId":987, "detail":{"surface":"LOCK_SCREEN"}}
  ]
}
→ 202 {"accepted": 12, "duplicated": 3, "rejected": 1}
```

경로는 상위 PLAN §227 이 이미 정한 계약이다(D9). 여정이 경로에 있으므로 **소유권 확인이 한 자리**
에서 끝난다.

### 5.1 거르는 순서

1. **여정 소유권** — `journeyId` 가 토큰 사용자 것이 아니면 **요청 전체 404**. 경로 자원이라
   행 단위로 봐줄 일이 아니다
2. **배치 상한** — 이벤트 200개 초과면 400. 조용히 자르지 않는다(앱이 나눠 보내면 되는 문제다)
3. **행 단위 검증** — 아래가 어긋난 행만 버리고 나머지는 저장한다
   - `telemetryId` 64자·`eventType` 40자 초과 → `malformed`
   - `detail` 직렬화 크기 4KB 초과, 중첩 3단 초과 → `malformed`
   - `alertId` 가 있는데 `(id, user_id, journey_id)` 로 못 찾음 → `foreign-alert`
   - `ALERT_OPENED`·`ALERT_PRESENTED` 인데 `alertId` 가 없음 → `unattributable`
4. **허용 키 필터**(§5.2) — 목록 밖 키는 조용히 버린다. 여기서 400 을 내면 앱이 필드를 하나 늘릴
   때마다 배치가 통째로 죽는다
5. 저장 — `ON CONFLICT (user_id, telemetry_id) DO NOTHING`. 0행이면 `duplicated`

### 5.2 `detail` 허용 키 (이벤트별)

MOBILE_PLAN §189 가 보내기로 한 필드에 맞춘다. **목록에 없는 키는 저장하지 않는다** — 앱이 실수로
좌표를 실어도 표에 남지 않아야 한다(D6).

| event_type | 허용 키 |
|---|---|
| `ALERT_OPENED` | `surface` |
| `ALERT_PRESENTED` | `surface` |
| `ALERT_SUPPRESSED` | `reason` |
| `SCREEN_ENTERED` | `origin` |
| `FOREGROUND_SPAN` | `seconds` |
| `LOCAL_CHECKPOINT` | `state`, `checkpointOccurrenceId`, `legIndex`, `checkpoint`, `surface`, `triggeredAt` |
| (모르는 값) | 없음 — `detail` 을 비운 채 저장한다 |

값은 **스칼라만** 받는다. 중첩 객체·배열은 버린다 — 좌표가 숨어 들어올 자리를 없앤다.

- `userId` 는 토큰에서 읽는다(D5). 본문에 있어도 무시한다
- 응답은 `202`. 앱은 실패하면 같은 `telemetryId` 로 재전송하면 되고, 실패가 앱 동작을 멈추면 안 된다(D4)
- 컨트롤러는 `infrastructure/adapter/in/api/journey/JourneyTelemetryController`

## 6. 지표 (최소)

식별자를 태그로 쓰지 않는다(D6). 태그는 **값 종류가 적은 것**만.

| 지표 | 태그 |
|---|---|
| `journey.telemetry.received` | `eventType`(모르는 값은 `unknown`) |
| `journey.telemetry.duplicated` | — |
| `journey.telemetry.rejected` | `reason`(`foreign-alert`·`unattributable`·`malformed`) |

alert-to-action·선택률 같은 **비율은 여기서 계산하지 않는다**(D8). 원본이 쌓이면 그때 질의로 짠다.

## 7. 작업 순서

- [ ] **S1** V41 + `.gitignore` whitelist 줄 + `JourneySchemaIT` 의 `@Sql` 목록에 추가
- [ ] **S2** 엔티티·저장소. 저장은 `ON CONFLICT DO NOTHING` native insert(D3)
      → 중복이 예외가 아니라 0행이어야 배치가 안 죽는다
- [ ] **S3** 요청·응답 DTO + 검증(배치 상한·필수 필드)
- [ ] **S4** `JourneyTelemetryService` — 여정 소유권, `alertId` 소유권 + 알림 메타 스냅샷,
      허용 키 필터, 저장, 지표
- [ ] **S5** 컨트롤러 + 인증 배선
- [ ] **S6** 테스트(§8)
- [ ] **S7** 전체 회귀 `./gradlew test`
- [ ] **S8** `PHASE-5.md` ⑦ 절 갱신 + §5 후속 과제에 **보관 삭제 배치** 등록
- [ ] **S9** 커밋(플랜 파일 포함) → PR

## 8. 테스트

| # | 시나리오 | 기대 |
|---|---|---|
| 1 | 이벤트 3개 batch | 3행 저장, `accepted=3` |
| 2 | 같은 `telemetryId` 를 두 번 | 1행, `duplicated=1`. **예외 없음** |
| 3 | 한 batch 안에 같은 `telemetryId` 두 개 | 1행 |
| 4 | 남의 `journeyId` 경로 | **404**, 아무것도 저장 안 됨 |
| 5 | **다른 사용자가 같은 `telemetryId` 사용** | 둘 다 저장된다(유일성이 사용자별, D10) |
| 6 | 남의 `alertId` | 그 행만 버림, `rejected{reason=foreign-alert}` |
| 7 | `ALERT_OPENED` 인데 `alertId` 없음 | 그 행만 버림, `unattributable` |
| 8 | 정상 `alertId` | `alert_checkpoint`·`alert_sent_at` 이 알림 행 값으로 채워진다 |
| 9 | 본문 `userId` 를 남의 것으로 위조 | 토큰 사용자로 저장된다 |
| 10 | 모르는 `eventType` | 저장되고 `detail` 은 비며 지표는 `unknown` |
| 11 | 이벤트 201개 | 400, 아무것도 저장 안 됨 |
| 12 | `detail` 에 좌표(`lat`·`lng`) | 저장된 `detail` 에 그 키가 없다 |
| 13 | `detail` 에 중첩 객체·배열 | 그 키가 저장되지 않는다 |
| 14 | `eventType` 41자 / `telemetryId` 65자 | 그 행만 `malformed`, 나머지는 저장 |
| 15 | 스키마 | unique(user_id, telemetry_id)·인덱스 3개 존재(`JourneySchemaIT` 관례) |

회귀: 전체 `./gradlew test`. 알림 경로는 이 PR 이 건드리지 않는다.

## 9. 후속 과제

- **보관 삭제 배치** — 정책은 30일이고 이 PR 은 삭제하지 않는다(D7). `@Scheduled` 하나로
  `received_at < now - 30d` 를 hard delete 한다.
  <b>주의</b>: 지우는 순간 그 `telemetry_id` 의 멱등이 열린다. 아주 오래된 batch 를 뒤늦게
  재전송하는 기기가 있으면 같은 이벤트가 다시 셈해진다. 삭제를 붙일 때 **받아 주는 시각 창**
  (예: `occurredAt` 이 7일보다 오래되면 거부)을 함께 정해야 한다 — codex 지적 1번
- **계정 삭제 시 24시간 내 물리 삭제**(상위 PLAN §627). 탈퇴 경로가 코드베이스에 생기면 그때
  붙인다. ①의 `user_push_token` 과 같은 상황이다
- **같은 ID 다른 내용 검출** — 지금은 first-write-wins 로 못박았다(D13). 필요해지면 정규화 해시를
  한 컬럼 더 두고 어긋날 때 지표를 올린다
- 집계 질의·대시보드 — 원본이 쌓인 뒤(D8)
- O8(잘못된·중복·지연 알림)은 발송 기록과 대조해야 하므로 질의 단계에서 다룬다

## 10. 함정

- **`telemetryId` 중복을 예외로 만들지 마라.** 배치 하나가 통째로 죽는다(D3)
- **모르는 `eventType` 을 400 으로 막지 마라.** 앱이 먼저 배포되는 것이 정상 순서다
- **메트릭 태그에 식별자를 넣지 마라.** 시계열이 사용자 수만큼 늘어난다(D6)
- **기기 시각을 믿지 마라.** `occurred_at` 만으로 지연을 계산하면 시계 틀어진 기기가 음수를 만든다
- `.gitignore` whitelist 줄을 빼먹으면 마이그레이션이 커밋되지 않아 **운영에서만 표가 없다**
- 이 API 는 여정 트랜잭션과 무관하다. 여정 서비스에 끼워 넣지 않는다(D4)
- **인증된 사용자도 쓰기 부하를 만들 수 있다.** 배치 상한은 요청 하나만 막는다. 지금은 수용하고,
  발송을 켠 뒤 실제 유입을 보고 필요하면 사용자별 유량 제한을 붙인다


## 11. codex 리뷰 처분 (2026-08-11)

| 지적 | 처분 |
|---|---|
| API 경로가 상위 PLAN §227(`/api/journeys/{journeyId}/telemetry/batch`)과 다르다 | **수용.** 기존 계약으로 되돌렸다(D9). 여정 밖 이벤트는 범위 밖으로 명시 |
| `alertId` 를 아무도 인가하지 않는다 | **수용.** `(id, user_id, journey_id)` 확인 + `foreign-alert` 사유(D11) |
| 알림 행이 지워지면 상관을 못 낸다 | **수용.** 수집 시점에 `alert_checkpoint`·`alert_sent_at` 스냅샷(D12) |
| 보관을 기기 시각으로 지운다 | **수용.** `received_at` 기준 + 전용 인덱스(D7) |
| `telemetry_id` 전역 유일이 계정 교체에서 남의 이벤트를 삼킨다 | **수용.** `unique(user_id, telemetry_id)`(D10) |
| `detail` 허용 목록·크기·중첩이 정의돼 있지 않다 | **수용.** §5.2 표 + 스칼라만·4KB·3단 제한 |
| 길이 검증이 없어 배치 전체가 SQL 에서 죽을 수 있다 | **수용.** 행 단위 `malformed` 로 거른다(§5.1) |
| `ALERT_OPENED` 에 알림 참조가 없어도 통과한다 | **수용.** `unattributable` 로 그 행만 버린다 |
| 같은 ID 다른 내용이 조용히 삼켜진다 | **부분 수용.** first-write-wins 를 계약으로 명시(D13), 검출은 후속(§9) |
| 보관 삭제가 멱등 키를 다시 연다 | **수용(후속).** 삭제를 붙일 때 받아 주는 시각 창을 함께 정한다고 §9 에 적었다 |
| 유량 제한 계약이 없다 | **수용(문서화).** 지금은 수용하고 실제 유입을 보고 붙인다(§10) |
| 계정 삭제 시 24시간 내 삭제(상위 PLAN §627)가 빠졌다 | **수용(후속).** 탈퇴 경로가 생기면 붙인다(§9) |
