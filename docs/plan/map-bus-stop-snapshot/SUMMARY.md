# 지도 버스정류장 스냅샷 API

## 뭘 하나

서울 전체 버스정류장(12,986개)을 한 번에 내려주는 읽기 전용 엔드포인트
`GET /api/map/bus-stops/snapshot` 을 새로 만든다. ETag 로 재검증해서 안 바뀌면 304 만 준다.
앱은 시작할 때 1회 받아 로컬에 두고, 지도 팬/줌에는 서버를 부르지 않는다.

## 왜

지금은 화면 이동마다 `nearby` API(가까운 20개, 밀도에 따라 반경 500m~3km 변동)를 불러 마커를
전체 교체한다. 그래서 팬할 때마다 아까 있던 마커가 사라져 끊겨 보인다.

## 핵심 결정

- **정적 데이터는 통째로 준다** — 서울만 12,986행, gzip 후 300kB 미만. 팬마다 왕복할 이유가 없음
- **벡터 타일(MVT) 기각** — 네이버 지도 SDK 가 외부 벡터 타일 소스를 못 받음
- **bbox·타일 캐시 엔드포인트 기각** — 스냅샷이 성립하는 크기. 전국으로 넓히면 그때 승격
- **`version = "<정류장 수>-<최종수정 epoch>"`** — 프로브 쿼리 하나로 계산, 304 때 본문을 안 만드는 게 핵심
- **응답은 JSON array-of-arrays + 직렬화 바이트 캐시** — 키 반복 제거로 크기 절감, 요청마다 1.3만 행 재직렬화 안 함. CSV 는 정류장명 이스케이프 때문에 기각
- **기존 `/api/nearby/bus-stops` 는 안 건드림** — 검색/resolve 용도로 유지, 지도용과 분리

## 건드는 곳

`BusStopRepository`, `BusDataService`, 신규 `application/map/**`,
신규 `infrastructure/adapter/in/api/map/MapBusStopController`, `application.yaml`(gzip),
테스트 3개, `docs/plan/map-bus-stop-snapshot/HANDOFF.md`

## 리스크

- **gzip 전역 활성화** — 2KB 넘는 모든 JSON 응답이 압축된다 (경로검색 등 포함). 이득이지만 의도된 side effect
- **끊김의 나머지 절반은 클라 몫** — 마커 `stopId` upsert + 줌 게이팅/클러스터링이 없으면 여전히 깜빡이거나 1.3만 마커로 프레임이 죽는다. HANDOFF.md 로 인계
- **소프트삭제 필터 불일치** — 스냅샷은 `deleted_at IS NULL`, 기존 `findNearby` 는 `geom IS NOT NULL`. 현재 프로덕션은 결과가 같지만(삭제 0건) 계약이 다름을 문서화

## 검증법

`./gradlew compileJava compileTestJava` + 신규 테스트 3개(컨트롤러 304/200, 서비스 캐시·버전·좌표절삭,
리포지토리 필터 IT) green. 로컬 기동 후 curl 로 200 응답의 gzip `Content-Length` 400kB 이하 확인,
같은 ETag 재요청이 304 이고 그때 정류장 조회 쿼리가 안 나가는 것 확인. 마이그레이션 없음(VPS 수동배치 불필요).
