# Phase 1 — 수도권 GTFS 변환과 OTP graph

## 작업 계약

먼저 [AGENT-GUIDE.md](AGENT-GUIDE.md)를 읽는다.
선행: P0 handoff와 data-scope.json. 목표: 202503 입력에서 재생성 가능한 수도권 graph를 만들고 실제 query까지 연결한다.
수정 범위: `tools/transit-routing-benchmark/runner/prepare_gtfs.py`, `validate_gtfs.py`, 필요한 build/serve 설정·스크립트. 실제 파일과 CLI를 먼저 확인한다.

## P1-A — 범위 필터와 정규화

- [ ] 하드코딩 prefix를 scope 설정으로 치환한다. bbox 안의 검증된 수도권 버스·도시철도 trip만 승인한다.
- [ ] trip의 서비스 정류장 중 범위 밖 정류장이 있으면 해당 trip 전체를 제외한다. stop_times 일부를 삭제하거나 순번을 이어 붙여 새 운행을 만들지 않는다. 제외 건수와 노선 coverage 감소를 보고한다.
- [ ] 순환·왕복·동명 노선·다른 도시 철도를 실제 좌표/agency/pattern으로 확인한다. RR_ACC1 접두사만으로 수도권 판정하지 않는다.
- [ ] 원천 route/stop/trip ID는 문자열과 namespace를 보존한다. dialect가 KTDB일 때만 KTDB route_type 변환을 적용한다.
- [ ] trips→routes/agency, stop_times→stops, calendar/service, parent station, transfers 등 참조 폐쇄를 유지한다.
- [ ] calendar_dates 단독 구성, 24시 초과 시각, frequencies, 승하차 제한을 보존한다. 알 수 없는 필드·미지원 파일은 보고한다.
- [ ] 원본 `calendar.txt`의 `20170101~20301231` 범위는 원천 보존용으로 유지한다. OTP graph에는 `transitServiceStart`/`transitServiceEnd`로 실제 운영 창만 materialize한다. 기준값은 build 시점부터 `-P1D`~`P2M`으로 시작하고, graph 재생성 주기보다 길게 잡지 않는다.
- [ ] `calendar_dates.txt`가 없고 날짜별 시간표 변화가 확인되지 않은 경우에만 위 운영 창 축소를 허용한다. 예외일·서비스 변경이 있으면 calendar_dates를 반영한 뒤 창을 계산한다.
- [ ] 현재 스트리밍/SQLite 구현을 확인해 재사용한다. 전체 stop_times를 객체 목록으로 새로 적재하지 않는다.
- [ ] 원천은 보존하고 새 산출물 디렉터리에 생성한다. 실패한 산출물은 serving 대상으로 publish하지 않는다.

## P1-B — build와 load

- [ ] manifest에 입력 hash, scope, normalizer commit/config, OTP image digest, graphVersion을 넣는다.
- [ ] 기존 bbox PBF를 사용해 기본 graph를 만든다. 승인된 GTFS와 다른 도시 데이터가 혼합되지 않았는지 건수를 확인한다.
- [ ] build와 serve 명령을 분리한다. 내부 endpoint에서 graph load 후 GraphQL 실제 query를 수행한다.
- [ ] 서버 schema에 맞는 query와 응답 fixture를 저장한다. 기존 OTP 2.9.0 planConnection 사용은 실행 이미지에서 확인한다.
- [ ] graphVersion을 배포 manifest와 연결하고 hash/load/query 실패 시 기존 artifact를 유지한다.
- [ ] Phase 3가 OSM relation shape를 추가한 GTFS로 같은 명령을 재실행할 수 있도록 입력 경로를 인자로 받는다.

이 단계 graph에 transit shape가 없을 수 있다. 검색/adapter용 기본 산출물이며 형상 품질·네비 완료로 선언하지 않는다.
새 graph의 build/load 로그와 자원 사용을 남기되, 기존 전국/동시 부하 시험은 반복하지 않는다.

## 필수 검증

| 입력/행동 | 기대 |
|---|---|
| 경계 넘는 trip, 다른 도시 철도 | trip 제외 + OUT_OF_SCOPE 사유, 가짜 단축 운행 없음 |
| 25시 운행·휴일 예외·parent/transfer | 시각과 참조 보존 |
| 참조 끊김·중복 순번·시간 역행 | 검증 실패/명시적 제외 |
| 같은 입력 재변환 | 내용 식별/hash 재현; 생성시각 metadata는 별도 |
| 손상 graph·hash 불일치 | serving 전환 차단 |
| P0 대표 OD와 범위 밖 요청용 fixture | 실제 검색 결과 기록, 범위 밖 API 차단 입력은 P2에 전달 |

## 완료와 인계

정규화 무결성, load, 소수 실제 query가 통과하면 기본 graph 작업 완료다. 원천 최신성 한계는 기록하며 calendar를 늘려 해결하지 않는다.
`handoffs/PHASE-1.md`에 GTFS/graph 경로·hash·버전·scope 건수·제외 보고서·재생성/기동/종료 명령·GraphQL fixture를 남긴다.
다음: [Phase 2](PHASE-2.md). 최종 shape graph 생성은 P3에서 이 도구를 재사용한다.
