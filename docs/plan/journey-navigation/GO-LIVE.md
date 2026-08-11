# 여정 알림 운영 발송 켜기 — 실행 절차

- 상위: [PHASE-5.md](./PHASE-5.md) §1.1
- 대상: Phase 5 ①~⑦ 머지 후의 운영 전환. **코드 변경 없음** — 설정과 배포만 한다
- 작성일: 2026-08-11

## 0. 지금 실행하지 못하는 이유

**VPS 의 이번 달 네트워크 전송량을 다 썼다. 2026-08-15 이후에 연다.**

앞당길 이유도 없다. 발송을 켜면 FCM·실시간 버스 API 호출이 함께 늘어 남은 전송량을 더 빠르게
소진한다. 그때까지 이 문서를 최신으로 유지하고, 15일 이후 이 순서대로 실행한다.

## 1. 지금 상태

| 스위치 | 현재 값 | 뜻 |
|---|---|---|
| `journey.navigation.evaluator.enabled` | `false` | 평가기 빈이 안 뜬다. **알림 행 자체가 안 쌓인다** |
| `journey.notification.enqueue-enabled` | `true` | 평가기가 돌면 쌓기는 한다 |
| `journey.notification.sender` | `noop` | 아무 데도 안 보낸다 |
| `journey.notification.relay.enabled` | `false` | relay 루프 빈이 안 뜬다 |

넷 다 꺼진 조합이라 **머지만으로는 아무 일도 일어나지 않는다.** 켜는 순서가 곧 위험을 여는
순서다 — 아래 순서를 지킨다.

relay 와 평가기는 둘 다 `@Profile("worker")` 다. API 컨테이너에서는 스위치를 켜도 안 돈다.

## 2. 선행 조건 (하나라도 없으면 시작하지 않는다)

- [ ] **Firebase 프로젝트와 서비스 계정 키(JSON) 실물.** iOS 를 함께 쓰려면 그 프로젝트에 APNs
      인증키가 등록돼 있어야 한다 — 없으면 Android 만 도착하고 iOS 는 조용히 실패한다
- [ ] VPS 네트워크 전송량 여유 (**2026-08-15 이후**)
- [ ] `main` 의 ①~⑦ 이 VPS 에 실제로 배포돼 있을 것. 마이그레이션 **V39·V40·V41** 이 적용됐는지
      확인한다 — 로컬 빌드로 올리는 구조라 코드만 새것이고 스키마가 옛것인 상태가 실제로 가능하다
- [ ] 모바일이 FCM token 을 `PUT /api/me/push-token` 으로 실제 등록하고 있을 것.
      `select count(*) from user_push_token where enabled = true` 가 0 이면 켜 봐야 보낼 곳이 없다

```bash
# VPS 에서 스키마 확인
ssh vps
docker compose -f /root/apps/nextroute/compose.yaml exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 5;"
docker compose -f /root/apps/nextroute/compose.yaml exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) from user_push_token where enabled = true;"
```

## 3. 절차

### 3.1 자격증명 배치

```bash
# 호스트에 두고 worker 에만 읽기 전용으로 마운트한다(compose.yaml 이 이미 그렇게 돼 있다).
scp firebase-service-account.json vps:/srv/nextroute/firebase-service-account.json
ssh vps 'chmod 600 /srv/nextroute/firebase-service-account.json'
```

- 경로는 `compose.yaml` 의 worker 서비스에 이미 박혀 있다:
  `/srv/nextroute/firebase-service-account.json:/secrets/firebase-service-account.json:ro`
- **base64 로 환경변수에 넣지 않는다.** 프로세스 목록과 크래시 덤프에 남는다
- **API 컨테이너에는 주지 않는다.** relay 는 worker 전용이고, API 에 주면 권한만 넓어지는 데다
  Firebase 초기화 실패가 API 부팅까지 막는다

### 3.2 env 파일

```bash
ssh vps
cd /root/apps/nextroute
cp env/fcm.env.example env/fcm.env
```

`env/fcm.env` 를 이 값으로 둔다 — **아직 relay 는 끈 채다.**

```properties
JOURNEY_NOTIFICATION_SENDER=fcm
FIREBASE_CREDENTIALS_PATH=/secrets/firebase-service-account.json
JOURNEY_NOTIFICATION_RELAY_ENABLED=false
```

`compose.yaml` 의 worker 는 이미 `env/fcm.env` 를 읽는다. `env/fcm.env` 는 gitignore 대상이라
커밋되지 않는다.

### 3.3 worker 재기동 → **부팅 확인**

```bash
docker compose up -d worker
docker compose logs -f worker | head -50
```

여기가 이 절차의 **관문**이다. 자격증명이 없거나 틀리면 `JourneyNotificationProperties.validate()`
와 Firebase 초기화가 **부팅을 막는다** — 조용히 아무것도 안 보내는 상태가 가장 나쁘기 때문에
일부러 그렇게 만들어 뒀다.

- 부팅 실패하면 로그의 예외 문구를 그대로 읽는다. `sender=fcm 인데 fcm.credentials-path 가 비었다`
  면 env 가 안 실린 것이고, Firebase SDK 예외면 키 내용이 틀린 것이다
- **여기서 멈춰도 사용자에게 가는 알림은 0 이다.** relay 가 아직 꺼져 있다

### 3.4 평가기 켜기 (알림 행을 쌓기 시작)

`env/api.env` 또는 worker 가 읽는 env 에 둔다.

```properties
JOURNEY_EVALUATOR_ENABLED=true
```

```bash
docker compose up -d worker
# 행이 쌓이는지 확인 — 아직 아무것도 안 나간다(sender 는 붙었지만 relay 가 꺼져 있다)
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "select checkpoint, status, count(*) from journey_notification group by 1,2 order by 1;"
```

- 활성 여정이 있어야 행이 생긴다. 아무도 여정을 시작하지 않았으면 0 이 정상이다
- `PENDING` 이 쌓이기만 하고 줄지 않는 것이 **이 단계의 정상 상태**다

### 3.5 relay 켜기 (실제 발송 시작)

```properties
JOURNEY_NOTIFICATION_RELAY_ENABLED=true
```

```bash
docker compose up -d worker
```

이 순간부터 실제 사용자 기기로 알림이 간다.

## 4. 되돌리기

```properties
JOURNEY_NOTIFICATION_RELAY_ENABLED=false
```
```bash
docker compose up -d worker
```

**스위치 하나다.** 행은 다시 쌓이기만 하고 나가지 않는다. 되돌린 뒤에도 `PENDING` 은 자기
만료(`expires_at`)가 지나면 relay 가 다음에 켜질 때 `CANCELED` 로 닫는다 — 오래된 알림이 뒤늦게
나가지 않는다.

평가기까지 끄려면 `JOURNEY_EVALUATOR_ENABLED=false`. 그러면 외부 API 호출도 함께 멈춘다.

## 5. 켠 뒤 확인 (Phase 5 완료 조건)

발송을 켜야 비로소 확인 가능한 것들이다. `PHASE-5.md §4` 의 체크박스를 이 관측으로 채운다.

### 5.1 지표 (Grafana)

| 지표 | 봐야 하는 것 |
|---|---|
| `journey.notification.enqueued{checkpoint}` | 세 체크포인트가 각각 잡히는가 |
| `journey.notification.sent{channel}` | 실제로 나가는가 |
| `journey.notification.failed{channel,kind}` | 일시·영구 실패 비율 |
| `journey.notification.canceled{reason}` | **여기가 핵심.** `lease-expired`·`command-changed`·`transfer-missed`·`already-boarded` 가 각각 얼마나 나오는지로 재검증이 과한지 모자란지 안다 |
| `journey.notification.push.token.deactivated` | 갑자기 튀면 설정 실수를 의심한다 |
| `journey.notification.push.no.active.token` | 보낼 기기가 없는 사용자 수 |

### 5.2 SQL 로 확인하는 완료 조건

```sql
-- 하차 유형 FCM enqueue 0 (어휘에 없으므로 구조적으로 0이어야 한다)
select distinct checkpoint from journey_notification;
--   기대: TRANSFER_RISK_AT_RISK / TRANSFER_RISK_MISSED / TRANSFER_TARGET_APPROACHING 뿐

-- 같은 서버 체크포인트 중복 enqueue 0
select logical_checkpoint_key, count(*) from journey_notification
group by 1 having count(*) > 1;
--   기대: 채널이 하나(PUSH)이므로 0행

-- 재탐색 후 이전 revision 알림 발송 0
select count(*) from journey_notification n
join journey_session s on s.id = n.journey_id
where n.status = 'SENT' and n.route_revision <> s.route_revision;
--   기대: 0

-- lease 만료 이후 발송 0
select count(*) from journey_notification n
join journey_session s on s.id = n.journey_id
where n.status = 'SENT' and n.sent_at > s.guidance_lease_until;
--   기대: 0
```

### 5.3 실기기 (모바일 몫)

- 포그라운드에서 OS 알림 0회 + 앱 내부 배너 1회
- 백그라운드·잠금화면에서 OS 알림이 뜨고 deep link 로 해당 여정·환승이 열림
- 오래된 push 를 열어도 payload 의 값이 아니라 **최신 snapshot** 으로 화면이 수렴

deep link 형식은 `nextroute://journey/{journeyId}?alertId={notificationId}&scope=TRANSFER&scopeId={n}`
이고, 앱에 등록된 스킴이 `nextroute` 와 **글자 하나까지 같아야** 한다. 다르면 OS 가 열 곳을 못 찾는다
(서버 쪽 상수는 `FcmMessageFactory.DEEP_LINK_SCHEME`).

## 6. 함정

- **순서를 건너뛰지 마라.** `relay=true` 를 먼저 켜고 `sender=noop` 이면 부팅이 막힌다(일부러
  그렇게 뒀다 — 진짜 알림 행을 아무 데도 안 보낸 채 `SENT` 로 소모하면 나중 발송을 영영 막는다)
- **worker 에만 준다.** `env/fcm.env` 를 api 서비스에 붙이지 않는다
- **`compose.yaml` 의 worker 는 YAML 병합으로 공용 앵커를 덮는다.** `env_file`·`volumes` 목록을
  건드릴 때 기존 항목을 지우면 worker 가 DB·토스 설정을 잃는다
- **iOS 는 조용히 실패한다.** APNs 인증키가 Firebase 프로젝트에 없으면 Android 만 도착하는데
  지표로는 잘 안 드러난다. 실기기로 한 번은 확인한다
- **첫날은 지표를 지켜본다.** `canceled{reason}` 이 `sent` 보다 크면 재검증이 과한 것이고,
  `token.deactivated` 가 튀면 자격증명이 다른 프로젝트의 것일 수 있다

## 7. 이 문서 뒤에 남는 것

- 계측 보관 삭제 배치(30일) — `PHASE-5.md §1` 후속 과제
- 계정 삭제 시 token·계측 물리 삭제 — 탈퇴 경로가 생긴 뒤
- `journey_notification` 보관 — 흔적 표 설계 선행
