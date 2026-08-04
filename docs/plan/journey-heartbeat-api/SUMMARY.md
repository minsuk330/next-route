# 여정 heartbeat API (M)

## 뭘 하나

`POST /api/journeys/{journeyId}/heartbeat` 엔드포인트 하나를 추가한다. 진행 중인 여정의 안내 lease(3분)를 연장하고, 최신 스냅샷을 돌려준다.

## 왜

지금은 progress 요청(15초, 하차 임박 5초)이 lease 연장을 겸한다. 문제는 **보낼 진행도가 없는 상황**이다. GPS 정확도가 낮거나, 터널 안이거나, 아직 첫 위치 sample 을 못 받은 때다. 그동안 앱은 멀쩡히 돌고 있는데 서버는 "소식이 끊겼다"고 판단하게 된다. 다음 PR 에서 만료 처리 배치가 붙으면 그 판단이 곧 여정 종료가 된다. 그래서 진행도 없이도 살아 있음을 알리는 경로가 먼저 필요하다(SC-7 5항).

## 핵심 결정

- **번호를 올리지 않는다.** `commandVersion` 을 올리면 앱이 들고 있던 명령 번호가 이유 없이 낡아 정상 명령이 충돌로 거절된다. `evaluationVersion` 도 올리지 않는다 — 다시 계산한 것이 없다
- **lease 쓰기는 여정별 60초에 한 번으로 합친다.** progress 가 쓰는 `extendLeaseIfStale` 를 그대로 재사용한다. 30초마다 이 행을 쓰면 여정 하나가 계속 갱신되는 hot row 가 된다. lease 는 3분이라 60초 간격으로 늘려도 만료되지 않는다
- **예측(ETA)을 얹지 않는다.** 예측 합성은 실시간 도착 API 를 부른다. GPS 가 어두운 동안 30초마다 외부 API 를 부르면 provider 지연이 그대로 heartbeat 지연이 되고, 정작 예측의 근거가 될 새 위치도 없다. `PUT /progress` 와 같은 선택이다. 예측이 필요하면 `GET /api/journeys/{id}` 나 `/active` 를 쓴다
- **끝난 여정은 되살리지 않는다.** ACTIVE 가 아니면 409 다. heartbeat 로 만료된 여정이 복원되면 만료의 의미가 없다
- 소유자를 쿼리 조건에 넣는다. 남의 여정 lease 를 연장할 수 있으면 만료 판정 자체가 무의미해진다

## 건드는 곳

```
application/journey/port/in/HeartbeatJourneyUseCase.java        (신규)
application/journey/service/JourneyHeartbeatService.java        (신규)
infrastructure/adapter/in/api/journey/JourneyHeartbeatController.java  (신규)
```

마이그레이션 없음. 새 설정값 없음. 기존 파일 수정 없음.

## 검증법

- `JourneyHeartbeatIT` — lease 가 실제로 연장된다, 60초 안의 연속 호출은 다시 쓰지 않는다, 두 번호가 모두 그대로다, 응답에 예측이 없다, 끝난 여정은 409, 남의 여정은 404
- `JourneyHeartbeatControllerTest` — 200·401·404·409 상태 코드와 인증 보호

## 리스크

앱이 progress 를 만들 수 있는 상황에서도 heartbeat 를 습관적으로 보내면 요청 수가 늘어난다. 다만 lease 쓰기는 60초로 합쳐지므로 DB 부하는 늘지 않고, 실제 사용 패턴은 Phase 4 ⑧ 부하 측정에서 확인한다.
