# 모바일 인계 — 주행 중 재탐색(다음 정류장)

## 요약

**서버는 끝났다**(8 PR 전부 main, 2026-09-20). 기능은 **플래그로 꺼져 있고**, 아래 클라 작업이
끝나야 켤 수 있다.

바뀐 것 한 줄: 재탐색이 **"환승 정류장에서 다른 경로"** 에서 **"지금 타고 있는 버스에서 어느
정류장에 내리든 다른 경로"** 로 넓어졌다. 그래서

- 제안의 주소가 `transferIndex`(환승 번호) → `origin`(정류장) 으로 **바뀌었다(깨지는 변경)**
- 후보마다 **"어디서 내리는지"** 가 붙는다 → 화면의 축이 하차 정류장이다
- **서버가 먼저 알린다**(push) → 거절·무시 엔드포인트가 새로 생겼다
- 앱이 `remainingStops` 를 안 보내면 **기능 절반이 조용히 안 돈다**

---

## 1. 깨지는 변경 — `transferIndex` → `origin`

`GET /api/journeys/{journeyId}/reroute-proposals/active` 응답에서 `proposal.transferIndex` 가
**사라졌다**. 그 자리에 `proposal.origin` 이 들어간다.

```jsonc
{
  "proposal": {
    "proposalId": 1234,
    "routeRevision": 3,
    "origin": {                      // ← 신규. 옛 transferIndex 를 대신한다
      "legIndex": 3,                 // 이 원점이 속한 단계 번호(현재 revision 기준)
      "stopId": "ST-1234",           // 서울 정류장 id
      "routeId": "BL-370",           // 서울 노선 id (없으면 null)
      "seq": 12,                     // 그 노선 안에서의 정류장 순번 (없으면 null)
      "kind": "RIDE_INTERIOR"        // TRANSFER_WAIT | RIDE_INTERIOR
    },
    "kind": "REMAINING_PATH",
    "expiresAt": "2026-09-20T09:02:00Z",
    "snapshotObservedAt": "2026-09-20T09:00:00Z",
    "candidates": [ /* 아래 2절 */ ]
  }
}
```

- **`kind`(origin 안)** — `TRANSFER_WAIT` = 원래 계획된 환승(=옛 동작) / `RIDE_INTERIOR` = 주행
  중인 버스의 **중간 정류장**. 옛 모델엔 주소가 아예 없던 자리다.
- **`origin` 이 null 인 경우** — 이미 접힌 옛 행을 읽을 때뿐이다. 실사용에서는 언제나 있다.
- 제안이 없으면 `204` 가 아니라 **`200` + `proposal: null`** 이다(기존과 동일).

### 요청 쪽은 안 바뀌었다

`POST /api/journeys/{journeyId}/reroute-proposals` 의 본문은 그대로 `{"transferIndex": 0}` 이다.
그 값은 **저장 키가 아니라 "어느 환승을 구하려는 요청인가"라는 입력**이라 남겨 뒀다. 값이 없으면
첫 환승(0)으로 읽는다.

---

## 2. 후보 — 하차 정류장이 화면의 축이다

```jsonc
"candidates": [
  {
    "candidateId": "6f1c…",
    "kind": "REMAINING_PATH",
    "display": {
      "destinationEtaAt": "2026-09-20T09:41:00Z",
      "totalDurationSec": 1980,
      "etaGainSec": 420,             // 지금 계획 대비 절감(300초 미만은 서버가 이미 거른다)
      "transferCount": 1,
      "walkingSec": 420,             // ⚠ 뜻이 넓어졌다 — 아래 주의
      "source": "REALTIME",          // REALTIME | ML | STATIC
      "confidence": "GOOD",
      "dataObservedAt": "2026-09-20T09:00:00Z",
      "legSummaries": ["360 승차", "역삼역 환승"],
      "alight": {                    // ← 신규(PR-2). 없으면 null = "계획대로 내린다"
        "stopId": "ST-1234",
        "name": "역삼역.국기원입구",
        "alightAt": "2026-09-20T09:08:00Z",
        "planned": false             // true = 원래 계획된 하차점
      }
    }
  }
]
```

**화면 구성**

- 후보는 **정류장당 정확히 하나**다. 그래서 응답을 정류장으로 중첩하지 않았다 — 앱이
  `display.alight.stopId` 로 묶어 구역을 나누면 된다.
- `alight.planned == true` 인 후보가 **기준선 자리**다("계속 타고 가서 계획대로 내린다").
  보통 목록 맨 아래나 별도 구역에 둔다.
- `alight == null` 은 옛 저장 행이다. `planned: true` 와 같게 그리면 된다.

**⚠ `walkingSec` 의 뜻이 넓어졌다**(PR-1). 예전에는 *승차 이후* 도보만 셌는데 이제 **접근
도보(정류장까지 걸어가는 시간) 포함 경로 전체**다. 같은 후보의 값이 예전보다 커 보일 수 있다.

**⚠ 후보 수가 줄 수 있다**. 예전에는 한 정류장에서 최대 4개가 나왔지만 이제 정류장당 1개다.
창이 안 열리는 상황(위치 모름·실시간 없음·지하철 주행)에서는 **후보가 하나만** 나온다.

---

## 3. 신규 엔드포인트 2개 — 거절 / 무시

서버가 먼저 알리게 되면서(4절) 사용자의 응답을 받을 자리가 필요해졌다.

### 거절 — "이 방식 마음에 안 든다"

```
POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/reject
Content-Type: application/json

{"keepAlerts": false}      // 본문 자체가 선택(생략 가능)
```

- `keepAlerts` = 거절창의 **"계속 알림 받기 ☐"** 체크박스. **기본은 꺼짐** — 값이 없으면
  "이제 그만"으로 읽는다.
- 체크 안 함 → 이 여정에서 재탐색 알림이 **끝난다**.
- 체크함 → 다음 제안도 계속 온다(무응답 상한도 무시한다).

### 무시 — "그냥 닫았다"

```
POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/dismiss
```

알림 화면을 **스와이프로 닫을 때** 부른다. 거절과 다르다 — 알림을 끝내지 않고 무응답으로 세며,
**여정당 2회**까지만 참는다(넘으면 더 안 알린다).

### 두 응답은 같은 모양

```json
{"closed": true, "rerouteTerminated": false, "rerouteAlertOptIn": false}
```

- **`closed`** — 이번 호출이 실제로 제안을 닫았는가. 재전송이면 `false` 지만 **응답은 여전히
  `200`**(멱등). 네트워크가 흔들려 재시도해도 실패 화면을 보여 주지 말 것.
- **`rerouteTerminated`** — 이 여정의 재탐색 알림이 끝났다. `true` 면 "이 여정에서는 더 이상
  제안이 오지 않습니다" 안내를 띄울 수 있다.
- **`rerouteAlertOptIn`** — 거절했지만 계속 받기로 했다.

> 이 값들을 앱이 스스로 계산하지 말 것. 규칙(수락 1회 / 거절+미체크 종료 / 무응답 2회)이 서버와
> 앱 두 벌이 되면 어긋나는 날 사용자에게 거짓말을 한다.

---

## 4. 수락 — 신규 오류 코드 `REROUTE_ALIGHT_UNAVAILABLE`

```
POST /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept
{"candidateId": "6f1c…"}
```

응답은 기존대로 새 개정본이 반영된 **여정 스냅샷**이다. 앱은 그 `routeRevision` 으로 geometry 를
다시 받아 지도와 진행 엔진을 함께 갈아끼운다(기존 흐름과 동일).

| 코드 | 상태 | 뜻 | 앱이 할 일 |
|---|---|---|---|
| `REROUTE_ALIGHT_UNAVAILABLE` | 409 | **신규.** 누르는 사이 버스가 그 정류장을 지나갔다 / 남은 시간이 30초 미만 / 실시간이 끊겼다 | **목록을 새로 받아** 남은 자리를 다시 보여 준다. 제안은 아직 살아 있다 |
| `REROUTE_PROPOSAL_STALE` | 409 | 제안이 낡았다(만료·개정본 변화·지나간 단계) | 제안을 닫고 새로 요청 |
| `REROUTE_CANDIDATE_NOT_FOUND` | 404 | 그 후보가 제안에 없다 | 목록 새로고침 |

**둘을 가른 이유**: `ALIGHT_UNAVAILABLE` 은 제안이 낡은 게 아니라 **버스가 더 간 것**이다.
여정은 아무것도 안 바뀌었고(트랜잭션째 롤백) 제안도 `ACTIVE` 로 남아 있어, 목록만 다시 받으면
다른 정류장 후보를 그대로 고를 수 있다.

**같은 후보로 다시 누른 재전송은 `200`** 이다(기존과 동일, 멱등).

---

## 5. Push — 서버가 먼저 알린다

계약이 뒤집혔다: 예전에는 **사용자가 요청할 때만** 제안이 생겼지만, 이제 서버가 환승 위험을
감지하면 스스로 제안을 만들고 알린다. **경로 변경은 여전히 사용자 선택만** — 자동 적용은 없다.

FCM `data` (전부 문자열):

```jsonc
{
  "notificationId": "8891",
  "journeyId": "1234",
  "routeRevision": "3",
  "scopeType": "JOURNEY",
  "scopeId": "5678",                        // ← 제안 번호(proposalId)다. 환승 번호가 아니다
  "checkpoint": "REROUTE_SUGGESTED",        // ← 신규 체크포인트
  "commandVersion": "12",
  "evaluationVersion": "40",
  "messageKey": "journey.reroute.suggested",
  "deepLink": "nextroute://journey/1234?alertId=8891&scope=JOURNEY&scopeId=5678"
}
```

- 서버 문구: **"더 빠른 길이 있어요 / 앱에서 새 경로를 확인하세요."**
  `messageKey` 로 앱 문구를 써도 된다. **바꾸려면 발송을 켜기 전에** — 켠 뒤 변경은 사용자
  눈에 보인다.
- push 는 **한 줄 신호**다. 숫자를 담지 않으므로 앱은 알림을 받으면 `.../reroute-proposals/active`
  를 **다시 조회**해서 최신 상태를 그린다(늦게 도착한 알림이 낡은 숫자를 보여 주지 않게).
- 알림 TTL 5분 / 사용자 요청 제안 TTL 2분. 만료된 제안의 알림을 누르면 빈 목록이다.

---

## 6. 앱이 반드시 해야 하는 것 — `remainingStops`

**주행 중 중간 하차는 앱이 보낸 진행 보고에 전적으로 의존한다.**

`PUT /api/journeys/{journeyId}/progress` 의 `remainingStops`(버스 구간에서 남은 정류장 수)가
**없거나 180초보다 오래되면** 서버는 "지금 몇 번째 정류장인지 모른다"고 판단해 **창을 아예 열지
않는다**. 그러면
후보는 계획 하차점 하나만 나오고, 이 기능의 절반이 조용히 사라진다.

- 버스 주행 중에는 `remainingStops` 를 **계속** 보낼 것(지하철·도보 구간은 비워도 된다).
- 서버는 그 값을 **재판정하지 않는다**. 앱이 보낸 값이 곧 위치다.

---

## 7. 화면 — 중간 하차 확인

PLAN 의 "확인 화면"은 클라 UX 다. 서버는 재료를 준다:

- 어디서 내리는지 = `display.alight.name` / `alightAt`
- 얼마나 빨라지는지 = `display.etaGainSec`
- 언제 도착하는지 = `display.destinationEtaAt`

**하차는 되돌릴 수 없는 행동**이라 서버가 보수적으로 만든다 — 실시간 도착을 못 구한 정류장은
후보에서 아예 빼고(시간표로 때우지 않는다), 리드타임 90초 미만도 뺀다. 그래도 사용자가 누른 뒤
버스가 더 갈 수 있어 4절의 409 가 존재한다.

---

## 8. 켜는 순서 (서버 쪽, 참고)

1. `journey.reroute.enabled` — 재탐색 기능 자체
2. `journey.reroute.alert.enabled` — 서버 능동 알림

둘 다 지금 꺼져 있다. 1번만 켜면 "사용자가 요청할 때만" 동작하고, 2번을 켜야 push 가 나간다.
**앱 배포가 먼저**다 — `origin` 을 못 읽는 앱 버전이 남아 있는 동안 켜면 제안 화면이 깨진다.

---

## 참고

- 설계·결정 이유: `docs/plan/journey-reroute-nextstop/{PLAN,SUMMARY,DESIGN-LOG}.md`
- 구현 중 실측·함정: 저장소 루트 `implementation_note.md` (2026-09-18 ~ 09-20 절)
- 관련 PR: #211 #213 #214 #215 #216 #219 #222 #223 #225 #226
