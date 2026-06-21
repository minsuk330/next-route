# Plan — 환승 지점 도착예측 통합 (Route Search × Bus Arrival ML)

`GET /api/route/search`의 각 **버스 승차 지점**(trafficType=2)마다, 그 버스가 사용자 도착 시점 기준 언제
오는지를 응답에 싣는다. 실시간 버스 도착 API 우선, 이미 지나간 경우만 ML 모델로 다음 버스 예측. 모델은
top-30 노선만 학습.

## 흐름 한눈에
1. 사용자 환승 정류장 도착시각 계산 (`now + 앞 subPath sectionTime[분] 누적`)
2. 실시간 버스 API 조회 → 사용자 도착 이후 도착하는 버스 있으면 그 값 (REALTIME)
3. 이미 지나갔으면 모델 호출 — `getBusPosByRtid` 접근 차량 스냅샷 → feature → 예측 (MODEL)
4. 모델은 top-30 노선만 → 미포함이면 NONE/UNSUPPORTED_ROUTE

## PR 분리 (per-task, fresh main 독립 브랜치 — 스택 금지)
**원칙: 브랜치는 독립(fresh main), API 계약은 PR1 기준.**

| PR | 제목 | 브랜치 | 계약/순서 |
|---|---|---|---|
| [PR1](./PR1-fastapi-ml-serving.md) | Python FastAPI ML Serving | 독립 | `/predict` 계약 정의 |
| [PR2](./PR2-java-ml-client.md) | Java ML Client (hexagonal) | 독립 | PR1의 `/predict` 계약 따름 |
| [PR3](./PR3-transfer-arrival-enricher.md) | 식별자 보존 + Resolver + Dedupe Orchestration + DTO | 독립 | PR2 머지 후 분기(코드 의존) |
| [PR4](./PR4-ops-hardening.md) | 운영 하드닝 (Cache/Budget/Parallel Limit) | 독립 | PR3 후 |
| [PR5](./PR5-transfer-predict-api.md) | 환승 예측 호출 API (단일 버스, userArrivalAt 기준) | 독립 | 선택 UI(PR #26) 후 |

## 운영 게이트
PR3까지 배포 시 **`route.transfer-arrival.enabled=false`(기본값) 유지** — `ML_PREDICTOR_ENABLED=false`만으로는
REALTIME stop API fan-out이 계속 동작해 PR4 안전장치(deadline·호출 상한) 없이 외부 API fan-out이 열림.
기능 toggle을 꺼야 fan-out 자체가 차단됨. PR4(캐시·rate-limit·병렬제한) + path/lane cap + 메트릭(호출수·p95) +
서울 버스 API error code 7 즉시 중단 갖춘 뒤 활성화. (배포 설정 검증으로 조기 활성화 차단 권장.)
