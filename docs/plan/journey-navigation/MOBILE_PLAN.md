# Journey Navigation 모바일 구현 계획

- 기준 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 백엔드 계약: [PLAN.md](./PLAN.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 작성일: 2026-07-27
- 대상 레포: React Native `nextroute-app`

## 1. 책임 경계

모바일은 사용자 위치 수집, 화면 표시, 로컬 카운트다운, 백그라운드 실행, heartbeat, 푸시 수신을 담당한다. Journey 상태·세 version·환승 판정·알림 checkpoint의 기준은 서버다.

- 포그라운드: `recommendedPollSeconds`에 따른 REST polling
- 백그라운드: 네이티브 위치·heartbeat 세션 + FCM
- resume·push tap: 최신 Journey snapshot REST 조회
- 오프라인 command: `eventId`를 가진 로컬 outbox에 저장 후 재전송
- 초 단위 남은 시간: 서버의 절대 ETA와 `serverTime`을 기준으로 로컬 계산
- foreground·Android foreground service는 기본 30초 heartbeat, iOS background는 Core Location callback으로 서버의 3분 `guidanceLeaseUntil` 연장

## 2. 화면·상태

### 2.1 여정 시작

- 경로 검색 응답의 `searchSessionId`, `pathKey`로 Journey 시작
- 이미 ACTIVE Journey가 있으면 기존 여정 복원 또는 명시적 교체 선택
- 클라이언트가 전체 route JSON을 Journey 생성 요청으로 보내지 않음

### 2.2 네비게이션 화면

- 현재 leg, 다음 행동, 목적지 ETA
- 환승 상태와 `transferSlack`
- 버스 하차까지 남은 정류장·시간
- 현재 및 남은 leg 폴리라인
- freshness·`UNKNOWN`·사용자 확인 필요 상태
- 탑승·하차·차량 선택 확인

### 2.3 재탐색 제안

- 기존 도착 ETA와 대안 도착 ETA 및 절약 시간 표시
- 환승 횟수·도보시간·제안 만료 표시
- 수락 시 현재 `baseCommandVersion`과 `routeRevision`으로 요청
- `409`이면 최신 snapshot을 조회하고 만료된 제안 UI 제거

## 3. 로컬 Journey store

```text
journeyId, commandVersion, evaluationVersion, routeRevision, status
guidanceLeaseUntil
serverTimeOffset
snapshot
pendingEvents[eventId]
lastDisplayedAlertId
pollingState
```

- command 결과는 `commandVersion`, 평가 snapshot은 `evaluationVersion`이 더 높은 경우에만 적용한다.
- push payload를 Journey 상태로 직접 적용하지 않는다.
- 이전 routeRevision의 응답·알림·비동기 작업은 폐기한다.
- 로그아웃 시 위치 세션과 polling을 종료하고 민감한 로컬 상태를 정리한다.
- 서버가 `EXPIRED`로 반환한 Journey와 pending event는 정리하고 복원하지 않는다.

## 4. 구현 단계

### Phase M1. API 계약·store

- Journey API client와 DTO
- 인증 token·device session 연결
- Journey store와 세 version merge policy
- 시작·복원·완료·취소 flow

### Phase M2. 포그라운드 내비게이션

- Journey 화면과 현재 leg 표시
- Naver Map 현재 위치·leg polyline 표시
- 절대 ETA 기반 로컬 카운트다운
- adaptive polling과 앱 focus 즉시 refresh
- 사용자 탑승·하차·차량 선택 이벤트

### Phase M3. 오프라인·동시성

- UUID `eventId` 로컬 outbox
- 네트워크 복구 시 순서대로 재전송
- duplicate 성공 처리와 `409` 최신 snapshot 수렴
- 사용자 event에 `baseCommandVersion`·`routeRevision` 포함
- routeRevision 변경 시 이전 화면 작업 취소

### Phase M4. 백그라운드·푸시

- Android foreground location service와 필수 상시 알림
- iOS background location mode와 native Core Location callback 기반 heartbeat
- Android·iOS FCM token 등록·갱신·비활성화
- 정상 background에서 위치·heartbeat를 보내 3분 lease 연장
- background 상태에서 하차·환승 위험·재탐색 제안 FCM 알림 수신
- push deep link로 Journey 진입 후 최신 snapshot 조회
- lease가 유효한 process 재생성·재실행은 ACTIVE Journey 복원
- `EXPIRED` 응답이면 네비게이션 화면·위치·polling·로컬 outbox 종료

### Phase M5. 하차 안내

- 남은 정류장·남은 시간 표시
- 3정거장 전·1정거장 전·도착 임박 알림 표시
- 동일 `alertId`와 checkpoint 중복 표시 차단
- stale 데이터에서는 확정 안내 대신 사용자 확인 UI

### Phase M6. 부분 재탐색

- 환승 임박·위험도 상승 시 제안 배너
- 현재 경로와 대안의 ETA·환승·도보 비교
- `baseCommandVersion`으로 제안 수락하고 새 routeRevision 적용
- 새 revision 적용 시 지도 polyline·leg·알림 상태 원자적 교체

## 5. 모바일 완료 조건

- Android·iOS에서 포그라운드 polling과 로컬 카운트다운 동작
- Android·iOS FCM 실기기에서 foreground·background 알림 수신
- 화면 off·background에서 lease 연장과 push tap Journey 복원
- lease 만료 전 네트워크 복구 시 이벤트가 중복 상태 전이 없이 재전송
- heartbeat 3분 미수신 후 `EXPIRED` 처리되고 재실행해도 Journey가 복원되지 않음
- 하차 checkpoint가 화면과 알림에서 중복 표시되지 않음
- 재탐색 수락 후 이전 revision의 지도·알림·비동기 응답이 노출되지 않음
- 위치 권한 거부·축소·회수 시 수동 확인 flow로 안전하게 저하
