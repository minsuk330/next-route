# 앱 인계 — 악화 알림 피로 정책 (#428)

서버는 경로 버전마다 **울림 한 번**만 보내고, 그 뒤 악화는 **조용한 갱신**으로 보낸다. 앱이 할 일은 셋이다.

## 1. Android 조용한 채널 만들기 (필수)
- 채널 id `journey_quiet` (글자까지 같아야 함)
- 중요도 `IMPORTANCE_LOW` — 소리·진동 없음, 알림 목록에는 보임
- 앱 시작 시 한 번 생성 (`expo-notifications` 의 `setNotificationChannelAsync` 등)
- **이 채널이 없는 기기에 서버가 조용한 갱신을 보내면 OS 가 소리 나는 기본 채널로 떨어뜨린다.** 그래서 서버는 2번의 능력을 알린 기기에만 보낸다

## 2. 토큰 등록에 능력 알리기 (필수)
`PUT /api/me/push-token` 본문에 추가:
```json
{ "token": "...", "platform": "ANDROID", "capabilities": ["quietChannel"] }
```
- 1번 채널을 만든 뒤에만 `quietChannel` 을 넣는다
- 필드를 아예 빼면 서버는 기존 값을 그대로 둔다. 빈 목록 `[]` 은 "능력 없음"
- iOS 는 넣어도 되고 안 넣어도 된다(iOS 는 OS 가 `passive` 를 처리)

## 3. 포그라운드에서 조용한 갱신 배너 숨기기 (필수)
- FCM `data.delivery` 가 `"QUIET"` 이면 인앱 배너를 띄우지 않고 여정 화면만 새로 고친다(`refreshJourney`)
- `"ALERTING"` 또는 값 없음 → 지금처럼 배너
- 안 하면 앱을 보고 있는 사용자에게는 조용한 갱신이 배너로 보인다

## 서버가 보내는 것 (참고)
| 필드 | 값 |
|---|---|
| `data.delivery` | `ALERTING` / `QUIET` (정책 켜졌을 때만) |
| Android `tag` | `j{journeyId}r{revision}A` — 같은 경로 버전 알림은 화면에서 서로 덮인다 |
| Android `channel_id` | QUIET 일 때만 `journey_quiet` |
| iOS `apns-collapse-id` | 위 tag 와 같음 |
| iOS `aps.interruption-level` | QUIET 일 때만 `passive` |

- 접근 알림("갈아탈 차가 곧 도착해요")은 이 정책 밖 — 지금과 같음
- 앱 업데이트 전 Android: 첫 울림은 받고, 이후 조용한 갱신은 안 받는다(서버가 안 보냄). 앱 화면에서는 최신 상태가 보인다
