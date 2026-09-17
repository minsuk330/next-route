# 검색 응답 노선 형상 배선 + 지하철 형상 생성

## 뭘 하나
검색 응답의 `subPaths[].polyline` 자리를 OTP가 들고 있는 실제 형상으로 채운다(버스·도보 먼저).
지하철은 형상 자체가 없으므로 pfaedle을 지하철 모드로 한 번 더 돌려 만들고, 그래프를 재배포하면 같은 배선으로 자동 합류한다.

## 왜
P3로 프로덕션 그래프에 버스 형상은 들어갔는데, 그걸 앱으로 내보내는 자리가 끊겨 있어 지금 응답의 polyline이 버스·지하철·도보 전부 null이다.

## 핵심 결정
- **응답 스키마 안 바꾼다** — `polyline` 필드는 ODSAY 시절부터 계약에 있다. 값만 채우면 클라 변경 0.
- **검증된 형상만 채운다** — `SHAPE_OK`일 때만 주입, `STREET_FALLBACK`·2점 미만은 null. 디코더는 예외를 안 던진다(형상 결함이 검색 500으로 번지면 안 됨).
- **지하철 = pfaedle Stage2, 1차는 subway만** — OSM에 선형 실재 확인(Overpass, way 1,928개). 게이트 스크립트의 `coveredModes=["bus"]` 하드코딩부터 제거(안 하면 형상 만들고도 계속 null). rail 2개 노선은 분포 보고 판단. ODSAY 캐시 재활용은 기각(27노선뿐).
- **도보는 OTP 경로 사용** — TMAP enricher 되살리기 기각(OTP가 이미 OSM 보행 실경로 계산).
- **버스·도보 먼저 배포** — 지하철을 안 기다린다. 배선이 `coveredModes`를 보므로 그래프 배포만으로 코드 변경 없이 합류.

## 건드는 곳
`LegacyRouteResponseMapper`, 신규 `EncodedPolyline`, `compute_shape_quality.py`, `build_seoul_graph.sh`, `shape-gate-thresholds.json`, `release-manifest.json`

## 리스크
- **Redis 압박** — 검색 결과는 스냅샷으로 600초 상주하는데 프로덕션 Redis는 128MB/allkeys-lru고 실시간 버스 캐시와 공유한다. 부풀면 남의 키가 밀려난다 → 상주량 32MB 넘으면 그 PR에서 대응.
- 지하철 rail 매칭 품질 미측 — 역 좌표 기준 차이로 잔차가 클 수 있다. 분포 측정 전 임계 확정 금지.
- Stage2 배포 시 **배포본 OTP 이미지로 빌드하지 않으면 그래프 로드가 거부된다**(serialization id 249 vs 276).

## 검증법
계약 테스트 5종(SHAPE_OK→채움 / FALLBACK→null / 도보→채움 / 좌표순서 / malformed→예외 없이 null) 통과 + 배포 후 VPS 실검색에서 polyline non-null·점 개수 > 정류장 수 + Redis `used_memory` 가드 아래. 지도 실표시는 모바일 HANDOFF.
