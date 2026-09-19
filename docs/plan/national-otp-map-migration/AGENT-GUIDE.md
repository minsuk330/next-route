# Phase 에이전트 공통 실행 계약

작성일: 2026-09-07. 대상: 이 디렉터리의 Phase 0~8을 수행하는 에이전트.
문서 작성은 구현 완료를 뜻하지 않는다. 현재 구현 여부는 코드와 이전 작업 결과로 확인한다.

## 시작 절차

1. 저장소 작업 지침과 git status를 확인한다. 기존 변경을 보존한다.
2. [intent.md](intent.md) → [PLAN.md](PLAN.md) → 배정된 Phase → 선행 Phase의 handoff를 읽는다.
3. 아래 검색으로 실제 코드 위치와 기존 구현을 확인한다. 문서의 클래스명은 탐색 시작점이며 신규 클래스 생성 명령이 아니다.

```bash
git status --short
rg --files src/main src/test tools/transit-routing-benchmark/runner
rg -n 'OdSayApiPort|RemainingRouteSearchService|TransferArrivalEnricher|JourneyCompiler|JourneyNavigationGeometryService' src/main
```

4. 이미 구현·검증된 부분을 구분하고 남은 작업을 수행한다. 선행 산출물이 없으면 독립 작업은 진행하고, 의존 작업은 구체적인 누락 항목을 보고한다.
5. 한 Phase만 배정받으면 다른 Phase까지 자동 진행하지 않는다. 선행 계약 수정이 필요하면 영향과 변경 계약을 handoff에 남긴다.

우선순위: 사용자 최신 결정 → intent → PLAN → 공통 계약 → Phase 세부 내용. 충돌을 발견하면 묵묵히 한쪽을 선택하지 말고 문서와 구현의 차이를 기록한다.

## 확정 구현 기준

- OTP + OSM 전환은 확정. 엔진 비교나 전국 graph 부하 시험을 반복하지 않는다.
- 운영 범위는 기존 bbox: west 126.5 / south 37.2 / east 127.4 / north 38.0. 서울·경기·인천 행정구역 전체를 보장하지 않는다.
- GTFS 원본은 202503_GTFS_DataSet. 기존 2024 benchmark graph는 비교 자료이며 새 산출물로 둔갑시키지 않는다.
- `calendar.txt`의 넓은 원본 날짜는 보존한다. 운영 graph는 OTP `transitServiceStart`/`transitServiceEnd`로 유효 창을 제한하며, 현재 권장값은 build 기준일 `-P1D`부터 `+P2M`이다. `calendar_dates`나 날짜별 시간표 변화가 발견되면 임의 축소하지 않는다.
- 범위 밖 요청은 OUT_OF_SCOPE. 원천 trip이 경계를 넘으면 정류장을 삭제해 새 직통 trip을 만들지 않는다. P1에서 trip 제외와 사유를 기록한다.
- 버스·도시철도 형상은 검증한 OSM route relation으로 만든다. 정류장 직선 연결이나 도보 최단경로로 대체하지 않는다. 결측은 품질 상태와 미지원 사유로 전달한다.
- Naver 우선. 좌표·표시 보정 후에도 실제 호환성이 부족하면 OSM 기반 지도 전환 근거와 구체적 구현안을 남긴다.
- ODsay 비용 절약만을 위한 기능 제한은 해제한다. OTP 자원 보호·알림 중복 방지·남은 외부 API 쿼터 제약은 목적별로 유지/조정한다.
- 새 검색과 재탐색을 모두 전환한다. 활성 Journey는 기존 snapshot/geometry 버전을 유지한다.
- 기존 실시간 버스/지하철·ML 기능은 사용처를 확인해 보존한다. 새 GTFS와 매핑되지 않는 구간만 정적 정보로 표시한다.
- 새 지역 실시간 API 추가는 Phase 8 후속 작업이다. 1차 완료 조건에 넣지 않는다.

## 산출물과 완료 보고

각 에이전트는 이 디렉터리의 `handoffs/PHASE-N.md`를 생성/갱신한다. 현재 파일이 없으면 미수행 상태다. 큰 데이터·원본 응답·비밀키는 문서나 Git에 넣지 않는다.

```markdown
# Phase N 실행 결과
- 상태: COMPLETE / PARTIAL / BLOCKED
- 코드 기준: branch, commit(있으면), 미커밋 변경
- 완료한 작업과 미완료 항목
- 변경 파일 및 계약: API/DTO/설정/schema/오류/버전
- 산출물: 실제 경로, 데이터/hash/graphVersion, 실행 명령
- 검증: 명령, 결과, 기존 증거 재사용 범위, 미실행 이유
- 다음 Phase가 사용할 입력과 재현 방법
- 남은 문제: 발생 조건, 영향, 필요한 입력/결정
```

COMPLETE는 배정 범위의 코드·필수 검증·handoff가 끝난 경우에만 쓴다. 모바일/실기기 검증이 빠졌으면 서버 완료와 전체 완료를 구분한다. 빌드 성공만으로 경로 품질·운영 배포 완료를 선언하지 않는다.

## 변경과 검증 경계

기존 adapter/port/테스트를 우선 재사용한다. 배포된 migration은 수정하지 않고 필요한 경우 신규 번호를 확인해 추가한다. 대상 테스트부터 실행하고 수정과 무관한 대규모 부하 시험을 반복하지 않는다.

P0~5는 로컬 구현과 검증, P6~7은 배포 준비와 명시적으로 배정된 환경의 전환 작업이다. 운영 대상/접근 권한/배포 지시가 없으면 실행 가능한 명령과 rollback을 준비하고 실제 배포는 미수행으로 기록한다. 커밋·PR·머지는 별도 요청 범위를 따른다.
