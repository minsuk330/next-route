# PR5 — 환승 예측 호출 API (단일 버스, 사용자 도착시각 기준)

## 배경

선택 UI 조회 API(PR #26, 머지됨)로 정류장·노선 탐색은 갖춰졌다. 마지막 단계 = UI 흐름의 종착점인
**실제 예측 호출 API**가 없다.

사용자 흐름: **정류장 선택 → 특정 버스 1개 선택 → 도착 예상 시각 입력 → 그 시각 기준 환승(탑승) 가능성 예측.**

현재 예측 로직은 `TransferArrivalEnricher`(744줄)가 경로검색 결과(`RouteSearchResult`) 안에서 wave 단위로만
수행한다 — 단독 `(stopId, routeId, seq, userArrivalAt)` 호출 진입점이 없다. 이 PR이 그 진입점을 만든다.
참고: enricher는 `RouteSearchService.search()`에 이미 wired 돼 있으나 `route.transfer-arrival.enabled`(기본 false)로 비활성.

### 확정 계약
- 호출 단위 = **단일 버스 1건** (배치 아님).
- `userArrivalAt` = **필수** (사용자 입력 시각). 이 시각 기준으로 boardable 판정.

## 리뷰 반영 (1차 설계 수정)

| # | 수정 |
|---|---|
| 게이트 | 전체 gate = **`route.transfer-arrival.enabled`** (ml.enabled 아님). ML 비활성은 **realtime은 그대로**, MODEL 단계만 MODEL_UNAVAILABLE |
| 미지원 노선 | "실시간 도착만" 제공해야 함 → isSupported=false는 **position/ML만 차단, stop API는 호출** |
| 지원 상태 | bool 아닌 **tri-state** SUPPORTED / UNSUPPORTED / **UNKNOWN**(캐시 미적재). UNKNOWN은 ML 시도(serving이 권위) |
| seq 신뢰 | 클라 제공 seq는 **`existsByRouteIdAndStopIdAndSeq`로 조합 검증** 후 사용 |
| loop 실패 | 루프 노선 seq 해석 실패 = **STOP_MAPPING_FAILED** (UNSUPPORTED_ROUTE 아님) |
| stop API 실패 | ERROR/LIMITED라도 **targetSeq 확정 시 position/ML fallback 시도** (BLOCKED은 provider 차단이라 양쪽 중단) |
| userArrivalAt 범위 | now 기준 **과거·과도한 미래 → 400**. 모델 horizon(기본 +40분)으로 제한 |
| boardable | predicted≥userArrivalAt만 고르면 성공 응답은 항상 boardable=true(무의미). **earliest 버스를 그대로 반환**, boardable은 그 버스가 userArrivalAt 이후인지, waitSeconds는 **부호 有**(음수=이미 지나감) |
| deadline | **단일 요청 전체 deadline** + 각 외부 콜 전 잔여시간 검사 |
| 컨트롤러 테스트 | @WebMvcTest 검증 추가 |

## 재사용

| 자산 | 위치 | 용도 |
|---|---|---|
| `SearchTimeBusQueryPort` | `application/route/port/out/` | `getArrInfoByStop`(실시간), `getBusPosByRtid`(위치). 캐시 15s / 검색쿼터 / 공유 breaker 내장(`CachedSearchTimeBusAdapter`) |
| `BusQueryResult<T>` + `Outcome` | 위 포트 | OK / BLOCKED / LIMITED / ERROR 구분 → 상태 매핑 |
| `MlFeatureVectorBuilder.build(reqId, BusPositionInfo, targetSeq, routeId)` | `application/route/service/` | feature 생성(train.py와 동일) |
| `MlArrivalPredictorPort.predict(vectors)` | `application/route/port/out/` | ML 배치 호출, item별 AVAILABLE / UNSUPPORTED_ROUTE |
| `TransferStopResolver.resolveSeq(routeId, stopId)` | `application/route/service/` | seq 미제공 시 후보 해석 |
| `BusRouteStopRepository.existsByRouteIdAndStopIdAndSeq` | `domain/bus/repository/` | 클라 제공 seq 조합 검증 |
| `TransferArrival.Source/Status` enums | `application/route/dto/` | 응답 source/status 재사용 |
| `PredictionSupportService` | `application/route/service/` (PR #26) | tri-state 지원판정(아래 확장) |
| `TransferArrivalProperties.enabled` | `application/route/config/` | 전체 feature 게이트 |
| `MlPredictorProperties.enabled` | `application/route/config/` | MODEL 단계 게이트 |

### `PredictionSupportService` 확장 (소폭)
현재 `isSupported(routeId):boolean` + `supportedRouteIds():Set`. **`Support support(routeId)`** 추가:
- 캐시가 한 번이라도 정상 적재됨(`loaded=true`) → 포함=SUPPORTED, 미포함=UNSUPPORTED.
- 미적재(`loaded=false`, serving 미기동/장애) → **UNKNOWN**.
- `loaded` volatile flag 추가(refresh 성공 시 set). 기존 `isSupported`(배지용)는 불변 — 회귀 없음.

## 설계: 신규 `SingleTransferPredictor` (enricher 불변)

> ⚠️ enricher(검색 hot path)는 건드리지 않는다. 공유 유닛 추출은 회귀 위험 커 후속 리팩터. 포트/빌더/리졸버
> 재사용하되 realtime→ML 선택 로직은 단일타깃용으로 구현(통제된 중복).

`application/route/service/SingleTransferPredictor.java`:
```
TransferPredictionResult predict(stopId, routeId, seqOrNull, userArrivalAt)
```
deadline: 메서드 진입 시 `Instant deadline = now + props.predictDeadlineMs`. 각 외부 콜 전 잔여시간>0 확인, 초과 시 LIMITED.

흐름:
1. **feature gate**: `transferArrivalProps.enabled=false` → DISABLED 즉시 반환(콜 0).
2. **seq 결정·검증**:
   - seq 제공 → `existsByRouteIdAndStopIdAndSeq(routeId, stopId, seq)` 검증. false → STOP_MAPPING_FAILED.
   - seq 없음 → `resolver.resolveSeq` — 빈 후보 → STOP_MAPPING_FAILED, 단일 → 사용, 다중(loop) → realtime 도착으로 disambiguate, 실패 → **STOP_MAPPING_FAILED**.
   - 확정된 targetSeq 확보.
3. **REALTIME**: `busPort.getArrInfoByStop(stopId)` →
   - OK: routeId 일치 `BusArrivalInfo`에서 **가장 이른 도착 버스** → source=REALTIME (predictedArrivalAt / vehicleId / waitSeconds / boardable). realtime 있으면 여기서 반환.
   - BLOCKED → BLOCKED 반환(provider 차단). ERROR/LIMITED → 종료하지 말고 4로(targetSeq 확정됨).
   - OK인데 해당 routeId 도착 없음 → 4로(MODEL fallback).
4. **MODEL 게이트**: `support(routeId)`가 **UNSUPPORTED** 또는 `mlProps.enabled=false` →
   - realtime이 없었으면 MODEL_UNAVAILABLE(ml off) / UNSUPPORTED_ROUTE(미지원). 버스 position 콜 안 함.
   - 즉 미지원·ML오프 노선은 **realtime만 제공**, MODEL 스킵.
5. **MODEL fallback**(SUPPORTED 또는 UNKNOWN, ml on): `busPort.getBusPosByRtid(routeId)` →
   - BLOCKED/LIMITED/ERROR → 상태 매핑.
   - 유효 차량(targetSeq 이전, 정상 dataTm) → `featureBuilder.build` → `mlPort.predict` → **가장 이른 도착 차량** → source=MODEL. ML이 UNSUPPORTED_ROUTE 주면(UNKNOWN이었던 경우) → UNSUPPORTED_ROUTE. NO_VEHICLE / MODEL_UNAVAILABLE 매핑.
6. **boardable / waitSeconds**: 선택된 버스 기준 `boardable = predictedArrivalAt >= userArrivalAt`, `waitSeconds = predictedArrivalAt - userArrivalAt`(음수 가능 = 이미 지나감). earliest 버스를 거르지 않고 그대로 반환하므로 boardable이 true/false 모두 의미.

## 신규 DTO `TransferPredictionResult`

```
stopId, routeId, seq,
source(TransferArrival.Source), status(TransferArrival.Status),
calculatedAt, userArrivalAt,
predictedArrivalAt(nullable), waitSeconds(nullable, 부호 有),
boardable(nullable Boolean), vehicleId(nullable), modelVersion(nullable)
```
- 성공(REALTIME/MODEL): predictedArrivalAt/boardable/waitSeconds 채움.
- 비성공(NO_VEHICLE/UNSUPPORTED_ROUTE/BLOCKED/...): predicted* null, boardable null.

## 엔드포인트

`GET /api/transfer/predict?stopId=&routeId=&seq=&userArrivalAt=`

- `stopId`, `routeId`: 필수, blank 금지.
- `seq`: 선택(int, >0). 누락 시 resolver.
- `userArrivalAt`: ISO-8601 instant, 필수.
  - **범위 검증**: `now - grace(예 1분) ≤ userArrivalAt ≤ now + maxFutureMinutes(기본 40)`. 밖 → 400.
- 컨트롤러 `infrastructure/adapter/in/api/transfer/TransferPredictController.java`, `@Validated`, swagger.
- use case 포트 `application/route/port/in/PredictTransferUseCase.java`, service 구현.

## 신규 설정 `transfer.predict.*` (또는 `TransferArrivalProperties` 확장)
- `predict-deadline-ms` (기본 2500): 단일 요청 전체 deadline.
- `max-future-minutes` (기본 40): userArrivalAt 미래 상한(모델 horizon).
- `past-grace-seconds` (기본 60): 과거 허용 여유.

## 쿼터 / 지연
- 호출당 버스 API ≤ 2(arr 1 + pos 1) + ML 1. `SearchTimeBusQueryPort` 15s 캐시·검색쿼터·breaker 상속.
- realtime 도착 있으면 position/ML 생략. 미지원·ML오프면 position/ML 생략(realtime만).
- DISABLED 시 콜 0. 전체 deadline 초과 시 LIMITED.

## 변경 파일
신규:
- `application/route/service/SingleTransferPredictor.java`
- `application/route/dto/TransferPredictionResult.java`
- `application/route/port/in/PredictTransferUseCase.java`
- `infrastructure/adapter/in/api/transfer/TransferPredictController.java`
- `src/test/.../SingleTransferPredictorTest.java`, `TransferPredictControllerTest.java`(@WebMvcTest)

수정:
- `PredictionSupportService` — `Support support(routeId)` tri-state + `loaded` flag.
- `TransferArrivalProperties`(또는 신규 properties) — predict-deadline/horizon/grace.
- 공통 헬퍼(`earliestRealtimeArrival`, `isValidVehicle`, `pickVehicleId`)는 회귀 0 우선 복제(기본) / DRY 우선 추출.

## 검증
1. `./gradlew compileJava test` — 신규 단위 테스트 통과(기존 환경 의존 실패 10건 무관).
2. **단위 테스트** `SingleTransferPredictorTest`(포트 mock):
   - realtime hit → REALTIME, boardable true/false(userArrivalAt 전후), waitSeconds 부호.
   - realtime 없음 + SUPPORTED + ml on → MODEL, earliest.
   - 미지원(UNSUPPORTED) → realtime만, position/ML 콜 0(verify never), MODEL 단계 UNSUPPORTED_ROUTE.
   - UNKNOWN(캐시 미적재) → ML 시도, serving UNSUPPORTED_ROUTE → 그 상태.
   - stop API ERROR/LIMITED + targetSeq 확정 → position/ML fallback 진입.
   - stop API BLOCKED → BLOCKED(양쪽 중단).
   - `transferArrivalProps.enabled=false` → DISABLED, 콜 0.
   - 제공 seq 조합 불일치 → STOP_MAPPING_FAILED.
   - deadline 초과 → LIMITED.
3. **컨트롤러 테스트** `TransferPredictControllerTest`(@WebMvcTest): blank id, seq≤0, 잘못된 ISO, 과거·+40분 초과, 필수값 누락 → 400.
4. **로컬 e2e**: `transfer.arrival.enabled=true` + `ml.predictor.enabled=true` + serving 기동 →
   `GET /api/transfer/predict?stopId=...&routeId=...&seq=...&userArrivalAt=2026-06-21T09:30:00Z`
   → source/status/predictedArrivalAt/boardable. userArrivalAt 전후로 boardable 토글, 미지원 노선은 realtime-only 확인.

## 후속 (범위 밖)
- enricher ↔ 단일예측 공유 유닛 추출 리팩터.
- 정류장 단위 배치 예측.
- 정류장명 contains(pg_trgm), projection 통합 테스트(PostGIS Testcontainers).
