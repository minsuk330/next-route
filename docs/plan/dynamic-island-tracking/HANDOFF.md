# HANDOFF — 지하철 열차 선택 창 확장 (S1, 서버 → 앱)

앱 A1·A3 가 쓰는 서버 계약. **이 서버가 VPS 에 배포된 뒤** 앱을 출시한다(옛 서버는 탑승 중·환승 선택에 409).

## 1. 고를 수 있는 단계가 넓어졌다
`GET /api/journeys/{id}/subway/train-candidates?legIndex=` · `POST /api/journeys/{id}/subway/train-selection`

| 지금 단계 | 대상(`legIndex`) | 후보 | 비고 |
|---|---|---|---|
| WAIT_SUBWAY | 바로 다음 RIDE_SUBWAY | 곧 올 열차(최대 2) + 막 떠난 열차 | 기존 |
| RIDE_SUBWAY | 지금 탑승 구간 | **떠난 열차만** | "다른 열차예요" — 다시 고르면 덮어씀 |
| RIDE_SUBWAY (지하 환승) | 다음 RIDE_SUBWAY (사이 도보 0~1개) | 곧 올 열차 + 막 떠난 열차 | 고른 뒤 앱이 단계 전환을 연속 전송 |

- `legIndex` 는 **탑승(RIDE_SUBWAY) 구간 index**. 없으면 지금 단계 기준(대기면 다음 탑승, 탑승 중이면 그 탑승).
- 그 밖(도보 등)·대상이 지하철 대기+탑승 짝이 아니면 409 `NOT_WAITING_FOR_SUBWAY`.
- 탑승 중 선택은 승차역 도착 목록(뒤차)을 받지 않는다 → 곧 올 열차 번호로 고르면 422 `TRAIN_NOT_IN_CANDIDATES`.
- 떠난 열차 창 = 10분, 탑승 중이면 탄 뒤 지난 시간 + 2분까지.
- **선택은 탑승 전환을 하지 않는다.** 대기에서 고르면 앱이 이어서 `CLIENT_LEG_TRANSITION_DETECTED`(WAIT→RIDE) 를 보낸다(결정 4). 환승이면 지금→다음 RIDE 까지 한 칸씩.

## 2. 후보에 `kind`
```json
[{"trainNo":"2390","destination":"성수","express":false,"trainType":"일반","arrivalSeconds":90,"observedAt":"…","kind":"UPCOMING"},
 {"trainNo":"2386","destination":null,"express":false,"trainType":null,"arrivalSeconds":null,"observedAt":"…","kind":"DEPARTED"}]
```
- `DEPARTED` 는 `arrivalSeconds`·`destination`·`trainType` 이 null 일 수 있다(위치 원천은 행선지가 없다). 화면은 "이미 탔어요".
- 옛 앱은 `kind` 를 몰라도 된다(필드 추가). 단 옛 앱은 떠난 열차도 목록에 보게 된다 — 그 열차를 골라도 서버는 받는다.

## 3. 추적 가능 여부 (신규)
`GET /api/journeys/{id}/subway/trackability?legIndex=` → `{"legIndex":3,"trackable":false,"reason":"NO_REALTIME_LINE"}`
- `reason`: `NO_REALTIME_LINE`(서울 실시간 원천이 없는 노선) · `UNKNOWN_DIRECTION` · `NO_STATION`. true 면 null.
- 단계 제약 없음 — 여정 시작 시 지하철 탑승 구간마다 미리 물어도 된다. 지하철 탑승 구간이 아니면 409.
- false 면 앱은 열차 선택·링을 띄우지 않는다(결정 9). 진행은 앱 GPS.

## 4. 링 push 는 그 탑승의 앞 대기·탑승 중에만
- 여정이 그 탑승 다음 단계로 넘어가면 진행 링 갱신·push 를 멈춘다(쌓인 행은 `leg_left` 로 SKIP). 지하철 화면이 도보 화면을 덮지 않는다.
- 종료(END) 프레임은 그대로 나간다.

## 5. 지하철 탑승 중 오프라인 보호
- 링이 하차 도착 예정을 알면 보호 시각을 `예정 + 5분`(상한 탑승 시작 + 60분)까지 늘린다. 승강장에서 고르고 바로 탑승 전환해도 지하에서 만료되지 않게.
