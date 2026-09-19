# OTP provider 전환 (shadow → 실제 라우팅)

## 뭘 하나
VPS 에 dormant 로 떠 있는 OTP(H+F)를 수도권 검색의 실제 라우팅 provider 로 승격.
승격 전, OTP 를 보호하는 글로벌 동시성 상한(Redis semaphore, permit 4)을 코드로 추가.
그 뒤 env 토글 3값으로 실 트래픽을 OTP 로 전환.

## 왜
correctness(경로 정확성)는 로컬 확정(A와 fingerprint mismatch 0). 남은 유일 안전장치 = 동시요청 폭주로부터 OTP 메모리 보호. 그것만 넣으면 전환 가능.

## 핵심 결정 (codex 리뷰 반영)
- correctness 게이트는 이미 통과(로컬). P4_FORMAL_PENDING 은 성능 주장일 뿐 안전 블로커 아님.
- 글로벌 Redis semaphore permit=4 (api+worker 합산). 로컬 JVM 상한 기각(별 JVM 라 합산 안 됨).
- 초과 시 요청 예산 안에서 대기 → 못 얻으면 504(OdSayApiException.timeout). **ODsay 폴백 없음**(provider 안 섞음).
- Redis 장애 = **fail-closed**(codex, fail-open 기각 — mem_limit≠동시성상한). now=Redis TIME. lease TTL startup 검증.
- 새 프레임워크 0 — RedisBusApiBreaker/Quota 패턴 미러, Lua ZSET 원자.

## 건드는 곳
OtpConcurrencyGate(신규 Port) · RedisOtpConcurrencyGateAdapter(신규) · RouteSearchService · RemainingRouteSearchService · OtpRoutingProperties · compose.yaml(토글 env)

## 리스크
- 공용 Redis `allkeys-lru` 가 semaphore ZSET 축출 가능 → **리스크 감수 결정(단일 Redis)**: N=4 는 best-effort, 하드 상한은 OTP mem_limit 3000m. hot key 라 실무상 드묾.
- permit leak(release 누락/crash) → lease TTL + finally 로 가드. 배선 2곳 중 누락 시 상한 반쪽.
- 전환 = 프로덕션 실 트래픽이 성능 미검증(P4) 엔진으로. 롤백=env 되돌림+재생성(ODSAY 즉시).

## 검증법
gate 단위테스트(획득/포화/lease만료/failopen/idempotent) + 배선 테스트(기본구성 ODSAY 회귀 0) green.
전환 후 Grafana 로그로 OTP 경로 확인, 5번째 동시요청 대기→degraded(ODSAY 안 섞임), 롤백 시 ODSAY 복귀.
