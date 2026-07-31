# 여정 상태 저장 · 경로 쪼개기 (L)

## 뭘 하나

진행 중인 여정을 PostgreSQL에 저장하고, 선택 경로를 `WALK / WAIT / RIDE` leg와 복수 노선 후보로 compile한다. 최소 시작 API와 ACTIVE Journey 복구 API까지 만든다.

## 구현 단위

1. `journey-schema-domain` — V35, 6테이블, entity·repository
2. `journey-compiler` — 실제 검색 fixture를 leg·transfer·route option으로 변환
3. `journey-start-api` — snapshot 검증, 시작·ACTIVE 조회, 동시성·보안

## 핵심 결정

- V34는 토큰 세대 migration이 사용 중이므로 Journey는 **V35**를 사용한다.
- `commandVersion / evaluationVersion / routeRevision`을 분리한다.
- 시작 상태는 `1 / 0 / 1`, 첫 leg만 `ACTIVE`다.
- `lastHeartbeatAt`, `guidanceLeaseUntil`, `endReason`을 처음부터 저장한다.
- `SubPathResult.lanes` 복수 후보를 `journey_leg_route_option`에 모두 저장한다.
- 식별자 누락은 leg lifecycle을 `UNKNOWN`으로 바꾸지 않고 `evidenceQuality=UNKNOWN`으로 표현한다.
- Redis snapshot 조회와 compile은 트랜잭션 밖, 사용자 잠금과 DB 저장만 짧은 트랜잭션으로 처리한다.
- `replace=true`는 기존 Journey 취소를 flush한 뒤 신규 Journey를 insert한다.
- route snapshot은 `JsonNode/JSONB`로 저장하고 수정하지 않는다.
- `JourneyRevision`에는 수락된 경로만 `ACTIVE/SUPERSEDED`로 저장하고, 미수락 대안은 Phase 6 `reroute_proposal`로 분리한다.
- 절대시각은 `timestamptz/Instant`를 사용한다.
- `reroute_proposal`은 Phase 6으로 미룬다.

## API

```http
POST /api/journeys?replace=false
GET  /api/journeys/active
```

두 API 모두 JWT 인증이 필수다. 동일 사용자의 ACTIVE Journey가 있으면 자동 종료하지 않고 `409 ACTIVE_JOURNEY_EXISTS`와 현재 snapshot을 반환한다.

## 동시성

`users` 행을 `PESSIMISTIC_WRITE`로 잠가 같은 사용자의 시작 요청을 직렬화하고, `uq_journey_active_per_user` partial unique index를 최종 방어선으로 둔다. unique 위반만 정확히 409로 번역하며 다른 무결성 오류는 숨기지 않는다.

## 배포

V35 SQL을 수동 실행하지 않는다. migration을 bootJar에 포함하고 API 시작 시 Flyway가 적용하게 한다. 이번 단계는 Worker 로직을 바꾸지 않으므로 역할 분리가 완료된 VPS에서는 `./deploy.sh api`로 배포 가능하다.

## 필수 검증

- V35·CHECK·partial unique index
- JSONB object round-trip과 route snapshot 불변성
- S1·S2·S3·S4·S6 및 복수 lane compile
- 첫 leg ACTIVE, 나머지 PENDING
- 동시 시작·동시 replace에서도 ACTIVE 하나
- 만료 410, 소유자 불일치 403, 인증 없음 401
- `EntityManager.clear()` 후 ACTIVE Journey 복구
- 전체 테스트 회귀 없음
