# P7 — OTP 기본 전환 + ODsay 폴리라인 스택 제거

## 뭘 하나
앱 코드의 기본 길찾기 공급자를 ODsay → OTP 로 뒤집는다(지금은 VPS 환경변수로만 OTP).
그리고 쓰이지 않게 된 ODsay 지하철 폴리라인 캐시 한 벌(서비스·잡·스케줄러·관리 API·테이블 3개)을 걷어낸다.

## 왜
프로덕션은 3일째 OTP 로 도는데 코드 기본값은 아직 ODsay 다 — 환경변수가 빠지면 조용히 ODsay 로 되돌아간다.

## 핵심 결정
- **ODsay 검색 분기는 남긴다** — 비상 롤백 통로. 지우는 건 폴리라인 스택뿐
- **폴리라인은 이미 대체됐다** — P3/P4 의 OTP 형상이 그 자리를 채운다. 롤백 시 지하철 형상이 null 이 되는 건 수용(원래 null 이던 필드)
- **테이블은 새 마이그레이션(V55)으로 지운다** — 배포된 V4 는 손대지 않음. FK·참조 없음 확인
- **비용 때문에 막아둔 값들은 건드리지 않는다** — P2-F 에서 이미 해제·재산정 완료
- PR 2개로 나눈다 — 기본값 전환과 코드 삭제를 섞으면 문제 생겼을 때 되돌릴 자리가 안 보인다

## 건드는 곳
`RoutingProviderProperties`, `application.yaml`, `RouteSearchService`, `SearchComponent`, `OdSayApiPort`/`OdSayApiAdapter`, `domain/route/polyline/*`, `RoutePolylineEnricher` 외 폴리라인 서비스 4종, 폴리라인 관리 컨트롤러·스케줄러, `V55__drop_odsay_route_polyline.sql`

## 리스크
- 기본값을 뒤집으면 **암묵적으로 ODsay 를 가정하던 기존 테스트가 깨진다** → 해당 테스트는 ODsay 를 명시하도록 고친다
- 검색 응답 meta 의 degraded 목록에서 `polyline` 값이 영구히 사라진다(이미 안 나오던 값)
- V55 는 VPS 수동배치 대상. **V53·V54 가 아직 미배포**라 순서 확인 필요

## 검증법
컴파일 + 전체 테스트 green, ODsay 분기가 폴리라인 없이도 응답하는 회귀 가드 1개 통과.
머지 후 `handoffs/PHASE-7.md` 에 제거/보존 목록과 배포 상태(코드 완료 vs 운영 전환) 기록.
