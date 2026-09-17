# OTP 대중교통 형상 (pfaedle)

## 뭘 하나
pfaedle로 OSM 도로에 정류장을 map-matching해 `shapes.txt`를 만들고 OTP graph에 넣어, 대중교통 leg가 정류장 직선 대신 진짜 노선 형상으로 나오게 한다. 검증 통과한 버스 trip만 형상 표시, 나머지는 정직하게 직선.

## 왜
KTDB GTFS에 형상이 없어 OTP가 건물 관통 직선을 그린다(intent 금지). OSM route relation은 커버리지 부재로 폐기, pfaedle이 VPS 기존 line과 4~6m 일치·전노선 커버로 검증됨.

## 핵심 결정
- **per-trip 사전게이트** (route-level·config-블랜킷 기각): pfaedle 자체 지표(잔차/단조성/앵커/우회비)로 trip 판정, 통과분만 SHAPE_OK.
- **coverage-index로 provenance 판정**: OTP는 shape 없어도 직선 points를 주므로 "points 존재=형상" 금지. 인덱스에 없는 trip·mismatch=fallback.
- **버스만 Stage1**: 지하철/철도/ferry는 측정 전까지 STREET_FALLBACK 유지.
- **VPS 비교=리포트만** (oracle 기각): 자동 정답판정 안 함, degraded는 pfaedle 지표로만.
- **원자 릴리즈**: graph↔graphVersion↔index 해시 묶음(graphVersion drift 방어).

## 건드는 곳
tools: build_seoul_graph.sh, compute_shape_quality.py, compare_pfaedle_vps.py, seoul-bbox GTFS/graph 재빌드, release-manifest. src: GeometrySource/GeometryQuality(enum), ShapeCoverageIndex, OtpRoutingProperties, OtpPlanConnectionMapper.

## 리스크
- 게이트 임계값 미확정(전량 분포 후 결정) — degraded 비율이 크면 형상 커버 낮아짐.
- 지하철/철도 rail 매칭 품질 미측 → Stage1 제외로 회피.
- graphVersion drift(앱 config vs 라이브 graph) — 원자 번들+기동검증으로 완화, 완전해결은 P2-C 미결 연계.

## 검증법
pfaedle exit0 + 게이트 통과율 분포 + planConnection 버스 leg 형상 직선 아님 육안. `./gradlew compileJava compileTestJava` + 계약 테스트 4종(covered=SHAPE_OK/degraded=fallback/mismatch=fallback/비버스=fallback). 배포는 shadow dormant 유지(회귀0).
