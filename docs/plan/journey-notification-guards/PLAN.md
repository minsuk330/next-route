# 발송 직전 재검증 (Phase 5 ⑤, M) — 실행 플랜

- 슬러그: `journey-notification-guards`
- 브랜치: `feat/journey-notification-guards` (**fresh main 기준 독립 브랜치**)
- 마이그레이션: **없음**
- 선행: ②·③·④ 머지 완료(PR #123·#126·#128)
- 상위: [PHASE-5.md](../journey-navigation/PHASE-5.md) §⑤

## 0. 한 줄

**계산한 순간과 보내는 순간 사이에 세상이 바뀐다. 보내기 직전에 다시 확인하고, 뜻이 사라진 알림은
보내지 않는다.**

## 1. 지금 있는 것과 없는 것

`JourneyNotificationOutboxStateService#loadTask` 가 이미 넷을 본다.

| 확인 | 취소 사유 |
|---|---|
| 행이 아직 `PROCESSING` 인가 | (조용히 건너뜀) |
| 만료(`expiresAt`)가 지났나 | `expired` |
| 여정이 `ACTIVE` 인가 | `journey-ended` |
| `routeRevision` 이 그대로인가 | `route-changed` |

⑤ 가 더할 것은 셋이다.

| 확인 | 취소 사유 | 근거 |
|---|---|---|
| lease 가 살아 있나(`guidanceLeaseUntil > now`) | `lease-expired` | "lease 만료 이후 발송 0"이 Phase 5 완료 조건 |
| `commandVersion` 이 그대로인가 | `command-changed` | 사용자 명령으로 상황이 실제로 바뀌었다 |
| 접근 알림이 아직 뜻이 있나 | `transfer-missed`·`already-boarded` | ④가 넘긴 경쟁 창 |

## 2. 확정 사항 (구현 중 재논의 금지)

| # | 결정 | 이유 |
|---|---|---|
| D1 | **`commandVersion` 만 엄격 비교.** `evaluationVersion` 은 취소 근거로 쓰지 않는다 | 평가 번호는 ETA 숫자만 바뀌어도 오른다(대기 단계 10초 주기). 엄격 비교하면 정상 알림 상당수가 발송 전에 죽는다 — 알림이 조용히 사라지는 쪽이 더 나쁘다 |
| D2 | 접근 알림은 **`MISSED` 이거나 이미 탑승했으면** 취소 | 둘 다 "곧 온다"가 무의미해진 경우다. 놓쳤으면 거짓말이고, 이미 탔으면 늦은 알림이다 |
| D3 | 위험 알림에는 상태 재확인을 **걸지 않는다** | `MISSED` 알림을 상태로 다시 거르면 자기 자신을 지운다. 위험 알림은 lease·version·revision 만 본다 |
| D4 | 취소 사유를 **하나씩 이름 붙인다** | `recordCanceled(reason)` 이 이미 태그를 받는다. 뭉뚱그리면 왜 안 갔는지 운영에서 못 가른다 |
| D5 | 재확인은 **`loadTask` 한 곳**에서 끝낸다 | claim 과 발송 사이에 다른 자리를 만들면 검사 순서가 두 벌이 된다 |
| D6 | 이 PR 도 운영 발송을 켜지 않는다 | ⑥(payload·문구)이 남았다 |

## 3. 읽기 비용

`loadTask` 는 이미 `journey_session` 을 한 번 읽는다(`ACTIVE`·`routeRevision` 확인). lease 와
`commandVersion` 은 **같은 행에 있어 추가 조회가 없다**.

접근 알림만 `transfer_connection` 한 행을 더 읽는다. 위험 알림은 읽지 않는다(D3). 접근 알림은
환승당 평생 1회라 그 조회도 여정당 몇 번뿐이다.

## 4. 구현

`src/main/java/watoo/grd/nextroute/application/journey/service/JourneyNotificationOutboxStateService.java`

`loadTask` 의 검사 순서 — **싼 것부터, 그리고 뜻이 강한 것부터**.

```
1) 행이 PROCESSING 인가            (기존)
2) 만료가 지났나                   (기존)  → expired
3) 여정이 ACTIVE 인가              (기존)  → journey-ended
4) routeRevision 이 그대로인가      (기존)  → route-changed
5) lease 가 살아 있나              (신규)  → lease-expired
6) commandVersion 이 그대로인가     (신규)  → command-changed
7) 접근 알림이면 환승 상태·현재 단계  (신규)  → transfer-missed / already-boarded
```

- 5·6 은 이미 읽어 둔 `JourneySession` 에서 바로 본다
- 7 은 `checkpoint == TRANSFER_TARGET_APPROACHING` 일 때만 `TransferConnectionRepository` 로
  `scopeId`(= `transferIndex`) 행을 읽는다. 행이 없으면 **보내지 않는다**(`transfer-missed`) —
  근거가 사라진 알림이다
- `already-boarded` 판정은 `session.getCurrentLegIndex() > transfer.getToLegIndex()`
- 취소는 기존 `cancel(n, reason)` 을 그대로 쓴다(상태 전이·지표·로그가 한 곳)

## 5. 작업 순서

- [ ] **S1** `loadTask` 에 5·6 추가 + 취소 사유 두 개
      → `./gradlew compileJava`
- [ ] **S2** `TransferConnectionRepository` 주입 + 7 추가(접근 알림 한정)
- [ ] **S3** `JourneyNotificationMetrics#recordCanceled` Javadoc 의 사유 목록 갱신
- [ ] **S4** 테스트(§6)
- [ ] **S5** 전체 회귀 `./gradlew test`
- [ ] **S6** `PHASE-5.md` ⑤ 절 구현 완료 표시 + 켜기 절차 갱신
- [ ] **S7** 커밋(플랜 파일 포함) → PR

## 6. 테스트

`src/test/java/watoo/grd/nextroute/application/journey/notification/` 의 relay IT 관례를 따른다.

| # | 시나리오 | 기대 |
|---|---|---|
| 1 | lease 가 지난 여정의 알림 | 발송 0, 상태 `CANCELED`(`lease-expired`) |
| 2 | lease 가 살아 있음 | 정상 발송 |
| 3 | `commandVersion` 이 오른 뒤 | `CANCELED`(`command-changed`) |
| 4 | `evaluationVersion` 만 오른 뒤 | **정상 발송**(D1 — 이걸로 취소하면 알림이 조용히 죽는다) |
| 5 | 접근 알림 + 환승이 `MISSED` | `CANCELED`(`transfer-missed`) |
| 6 | 접근 알림 + 이미 다음 단계로 이동 | `CANCELED`(`already-boarded`) |
| 7 | 접근 알림 + 아직 대기 중·`AT_RISK` | 정상 발송 |
| 8 | **위험 알림 + 환승이 `MISSED`** | 정상 발송(D3 — 자기 자신을 지우면 안 된다) |
| 9 | 환승 행이 사라진 접근 알림 | `CANCELED`(`transfer-missed`) |
| 10 | 취소 지표가 사유별로 갈린다 | 사유 태그 6종 |

회귀: ②③④의 IT 전부 + 버스 도착 알림·토스 경로 무손상. `./gradlew test` 전체.

## 7. 함정

- **`MISSED` 위험 알림을 상태로 거르지 마라**(D3). 가장 중요한 알림이 자기 상태 때문에 사라진다
- **`evaluationVersion` 으로 취소하지 마라**(D1). 조용한 실패가 시끄러운 중복보다 나쁘다
- `loadTask` 는 `@Transactional` 이다. 여기서 외부 호출을 하지 않는다
- 취소는 시도 횟수를 하나 쓴다(claim SQL 이 올린다). 그대로 둔다 — ②의 계약이다
- 접근 알림 조회는 **접근 알림일 때만** 한다. 모든 행에 걸면 위험 알림 경로에 조회가 하나 붙는다

## 8. 범위 밖

- payload 계약·최종 문구·deep link — ⑥
- 알림 효과 계측 — ⑦
- 운영 발송 활성화 — ⑥ 뒤
