# API ETA 누수 분석

작성일: 2026-08-11 · 검증 데이터: `service_date=2026-07-30` · 재현: `python check_api_eta_leakage.py 2026-07-30`

## 요약

`api_target_seconds_to_arrival`은 **피처로 사용할 수 없다.** 이 값은 버스 운영사가 사전에 발행한 도착 예측이 아니라, **도착 이벤트 레코드에 저장된 단일 상수**이며 실제 도착 시각보다 34~599초 **뒤에** 찍힌 값이다.

그 결과 기존 실험에서 기준선으로 삼아온 **`baseline_api` MAE 110.88초는 정당한 비교 대상이 아니다.** 이 숫자를 목표로 삼으면 어떤 모델도 이길 수 없고, 이겼다면 그 모델 역시 같은 누수를 쓰고 있다는 뜻이다.

**다행히 누수는 학습된 모델에 들어간 적이 없다.** [train.py](../train.py)와 [train_compare.py](../train_compare.py) 모두 이 컬럼을 `LEAKAGE_COLUMNS`로 제외해왔고 `--with-api-feature`는 기본값이 꺼짐이다. 오염된 것은 **모델이 아니라 잣대**다. 배포된 모델은 영향받지 않았다.

## 1. 문제의 컬럼이 어떻게 만들어지는가

```
api_target_seconds_to_arrival = api_estimated_arrival_at − snapshot_at
```

`api_estimated_arrival_at`은 `bus_arrival_label_event` 테이블 — 즉 **도착 이벤트 1건당 1행**인 테이블의 컬럼이다 ([build_dataset.py](../build_dataset.py) `label_frames`).

[build_dataset.py](../build_dataset.py) `dataset_for_route`는 이 도착 이벤트 1건을 `(route_id, vehicle_identity)` 기준으로 **최대 40분 전까지의 위치 스냅샷 전부**와 조인한다. 실측 결과 도착 1건당 평균 32.8개, 최대 40개의 스냅샷이 붙는다.

여기서 구조적 문제가 발생한다. 스냅샷이 33개든 40개든 **붙는 `api_estimated_arrival_at`은 전부 같은 하나의 값**이다. 40분 전 스냅샷과 1분 전 스냅샷이 동일한 도착 추정 타임스탬프를 갖는다.

## 2. 증거

세 가지 증거는 서로 독립적이며, 어느 하나만으로도 결론이 성립한다.

### 2.1 도착 이벤트 내부에서 잔차가 완전히 불변

`corrected_target − api_target`을 도착 이벤트별로 묶어 표준편차를 계산했다.

| 항목 | 값 |
|---|---|
| 도착 이벤트 수 | 172,349 |
| 이벤트당 스냅샷 (평균 / 최대) | 32.8 / 40 |
| **이벤트 내 잔차 표준편차** | **0.0000초** |
| 전체 잔차 표준편차 | 70.12초 |

실제 사례 (한 도착 이벤트의 연속 스냅샷):

| snapshot_at | api_target | corrected_target | 잔차 |
|---|---|---|---|
| 09:57:51 | 2434 | 2395 | **−39** |
| 09:58:42 | 2383 | 2344 | **−39** |
| 09:59:42 | 2323 | 2284 | **−39** |
| 10:00:43 | 2262 | 2223 | **−39** |
| 10:01:50 | 2195 | 2156 | **−39** |

`api_target`이 경과 시간만큼 정확히 감소한다. 스냅샷마다 재추정되는 실시간 예측이라면 버스가 이동하면서 추정이 갱신되어 잔차가 변해야 한다. 잔차가 소수점까지 고정이라는 것은 **저장된 값이 하나뿐**이라는 뜻이다.

### 2.2 저장된 ETA가 실제 도착보다 나중에 찍혀 있다

`api_estimated_arrival_at − label_arrival_at` (초, 양수 = 도착 이후):

| label_source | n | p05 | p50 | p95 | max |
|---|---|---|---|---|---|
| POSITION_STOP_FLAG_CORRECTED | 172,746 | 34 | **95** | 243 | 599 |
| ARRIVAL_API_ETA | 99,097 | 0 | 0 | 0 | 0 |

학습에 쓰는 `POSITION_STOP_FLAG_CORRECTED` 행에서 API ETA는 **항상 실제 도착 이후**이고 (최소 34초), 최대치가 정확히 599초에서 절단된다. 버스가 이미 도착한 뒤에야 확정되는 값이다.

`ARRIVAL_API_ETA` 행이 정확히 0인 것은 그 행들의 라벨 자체가 API ETA에서 파생됐기 때문이다(동어반복). 이 행들은 `corrected_target`이 null이라 학습 대상에서 빠진다.

### 2.3 예측 오차가 horizon과 무관하게 평평하다

전체 하루치(568만 행) API 오차를 도착까지 남은 시간 구간별로 분해했다.

| horizon | n | MAE |
|---|---|---|
| 1-5m | 609,687 | 112.72 |
| 5-10m | 822,363 | 113.39 |
| 10-20m | 1,534,920 | 114.61 |
| 20-40m | 2,688,716 | 116.14 |
| | **편차** | **3.42초** |

이것이 가장 직관적인 증거다. **어떤 실시간 예측 시스템도 40분 후와 2분 후를 같은 정확도로 맞출 수 없다.** 실제로 누수를 제거한 우리 모델은 43.8 → 66.5 → 103.7 → 145.4초로 정상적으로 악화된다. 오차가 평평하다는 것은 그 값이 앞을 내다본 결과가 아니라 **도착 그 자체에서 파생됐다**는 뜻이다.

### 2.4 왜 수집 방식과 무관하게 누수인가

설령 운영사 API가 실시간으로 ETA를 갱신해 발행했더라도 결론은 같다. **데이터셋은 도착 이벤트당 ETA를 하나만 저장한다.** 따라서 40분 전 스냅샷에 붙는 값은 "그 시점에 살아 있던 ETA"가 아니라 "그 도착에 최종적으로 결부된 ETA"다. 수집 시점 논쟁과 무관하게, 데이터셋 구성 자체가 미래 정보를 과거 행에 주입한다.

정량적으로 표현하면, 저장된 ETA는 실제 도착보다 평균 115초 늦으므로

```
api_target ≈ (해당 스냅샷의 실제 남은 시간) + 115초
```

즉 이 피처는 **모든 horizon에서 정답에 거의 상수를 더한 값**이다. 선형 모델조차 계수 1과 절편 −115만 학습하면 되므로, 아래 3절의 결과가 나오는 것이 당연하다.

## 3. 영향받는 수치

### 3.1 무효인 수치

| 수치 | 출처 | 실제 의미 |
|---|---|---|
| `baseline_api` MAE **110.88초** | `compare_56day.log` 등 전 실험 | 오라클. 도착 시각 + 상수 오프셋 |
| 잔차 학습 lightgbm **22.24초** | 2026-08-11 스모크 테스트 | 위 오라클의 상수 오프셋을 되돌린 것 |
| 잔차 학습 ridge **26.90초** | 동일 | 동일. 선형 모델이 이 값을 낸 것 자체가 신호였다 |

### 3.2 영향 없는 수치

`--with-api-feature`를 켜지 않은 모든 학습 결과는 유효하다.

| 실험 | 모델 | MAE |
|---|---|---|
| 56일, route_id 제거 | lightgbm | **138.51** |
| 56일, 전체 | catboost | 140.30 |
| 56일, 전체 | lightgbm | 144.83 |
| 56일, route-agnostic | lightgbm | 154.31 |
| 56일, 전체 | xgboost | 159.54 |

## 4. 정정된 기준선

`baseline_api`를 대체할 정직한 기준선이 필요하다. [train_advanced.py](../train_advanced.py)는 `naive_pace`를 사용한다 — 학습 기간의 정류장당 중앙값 소요시간에 남은 정류장 수를 곱한 비ML 추정값이다.

3일 소표본(테스트 = 2026-07-30) 기준 참고 수치:

| 모델 | MAE | 1-5m | 5-10m | 10-20m | 20-40m |
|---|---|---|---|---|---|
| naive_pace | 186.91 | 95.5 | 102.1 | 172.5 | 377.5 |
| ridge | 113.77 | 67.5 | 87.8 | 118.5 | 181.3 |
| lightgbm | 89.86 | 43.8 | 66.5 | 103.7 | 145.4 |

### 4.1 확정 수치 (2026-08-13, 전체 학습기간)

학습 `2026-06-12`~`2026-08-06`(48,298,611행, `--full-data-encoding`으로 전체 archive에 대해
target encoding 집계), 테스트 `2026-07-24`~`2026-07-30`(7,000,000행, seen 3.0M / unseen 4.0M)
기준 확정 수치:

| 모델 | MAE | RMSE | p90 | 1-5m | 5-10m | 10-20m | 20-40m | unseen route MAE |
|---|---|---|---|---|---|---|---|---|
| naive_pace | 179.78 | 253.26 | 409.23 | 105.0 | 108.3 | 169.2 | 336.4 | 183.8 |
| lightgbm (전체 spec) | 120.83 | 181.56 | 282.57 | 59.2 | 89.6 | 132.0 | 202.4 | 146.3 |
| model_a_alone (route-keyed dense) | 131.65 | 202.38 | 319.25 | 53.4 | 80.1 | 144.1 | 248.9 | 167.8 |
| **routed_lightgbm** | **119.37** | 178.60 | 278.86 | 59.2 | 88.7 | 130.5 | 199.0 | 146.3 |

`routed_lightgbm`은 단일 모델이 아니라 두 모델의 라우팅 조합이다([train_advanced.py](../train_advanced.py)
`--router-model-a`):

- **model A** — route-keyed dense aggregate(`advanced_2026-06-12_to_2026-08-06_corrected_20260812T020418Z/model_lightgbm.txt`)로 학습한 전체 spec 모델. 학습 시 봤던 route에서는 가장 정확하지만, route-keyed 인코딩에 이득의 79.8%가 몰려 있어 처음 보는 route에서는 22초가량 손해를 본다.
- **model B** — `--route-agnostic`. route-keyed 피처를 전부 빼고 section/stop 키만 남겨 route 간 전이가 되는 모델.
- 라우터는 route에 학습 이력이 있으면 A, 없으면 B를 쓴다. 그 결과가 3.2절 최선 수치(56일, route_id 제거, 138.51초)보다도 낮은 **119.37초**다 — 3.2절 수치는 이 라우팅 조합이 나오기 전 단일 모델 실험 결과였다.

이 수치가 배포 파이프라인(`train.py` + `ops/train_local.sh` + `gate.py`)의 실측치는 아니다. 배포 파이프라인은
아직 이 라우팅 조합을 쓰지 않는 단순 `train.py` 경로이므로, 8절 게이트 값은 여전히 3.2절의 단일 모델
수치(138.51초)를 기준으로 잡혀 있다. `train_advanced.py`의 라우팅 조합을 배포 파이프라인에 편입하면
게이트도 이 119.37초 기준으로 다시 낮춰야 한다.

## 5. 부수 발견 — `--target label`은 라벨 자체가 오염된다

같은 원인에서 파생된 별개의 문제다. 데이터셋 행의 35.3%는 **라벨이 API ETA에서 만들어진 것**이다.

`service_date=2026-07-30` 전체 8,742,822행 기준:

| label_source | 행 수 | `label_target` 비null | `corrected_target` 비null |
|---|---|---|---|
| POSITION_STOP_FLAG_CORRECTED | 5,655,686 | 5,655,686 | 5,655,686 |
| ARRIVAL_API_ETA | 3,087,136 | 3,087,136 | **0** |

`ARRIVAL_API_ETA` 행은 `label_arrival_at == api_estimated_arrival_at`이다(2.2절에서 편차가 정확히 0). 즉 이 행들의 "정답"은 관측된 도착이 아니라 **API의 추측**이다.

- **`--target corrected`** — `corrected_target`이 null이라 이 행들이 자동 제외된다. 학습 대상 5,655,686행 전부가 위치 stop_flag로 확인된 실측 도착이다. **안전하다.**
- **`--target label`** — `label_target`은 모든 행에서 비null이므로 필터가 걸리지 않는다. 학습 대상 8,742,822행 중 **3,087,136행(35.3%)의 라벨이 API 추측값**이 된다.

> **주의:** [train.py](../train.py)와 [train_compare.py](../train_compare.py)의 `--target` 기본값이 `label`이다. 옵션 없이 실행하면 오염된 라벨로 학습된다. 기존 56일 실험들은 `--target corrected`를 명시했으므로 영향받지 않았고, [train_advanced.py](../train_advanced.py)는 기본값을 `corrected`로 두었다.

`label` 타깃을 꼭 써야 한다면 `label_source == 'POSITION_STOP_FLAG_CORRECTED'` 필터를 반드시 함께 걸어야 한다.

## 6. 코드에 반영된 대응

- [check_api_eta_leakage.py](../check_api_eta_leakage.py) — 위 세 증거를 재현하고, 누수 신호가 있으면 exit 1을 반환한다. CI에 연결 가능하다.
- [train_advanced.py](../train_advanced.py) — `api_target_seconds_to_arrival`을 `LEAKAGE_COLUMNS`에 포함한다. `--api-oracle` 플래그로만 재도입할 수 있으며, 사용 시 경고를 출력하고 manifest에 `api_oracle: true`를 기록한다. 진단 목적 전용이다.
- [train_advanced.py](../train_advanced.py) `naive_pace_baseline` — `baseline_api`를 대신할 비ML 기준선.

## 7. 재현 방법

```bash
python check_api_eta_leakage.py 2026-07-30
```

다른 날짜에서도 동일한 패턴이 나오는지 확인하려면 날짜만 바꾸면 된다. 판정 임계값은 스크립트 상단의 `WITHIN_EVENT_STD_TOLERANCE`(기본 1.0초)와 `HORIZON_SPREAD_TOLERANCE_SECONDS`(기본 20초)에 있다.

## 8. 남은 확인 사항

- ~~**`ML_ABS_MAE` 게이트 값 재검토**~~ — 2026-08-13 갱신 완료. README.md·gate.py 예시를 `95` → `145`로 변경했다. 근거: 3.2절 정당한 56일 수치 중 최선(56일, route_id 제거, lightgbm) **138.51초**에 `ML_MAX_REGRESSION`과 동일한 1.05배 여유를 적용(138.51 × 1.05 ≈ 145.4, 반올림). 이 값은 현재 배포 파이프라인(`train.py`, 라우팅 미사용) 기준이다.
- ~~**라우팅 조합의 배포 편입 여부 결정**~~ — 2026-08-16 편입 완료. `train.py`에 `--fallback-model --target-encoding`을 추가해 `feature_engineering.py`(target-encoding 로직을 `train_advanced.py`에서 추출)로 servable-only spec subset(`SERVABLE_ENCODING_SPECS`, Java `MlFeatureVectorBuilder`가 이미 보내는 필드만 사용 — cross-repo 변경 불필요)을 학습·서빙 양쪽에서 재사용한다. 풀스케일(56일 윈도우) 확인 결과 routed `lightgbm` MAE **117.19초**(`lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z`) — Stage 0(2026-08-15, 5개 servable spec만, 117.67초)과 일치, 기존 138.51/138.53초 기준선 대비 ~15% 개선. `serve/app.py`는 `training_manifest.json`의 `target_encoding_enabled`을 읽어 model A의 route_id 카테고리 요구를 해제하고, 저장된 `encoding_tables/*.parquet` + `route_profile.parquet`로 요청의 raw 필드에서 `te_*`/파생 피처를 재계산한다(`feature_engineering.compute_serving_features`) — 오프라인 test-split 예측과 정확히 일치함을 `serve/test_app.py`로 검증했다. `ops/deploy_model.sh`는 실험 디렉터리 전체를 rsync하므로 `encoding_tables/`도 별도 수정 없이 함께 배포된다. `ML_ABS_MAE`는 117.19 × 1.05 ≈ **123**으로 낮췄다(README.md, `gate.py`). 프로덕션에서 이 경로를 켜려면 `ML_ROUTER_FALLBACK=true ML_TARGET_ENCODING=true`가 함께 필요하다(target-encoding 단독 모델 A는 141.00초로 plain 138.53초보다 나쁨 — 라우팅과 함께일 때만 이득).
- **수집 계층 확인** — 본 문서는 데이터셋 산출물만으로 결론을 내렸다. Spring 수집 코드에서 `api_estimated_arrival_at`이 정확히 어느 시점에 기록되는지 확인하면 서술을 더 정확히 할 수 있다. 다만 2.4절에 정리했듯 **결론은 수집 방식과 무관하게 성립한다.**
