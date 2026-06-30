# 캘린더/날씨 Feature 추가 계획

ML 버스 도착예측 파이프라인에 캘린더 feature와 날씨 feature를 추가한다.
학습(`build_dataset.py`/`train.py`)과 서빙(`MlFeatureVectorBuilder`)이 동일한
feature 이름·단위·시간 기준을 쓰도록 contract를 고정하고, 날씨는 작은 sidecar
parquet으로 관리해 저장 공간 부담을 줄인다.

---

## 1. 현재 구조 (확인 결과)

Contract chain — 한 곳이라도 어긋나면 train/serve skew 또는 서빙 거부:

- **`ml/build_dataset.py`** — `dataset_columns()`가 dataset parquet 컬럼 정의.
  캘린더 시간 feature(`hour_of_day`, `minute_of_day`, `day_of_week`, `is_weekend`)는
  `snapshot_at`(차량 `data_tm`을 KST naive datetime으로 파싱)에서 파생. `SCHEMA_VERSION = 1`.
- **`ml/train.py`** — `NUMERIC_FEATURES` + `CATEGORICAL_FEATURES = ["route_id"]`가
  실제 학습 feature. manifest `feature_list` + `schema_version: 1`(하드코딩) 기록.
  LightGBM은 NaN 네이티브 처리.
- **`ml/serve/app.py`** — `_validate_contract`가 모델 거부 조건:
  - `feature_list != booster.feature_name()` (순서까지 일치 요구)
  - `schema_version != EXPECTED_SCHEMA_VERSION (=1)`
  - forbidden `api_target_seconds_to_arrival` 포함
  - `/predict`는 item에 required feature 누락 시 **422** 반환.
  - **item의 extra feature는 무시**(`state.feature_names`만 읽음).
  - `_build_frame`이 None → `math.nan` 매핑.
- **`MlFeatureVectorBuilder.java`** — `BusPositionInfo` + `targetSeq` → feature map.
  시간 feature는 `snapshotAt`(dataTm) KST 기준. Python 파생과 정확히 동일해야 함.
- **`ml/ops/retrain.sh`** — restore(Drive) → `build_dataset` per date → holdout →
  누적 train → gate → symlink swap → serve restart.
- **`ml/split.py`** — dataset 컬럼을 그대로 통과. `to_pandas`가 `[*columns, target]` select.
  → 새 feature 컬럼은 dataset parquet에 존재해야 train이 select 가능.

### 발견 사항 3가지

1. **기존 skew 버그**: dataset `minute_of_day = snapshot_at.dt.minute()` (0–59),
   Java `MlFeatureVectorBuilder:35`는 `hour*60+minute` (0–1439). 이미 불일치.
   feature 분포가 바뀌므로 재학습이 필요한 PR D에서 함께 수정 권장.
2. **Spring 날씨 API 미발견**: 코드 grep 결과 false positive(`train`/`snow` 부분일치)뿐.
   전제 "Spring에 날씨 API 이미 연결됨"은 **PR C 착수 전 실존 검증 필수**.
   현재 `WeatherClient`/`WeatherPort` 없음.
2b. **휴일 인프라 기존 존재**: Spring `HolidayCalendar`/`GongApiAdapter`가 공공데이터
   getRestDeInfo로 휴일 조회(연 캐시 + `KoreanPublicHolidayCalendar` 폴백). → 손타이핑 금지.
   PR A는 Python이 **같은 gov API**로 sidecar 생성, PR C는 기존 `GongApiAdapter` 재사용.
3. **안전 롤아웃 레버**: 구 모델에 extra feature를 보내도 무시되고, dataset에 컬럼을
   추가하되 `NUMERIC_FEATURES`에 안 넣으면 `feature_list`/`schema_version` 불변.
   → PR A/B는 서빙 무영향으로 dataset 컬럼 적재, PR C는 새 모델 전에 미리 송신 가능.
   계약 전환은 PR D에서만 발생.

---

## 2. Implementation Plan

| 단계 | 작업 항목 | 수정/추가 파일 | 구현 내용 | 학습 경로 영향 | 서빙 경로 영향 | 저장 공간 영향 | 테스트/검증 | 리스크 & 대응 |
|---|---|---|---|---|---|---|---|---|
| **PR A** | 휴일 sidecar 생성기 | `ml/holiday_fetch.py` (신규) | **권위 소스 = 공공데이터 getRestDeInfo**(한국천문연구원, Java `GongApiAdapter`와 동일 endpoint). stdlib `urllib`+`xml.etree`(신규 의존 0)로 연도별 fetch → `$DATA_DIR/calendar/holidays.parquet`(`holiday_date` Date 1컬럼) 멱등 머지. `GONG_API` 키 필요(ML 컨테이너 env) | 학습 입력 아님(sidecar) | 없음(서빙은 기존 `GongApiAdapter` 재사용) | sidecar 수 KB(전 연도, 영속 볼륨) | API mock 없이도 파싱/머지 단위테스트, `GONG_API` 미설정 시 명확 에러 | API 실패→이전 sidecar 유지 best-effort. 손타이핑/하드코딩 대신 Java와 동일 gov API라 skew 차단 |
| **PR A** | 캘린더 feature dataset 생성 | `build_dataset.py` (`load_holidays`, `calendar_columns`, `dataset_for_route`, `dataset_columns`), `ml/test_build_dataset.py` | sidecar(`calendar/holidays.parquet`) 로드 → `snapshot_at` KST date 기준 `is_holiday/is_pre_holiday/is_post_holiday`(sidecar 없으면 null), hour 기준 `is_commute_morning[7,9)/is_commute_evening[18,20)/is_late_night(≥23 또는 <5)`. 경계 상수=모듈 상단 단일 정의. 전부 Int8 0/1 | dataset parquet 6컬럼 추가. **`NUMERIC_FEATURES` 미변경 → `feature_list`/`schema_version` 불변** | 없음 | dataset/일 6 Int8 col, 무시 수준 | 합성 frame 골든테스트(휴일 전후/출퇴근 경계/null), 전체 35 테스트 회귀 확인 | sidecar 없는 날 → holiday 3개 null(commute/late는 항상 계산). 경계 상수+주석으로 Java와 1:1 |
| **PR A** | retrain에 holiday fetch | `ml/ops/retrain.sh`, `compose.yaml`(`GONG_API`), `ml/.env.example` | build_dataset **앞**(step 3.5)에 `holiday_fetch.py --from-year..--to-year` best-effort 삽입. ML 컨테이너에 `GONG_API` env | 주간 retrain이 휴일 sidecar 갱신 | 없음 | sidecar 영속(수 KB) | retrain dry-run | API 실패→`|| log` 비치명, 이전 sidecar 유지 |
| **PR B** | 날씨 sidecar 생성기 | `ml/weather_fetch.py` (신규) | 과거 날씨 API → `/data/weather/service_date=YYYY-MM-DD/data.parquet`. 키=`weather_hour`(KST hour truncate). 단일 지역(도시 1점) 시작. 컬럼=raw(temp_c, precip_mm, humidity_pct, wind_mps)+condition code | 학습 입력 아님(sidecar) | 없음 | sidecar ~24행/일 = **일 수십 KB** | API 응답→parquet 스키마 검증, 빈 시각 행 누락 허용 | API 실패/누락→해당 시각 행 없음(join 시 null). 부분 실패 허용, 비치명 |
| **PR B** | dataset ↔ sidecar join | `build_dataset.py` (weather_hour 파생 + left join + `dataset_columns`) | `weather_hour = snapshot_at.dt.truncate("1h")`. sidecar 있으면 `scan_parquet` 후 `join(on="weather_hour", how="left")`, 없으면 컬럼 null. `is_raining/is_snowing/weather_severity`는 **결정적 파생함수**로 build 시 계산 | dataset 7 weather 컬럼 추가. **여전히 `NUMERIC_FEATURES` 미변경 → 스키마 불변** | 없음 | dataset/일 7컬럼(대부분 float), 일 수백 KB | sidecar 없는 날 null 채움+빌드 성공 테스트. join 카디널리티(1행:1시각) 가드 | sidecar 없는 날 build 실패 금지 → **left join + null-fill 강제**. severity 공식 spec 문서화(Java 공유) |
| **PR B** | sidecar archive/restore | `ml/ops/upload_archive.sh`, `retrain.sh`(restore) | sidecar Drive `weather/`에 업로드. retrain restore에 weather 추가(best-effort, 필수 아님). 누락 시 `weather_fetch.py`로 갭 채움 | 없음 | Drive tiny 추가 | 미미 | restore 후 partition 존재 확인 | sidecar 누락=null 학습 허용 → **비치명 처리**(`|| true`) |
| **PR C** | Spring 날씨 조회 포트 | `WeatherPort`(out) + adapter, `WeatherFeatureProvider` (신규) | snapshotAt(dataTm) hour 기준 같은 단위로 조회. **차량 후보 batch 예측 직전에 snapshotHour 단위로 1회 조회 + cache** → `WeatherFeatures` 값 객체 반환. 절대 builder 내부에서 차량별 호출 금지. 실패→null. **단위/시각/severity 공식 = PR B와 동일** | 없음 | 새 모델 전이라 **무시**(extra). 새 모델 후 사용 | 없음 | 단위 변환·severity 파생 단위테스트, API mock, **호출 횟수=고유 snapshotHour 수(차량 수 아님) 검증** | builder 내부 호출 시 차량 수만큼 API 폭주 → **batch 1회 조회 후 주입 구조 강제**. historical(train) vs live(serve) 불일치 → 같은 관측 endpoint·시각 버킷 |
| **PR C** | feature map 확장 | `MlFeatureVectorBuilder.java` | calendar 6 + weather 7 키 put. **holiday=기존 `HolidayCalendar`(`GongApiAdapter`) 재사용**(Python sidecar와 동일 gov API). commute/late 경계 상수는 build_dataset.py와 1:1 복제. **weather는 미리 조회된 `WeatherFeatures`를 인자로 주입받아 put**(builder는 I/O 없음). 시각 base=`snap`(KST) 동일 | 없음 | 구 모델 extra 무시(안전). **PR C를 PR D보다 먼저 배포** | 없음 | builder 단위테스트로 13키·값 일치(Python 골든 대조), 주입된 weather null 시 키=null 확인 | 키 누락 시 새 모델에서 422 → PR C 선배포 순서 강제. builder가 I/O 가지면 batch 폭주 → 순수 매핑 유지 |
| **PR D** | 학습 feature 활성화 | `train.py`(`NUMERIC_FEATURES`+13, **manifest `schema_version` 1→2**), `build_dataset.py`(`SCHEMA_VERSION=2`), `serve/app.py`(`EXPECTED_SCHEMA_VERSION=2`) | calendar+weather를 학습 feature로 승격. schema 1→2를 **build/train manifest/serve 3곳** 동시 bump(train.py는 현재 `"schema_version": 1` 하드코딩 → 안 올리면 새 모델 manifest=1이라 serve 로드 거부). (옵션) `minute_of_day` skew 동시 수정 | `feature_list` 확장, 재학습 필수 | serve v1 거부/v2 수락. **구 Java+신 모델 호환 단계 종료** | dataset 동일, 모델 약간 ↑ | 골든 vector(Java)↔predict 입력 일치 E2E, gate 통과 | **조정 배포**: serve(v2)+모델 동시. PR C 선행 안 하면 422. 롤백=symlink 구모델+serve v1 |
| **PR D** | validate null gate 분리 | `ml/validate.py` | weather 7컬럼을 **`REQUIRED_FEATURES`에 넣지 않음**(`validate.py:309-315`는 REQUIRED null_rate>1%를 error). 별도 `NULLABLE_FEATURES`로 분리해 coverage report/warning만 출력 | 없음 | 없음 | 없음 | sidecar 누락일 dataset으로 validate가 통과(warning만)하는지 테스트 | weather를 무심코 required에 넣으면 sidecar 누락일 validate/retrain 영구 차단 → **nullable 분리 + warning 강제** |
| **PR D** | retrain 통합 | `ml/ops/retrain.sh` | weather restore/fetch를 build_dataset **앞**(step ~2.5)에 삽입. dataset ephemeral 유지, sidecar keep | 주간 누적재학습이 새 feature 사용 | 자동 배포+health/coverage 게이트 | sidecar keep(작음), dataset 삭제 유지 | 1일 dry-run으로 build→train→gate 통과 | gate fail=구 모델 유지(기존 semantics). disk gate에 sidecar 영향 미미 |

---

## 3. Feature Contract (train ↔ serve 단일 spec)

시각 base 전부 `snapshot_at`/`dataTm` **KST**. `weather_hour` = 그 시각 hour truncate.

| feature | 단위/타입 | 파생 (양쪽 동일) | null 정책 |
|---|---|---|---|
| `is_holiday` | 0/1 | `snapshot_at` KST date ∈ kr_holidays | 항상 채움 |
| `is_pre_holiday` | 0/1 | 다음날이 holiday | 항상 |
| `is_post_holiday` | 0/1 | 전날이 holiday | 항상 |
| `is_commute_morning` | 0/1 | hour ∈ [7,9) | 항상 |
| `is_commute_evening` | 0/1 | hour ∈ [18,20) | 항상 |
| `is_late_night` | 0/1 | hour≥23 또는 hour<5 | 항상 |
| `temperature_c` | float °C | sidecar/API raw | 누락 null→NaN |
| `precipitation_mm` | float mm/h | raw | null→NaN |
| `humidity_pct` | float % | raw | null→NaN |
| `wind_speed_mps` | float m/s | raw | null→NaN |
| `is_raining` | 0/1 | `precipitation_mm > 0` | 누락 null |
| `is_snowing` | 0/1 | condition=눈 또는 강수+기온≤0 (spec 고정) | null |
| `weather_severity` | 0..N ordinal | precip/snow/wind 버킷 결정함수 | null |

> `is_raining`/`is_snowing`/`weather_severity`는 sidecar에 raw만 저장하고
> **결정적 파생함수**로 계산한다. 그 함수를 build_dataset.py와 Java가 동일 상수로 복제하며
> 본 문서가 유일한 spec. 가급적 단순 규칙으로 유지해 skew 위험 최소화.
>
> **weather feature는 전부 nullable.** sidecar 누락/API 실패일에도 학습·서빙·검증이
> 막히면 안 됨. `validate.py`의 REQUIRED null gate(>1% error)에 넣지 말고 별도
> coverage warning으로만 다룬다(§2 PR D validate 행 참조). LightGBM은 NaN 네이티브 처리.

---

## 4. schema_version / 호환성

- **PR A·B·C**: `feature_list` 불변 → `schema_version = 1` 유지 → 기존 모델 그대로 servable. 무중단.
- **PR D**: `schema_version 1→2`를 build/train/serve 3곳 동시 bump. serve가 버전 교차 거부 →
  v2 serve + v2 모델 **원자 배포**. 롤백 = symlink 구모델 + serve 이미지 v1.
- 구 Java가 신 모델보다 늦으면 422 → **PR C 선배포 순서 불변**.

---

## 5. 추천 PR 분할 (안전 순서)

1. **PR A — calendar**: `ml/holiday_fetch.py`(getRestDeInfo→sidecar) + `build_dataset.py`
   캘린더 6컬럼 + retrain.sh holiday 단계 + `GONG_API` env. 스키마 불변, 서빙 무영향.
   휴일 권위=Java와 동일 gov API. 저위험 선착.
2. **PR B — weather sidecar + join**: `weather_fetch.py` + sidecar + left-join 7컬럼
   + archive/restore. 여전히 스키마 불변.
3. **PR C — Spring serving**: `WeatherPort`/provider + `MlFeatureVectorBuilder` 13키.
   **전제(기존 날씨 API) 검증 먼저.** 구 모델이 extra 무시 → 단독 배포 안전.
4. **PR D — activate + retrain**: `NUMERIC_FEATURES`+13, `SCHEMA_VERSION→2`,
   `EXPECTED_SCHEMA_VERSION→2`, retrain.sh에 weather 단계. serve+모델 조정 배포.
   (옵션 `minute_of_day` skew 동시 수정.)

순서 핵심: dataset 컬럼(A,B) → 서빙 송신(C) → 계약 전환(D).
각 단계 독립 mergeable, D만 조정 배포.

---

## 6. 착수 전 확인 (배포 전제)

- VPS `compose.yaml`: weather sidecar 마운트 경로(`/data/weather`)·serve 이미지 태깅 확인.
- Spring 날씨 API 실존 여부 검증(현재 코드에서 미발견).
