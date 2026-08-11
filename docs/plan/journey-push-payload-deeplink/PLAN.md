# push payload 계약과 앱 진입 (Phase 5 ⑥, M) — 실행 플랜

- 슬러그: `journey-push-payload-deeplink`
- 브랜치: `feat/journey-push-payload-deeplink` (**fresh main 기준 독립 브랜치**)
- 마이그레이션: **없음**
- 선행: ②③④⑤ (⑤ = PR #130)
- 상위: [PHASE-5.md](../journey-navigation/PHASE-5.md) §⑥ / REQUIREMENTS §4.5·G6·G8 / SCOPE-CHANGES SC-3 2항

## 0. 한 줄

**알림을 눌렀을 때 앱이 정확히 그 환승 화면으로 가게 하고, 문구를 임시가 아닌 것으로 굳힌다.
payload 에는 여전히 식별자만 담는다.**

## 1. 지금 실려 나가는 것

`FcmMessageFactory` 가 `notification`(제목·본문) + `data`(식별자 8개)를 함께 보낸다.

```
data = notificationId, journeyId, routeRevision, scopeType, scopeId,
       checkpoint, commandVersion, evaluationVersion
```

⑥ 이 더할 것은 둘이다 — **deep link 문자열**과 **문구 키**. 그리고 임시 문구를 확정한다.

## 2. 확정 사항 (구현 중 재논의 금지)

| # | 결정 | 이유 |
|---|---|---|
| D1 | **deep link 는 서버가 완성한 커스텀 스킴 문자열**로 준다 — `nextroute://journey/{journeyId}?alertId={notificationId}&scope={scopeType}&scopeId={scopeId}` | 앱 핸들러가 단순해지고, 경로가 늘어도 서버만 고치면 된다. https 유니버설 링크는 AASA·assetlinks 배포가 선행되어야 해 지금 못 쓴다 |
| D2 | **`notification` + `data` 를 계속 함께 보낸다.** 포그라운드 억제는 앱이 한다 | iOS 무음(data-only) push 는 OS 가 미루거나 버려서 알림이 아예 안 뜨는 사례가 생긴다. 확실히 뜨는 쪽을 고르고 표시 제어만 클라이언트에 둔다(G8) |
| D3 | **문구는 서버 상수, `data.messageKey` 를 동봉**한다 | 앱이 원하면 키로 자기 문구를 쓰고, 안 쓰면 서버 문구가 그대로 보인다. 다국어·A/B 를 나중에 열어 두되 지금 설정 키를 늘리지 않는다 |
| D4 | **payload 에 상태값을 넣지 않는다**(기존 유지) | 30분 뒤에 연 알림의 숫자는 이미 틀렸다. 앱은 식별자로 최신 snapshot 을 다시 조회한다(SC-3 2항) |
| D5 | **알림 조회 API 를 만들지 않는다** | 기존 snapshot 이 `legs`·`eta.activeTransfer` 를 이미 준다. `scopeType`·`scopeId` 로 대상을 가리면 화면을 그릴 수 있다. 알림 이력 화면이 생기면 그때 만든다 |
| D6 | 이 PR 도 운영 발송을 켜지 않는다. **켜는 것은 별도 작업** | 자격증명 배치·worker 전환·부팅 확인이 코드 변경과 섞이면 되돌리기가 어려워진다 |

## 3. payload 계약 (확정형)

```
data:
  notificationId     알림 행 id. = deep link 의 alertId
  journeyId
  routeRevision
  scopeType          TRANSFER
  scopeId            transferIndex
  checkpoint         TRANSFER_RISK_AT_RISK | TRANSFER_RISK_MISSED | TRANSFER_TARGET_APPROACHING
  commandVersion
  evaluationVersion
  messageKey         journey.transfer.at-risk | .missed | .approaching
  deepLink           nextroute://journey/{journeyId}?alertId={id}&scope=TRANSFER&scopeId={n}
```

- 값은 전부 문자열이다(FCM `data` 계약)
- FCM 예약 키(`from`, `google*`, `gcm*`, `notification`)와 겹치지 않는다
- **상태값 금지**가 계약이다. 새 키를 더할 때 "이 값이 30분 뒤에도 참인가"를 먼저 묻는다

## 4. 문구 확정

| checkpoint | 제목 | 본문 |
|---|---|---|
| `TRANSFER_TARGET_APPROACHING` | 갈아탈 차가 곧 도착해요 | 탑승 준비를 하세요. |
| `TRANSFER_RISK_AT_RISK` | 환승 시간이 빠듯해요 | 서두르면 다음 차를 탈 수 있어요. |
| `TRANSFER_RISK_MISSED` | 환승을 놓쳤어요 | 앱에서 새 안내를 확인하세요. |

- `AT_RISK` 본문의 "지금 이동하면"을 **"서두르면"으로 바꾼다.** ④가 대기 단계의 `AT_RISK` 를
  접근 알림에 넘겼으므로 이 문구가 나가는 상황은 **아직 이동 중**인데, "이동하면"은 이미 멈춰
  있다는 전제를 깔아 어색하다
- 문구는 `FcmMessageFactory` 안의 `switch` 하나가 소유한다(지금 자리 유지). 값 목록이 늘면
  컴파일러가 빠뜨린 곳을 잡는다

## 5. 구현

`src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/notification/FcmMessageFactory.java`

- `data(...)` 에 `messageKey`·`deepLink` 두 줄 추가
- `messageKey(checkpoint)`·`deepLink(command)` private 메서드 신설. 둘 다 `switch`/문자열 조립뿐이라
  의존성이 늘지 않는다
- `deepLink` 는 `scopeType.name()` 을 그대로 쓴다. 값이 하나(`TRANSFER`)라도 문자열을 박아 넣지
  않는다 — leg scope 가 생기면 조용히 틀린 링크가 된다
- 스킴(`nextroute`)은 상수로 둔다. 바뀌면 한 곳만 고친다
- 문구 `body` 의 `AT_RISK` 문장 교체(§4)

## 6. 작업 순서

- [ ] **S1** `messageKey`·`deepLink` 추가 + `AT_RISK` 본문 교체
      → `./gradlew compileJava`
- [ ] **S2** `FcmMessageFactoryTest` 확장(§7)
- [ ] **S3** 전체 회귀 `./gradlew test`
- [ ] **S4** `PHASE-5.md` ⑥ 절 구현 완료 표시 + **운영 발송 켜기 절차**를 한 절로 정리
- [ ] **S5** 커밋(플랜 파일 포함) → PR

## 7. 테스트

`src/test/java/watoo/grd/nextroute/infrastructure/adapter/out/notification/FcmMessageFactoryTest.java`

| # | 시나리오 | 기대 |
|---|---|---|
| 1 | 접근 알림 payload | `deepLink` 가 `nextroute://journey/{id}?alertId={id}&scope=TRANSFER&scopeId=0` |
| 2 | `messageKey` | 체크포인트 셋이 각각 다른 키 |
| 3 | `data` 키 목록 | 정확히 10개. **상태값(`status`·`slack`·`nextBusArrivalAt`)이 없다** |
| 4 | `alertId` | `notificationId` 와 같은 값 |
| 5 | 문구 | 체크포인트 셋이 각각 제목·본문을 갖는다(§4 표와 일치) |
| 6 | scope 가 늘어도 | `scopeType` 이 링크에 그대로 반영된다(문자열을 박지 않았다) |

회귀: ②③④⑤ IT 전부 + 버스 도착 알림·토스 경로 무손상. `./gradlew test` 전체.

## 8. 함정

- **`data` 값은 문자열만.** 숫자를 그대로 넣으면 SDK 가 거부한다
- **payload 에 상태를 넣지 마라**(D4). 이 PR 이 그 계약을 문서로 굳히는 자리다
- deep link 에 사용자 식별자·token 을 넣지 않는다. 알림은 잠금화면에 보인다
- 문구를 바꾸면 **운영 발송을 켠 뒤에는 되돌리기가 사용자 눈에 보인다.** 켜기 전에 확정한다
- `collapseKey` 는 건드리지 않는다. 체크포인트를 빼는 것이 의도다(④의 결정)

## 9. 운영 발송 켜기 (이 PR 뒤에 별도로)

1. worker 컨테이너에 서비스 계정 JSON 을 읽기 전용으로 마운트
2. worker 프로필만 `journey.notification.sender=fcm` → **부팅 확인**(자격증명이 없으면 부팅이 막힌다)
3. `journey.notification.relay.enabled=true`
4. 되돌리기는 `relay.enabled=false` 하나

## 10. 범위 밖

- 알림 이력 화면·조회 API — 필요해지면 그때(D5)
- push 선택률·alert-to-action 계측 — ⑦
- 앱 쪽 핸들러 구현 — MOBILE_PLAN 소유
