# 전국 OTP 그래프 대중교통 형상(pfaedle) — 요약

## 뭘 하나
P3/P4에서 수도권 bbox 피드에만 돌린 pfaedle 형상 파이프라인을 전국(제주 제외) 피드로 넓혀, 전국 형상 그래프 + 전국 coverage-index를 한 번에 배포한다.

## 왜
2026-09-29 운영 OTP를 전국 그래프(`national-nojeju-20260929-otp278-h-only-9dbfb229`)로 교체했는데 이 그래프의 GTFS에는 `shapes.txt`가 없다. 그런데 api는 아직 수도권 shaped graphVersion + coverage-index로 형상 마킹 ACTIVE 상태라, 전국 모든 버스·지하철이 **정류장 직선을 SHAPE_OK로** 내보내고 있다(실측: legGeometry 점 수 = 정류장 수). 경기 bbox 밖·지방은 형상 자체가 없고, 신분당선이 깨져 보이는 것도 같은 원인(전국 그래프에 형상 없음)이다.

## 핵심 결정
- **현 상태 유지(2026-09-30 결정)** — 사용자가 없어 전국 형상 배포 전까지 정류장 직선 노출을 감수한다. api 형상 설정은 Phase 6 배포 때 한 번에 바꾼다.
- **파이프라인 재사용, 범위만 확장** — `build_seoul_graph.sh`를 프로파일(`seoul`/`national`) 인자로 일반화. 수도권 빌드 재현성은 그대로 유지.
- **전국 GTFS는 운영 피드 그대로** — 09-29 피드(calendar ~11-30)를 입력으로 고정. 재현 가능한 재생성은 다음 GTFS 갱신 때.
- **전국 그래프 빌드는 호스트 JVM** — 빌드 힙 11.6GiB라 Docker VM(7.75GB) 불가. 배포 이미지와 같은 jar를 sha·ser id 검증 후 사용.
- **bus → subway 순서로 pfaedle, rail·ferry는 제외 유지** — 일반/고속철도는 검색 노출 대상 밖(national-otp-map-migration PLAN). STREET_FALLBACK.
- **임계값은 전국 분포를 보고 다시 정한다** — 수도권 값 재사용 금지. 지역별(시도) 분포도 같이 본다(지방 OSM 품질 편차).
- **배포는 번들 원자성** — graph.obj + coverage-index + api graphVersion을 release-manifest로 묶어 한 번에 바꾼다.

## 건드는 곳
`runner/build_seoul_graph.sh`(→ `build_otp_graph.sh` 프로파일화), `runner/compute_shape_quality.py`(지역별 리포트), `config/otp-national/*`(thresholds 신규), `data/otp-national/release-manifest.json`, VPS `compose.otp-national.yaml`·api env, 저장소 `compose.yaml` 운영값 동기화

## 리스크
- pfaedle 전국 실행 시간·메모리 미측정(수도권 bus 14.7만 trip ≈ 2분, amd64 에뮬레이션). 전국 bus 30.7만 trip
- 지방 OSM 선로·도로 누락 → degraded 비율이 수도권(1%)보다 클 수 있음. 비율 자체를 결정 입력으로 쓴다
- 형상 추가로 힙 live set 증가(현재 Full GC 후 2.45GiB, Xmx3500m). 재측정 전 배포 금지
- api는 OTP가 실제로 어떤 그래프를 띄웠는지 모른다 → 이번 사고 원인. 배포 절차로 막고, 런타임 확인은 후속

## 검증법
게이트 분포 리포트(모드·지역별) → 임계 확정 → 그래프 빌드 → 로컬 로드 + 지역별 fixture(서울·경기 외곽·부산·대구·대전·광주·신분당선)에서 형상 점 수 > 정류장 수 → VPS 동시 4건 RSS·GC 확인 → api polyline non-null(SHAPE_OK)·null(degraded) 대조.
