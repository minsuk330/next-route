# PR4 — 운영 하드닝 (Cache / Budget / Parallel Limit)

> 환승 지점 도착예측 통합 — 운영 활성화 전 안전장치. 별도 후속 브랜치.
> 선행: [PR3](./PR3-transfer-arrival-enricher.md)

## Context

PR3은 전용 no-retry client + dedupe orchestration + stale 제외까지만 포함한다. 운영에서 `ML_PREDICTOR_ENABLED=true`로
켜려면 여러 ODSAY 경로의 fan-out 호출이 검색 지연·API 사용량을 키우지 않도록 캐시/예산/병렬 제한이 필요하다.

## 작업
- **Redis 10~20s 캐시**: 검색용 버스 API 호출(stopId/routeId 키). 기존 RedisConfig 재사용.
- **검색-시점 API 예산/rate-limit + provider quota 보호**: ⚠️ 검색 어댑터는 collector와 **동일 엔드포인트·
  동일 `seoul.api.bus-key`**를 씀 → 단순 독립 카운터는 collector 로컬 사용량만 보존하고 **실제 provider 일일 quota는
  공동 소진**, 검색 트래픽이 code 7 전에 collector·학습 데이터 수집을 고갈시킬 수 있음. →
  **엔드포인트별 provider quota를 Redis 원자 카운터로 공동 관리하고 search/collector 예약량을 분할**하거나,
  **검색에 별도 credential** 사용.
  - ⚠️ **상태 영속성**: 현재 compose Redis는 `allkeys-lru` + `--save ""` → eviction·재시작 시 quota 카운터·
    breaker 상태만 사라지고 **실제 provider 사용량은 그대로** → search/collector가 quota를 재배정해 수집 고갈 위험.
    → quota/breaker 상태는 **영속·non-evictable 저장소**(별도 Redis instance/DB, 또는 해당 키만 persistence·
    eviction 제외)에 두고, **상태 유실·Redis 장애 시 fail-closed**(호출 차단) 계약.
- **병렬 호출 수 제한**: 환승점·경로 fan-out 동시 호출 제한.
- **공유 circuit breaker**: 서울 버스 API **error code 7**(일일 트래픽 초과 등) 발생 시 collector·search가
  **공유하는** 차단 상태로 즉시 호출 중단.
  - ⚠️ 기존 `BusApiBlockStatusPort`는 `getBlockedUntil()` 조회만 제공하고 차단 상태가 `SeoulBusApiAdapter`의
    **private in-memory 필드** → search adapter가 error code 7을 받아도 collector breaker를 trip 못 하고,
    다중 인스턴스 간 공유도 안 됨.
  - → **`trip(until)` + 조회를 제공하는 별도 상태 포트** 신설, **Redis 등 공유 저장소에 원자적 기록**해
    collector·search가 함께 읽고/쓰게 설계. 기존 collector도 이 공유 포트를 쓰도록 이관.
- **enrichment deadline**: 검색 요청 1건 전체 enrich에 상한 시간(초과 시 그때까지 결과만, 나머지 `ERROR`/`NONE`).

## Rollout 게이트 (운영 활성화 조건 — 구체값은 구현 시 확정·문서화)
- PR4(캐시·rate-limit·병렬제한) 완료.
- **구체 상한 명시**: 검색당 최대 처리 `path` 수, `lane` 수, 외부 호출 수, 병렬 동시성, enrichment deadline(ms).
- **메트릭**: 호출 횟수 + p95 latency + **`source`/`status`별 성공률·fallback 비율**.
- 공유 circuit breaker(error code 7) 동작 확인.
- **상태 영속**: quota/breaker 저장소가 영속·non-evictable, Redis 재시작/eviction 후 상태 복구 검증.

## Verification
- 캐시 hit/miss·TTL 동작 테스트.
- rate-limit 초과 시 graceful 동작(검색 자체는 유지) 테스트.
- 병렬 제한 하에서 fan-out 호출 수 상한 검증.
- **quota/breaker 영속**: Redis 재시작·eviction 후 상태 유지/복구, 상태 유실 시 fail-closed(호출 차단) 테스트.
