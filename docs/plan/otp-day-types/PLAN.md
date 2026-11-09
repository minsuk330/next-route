# 지하철 요일별 시간표 (L, slug `otp-day-types`, 이슈 #557)

## 결론
세 단계로 나눈다.
- **PR-0 (마감 12-22)**: 시간표는 그대로 두고 운행 기간만 늘린 그래프를 먼저 배포한다. 지금 그래프는 12-23 부터 대중교통이 없다.
  이 그래프가 이후 단계의 되돌리기 대상이 된다.
- **PR-1**: 3·7·8·9호선을 평일, 토요일, 휴일(일요일·공휴일) 시간표로 나눈다. 평일은 KTDB 운행을 그대로 두고 운행 패턴만 평일 전용으로 바꾼다.
  토·휴일 운행은 서울 시간표 API 로 만든다. 공휴일은 `calendar_dates.txt` 로 휴일 시간표에 넣는다.
- **PR-2 이후**: 갈래가 있는 4·5호선 → 1호선(route 7쌍, 코레일 구간) → 순환인 2·6호선. 각 단계에서 열차를 route 에 배정하는 규칙을 새로 만든다.
  이번 계획은 PR-0 과 PR-1 을 확정하고, PR-2 이후는 방향만 적는다.

예: 2026-11-08(일) 23:58 동대문역사문화공원 5호선 하행. 지금은 평일 운행(24:02 하남검단산 등)으로 천호행 경로가 나온다.
5호선이 들어가는 PR-2 뒤에는 휴일 운행만 보여 23:58 왕십리행이 막차가 된다. PR-1 은 같은 문제를 3·7·8·9호선에서 고친다.

## 사실 (조사 2026-11-09, 리뷰 반영)
- 원인: KTDB 원본 `calendar.txt` = `B1,1,1,1,1,1,1,1,20170101,20301231` 한 줄. 설명서 p7 "2025년 3월 중 평일 1일 기준 …
  모든 요일에 운행하는 것으로 코딩", p10 기준시점 "2025년 3월 평일 1일". 운영 입력 zip
  (`data/input/national-nojeju-202503-shaped-gbis-busrt-entrances-addtrips-subwayfix-calend20261222.gtfs.zip`) 도 같고, calendar 는 `B1 … 20260729,20261222`
- 운영 그래프 `serviceTimeRange` = 2026-10-21 00:00 ~ 2026-12-23 00:00 KST. **12-23 부터 대중교통 없음**
- **OTP 운행 기간 창**: `config/otp-national/build-config.json:22-23` `transitServiceStart "-P1D"`, `transitServiceEnd "P2M"`.
  빌드한 날 기준 -1일 ~ +2개월만 그래프에 들어간다. `extend_calendar.py:121-127` 은 `--calendar-end` 가 이 창 끝을 넘으면 실패한다.
  그래서 `--calendar-end 20270331` 은 11월 빌드에서 실패한다. 창 길이가 같으면 힙도 그대로다(대중교통 하루당 약 3.5 MB, extend_calendar 설명문)
- 운영 OTP(VPS 실측 11-09): 컨테이너 한도 4.5 GB(`mem_limit` 4718592000), `-Xmx3500m`, 사용 3.42 GiB. 호스트 7.9 GB 중 여유 약 730 MB
- KTDB 1~9호선 route 33개(운영 zip 실측, 정차역 전부 `RS_ACC1_S-1-*`):
  | 노선 | route | 지금 build 규칙으로 되나 |
  |---|---|---|
  | 3호선 | `03-1D/U` 44역, 가장 긴 운행 44 | 된다 |
  | 7호선 | `07-1D/U` 53역, 53 | 된다(평일 204/204, 75/75 초 단위 일치, 10-22 노트) |
  | 8호선 | `08-1D/U` 24역, 24 | 된다 |
  | 9호선 | 일반 `09-1D/U` 38역, 급행 `09-2D/U` 16역 | 급행을 `09-2` 에 배정하는 규칙만 더하면 된다 |
  | 4호선 | `04-1D/U` 51역, 가장 긴 운행 48 | 안 된다(`seoul_timetable_trips.py:289` "longest trip does not cover") |
  | 5호선 | `05-1D/U` 56역, 49(마천/하남 갈래) | 안 된다(같은 곳) |
  | 1호선 | 7쌍(`01-1` 65역, `01-2` 58역, `01-3` 7역, `01-4` 16역, `01-6` 급행, `01-7` 49/40역) | 안 된다(API 역 목록이 노선 전체 → `:281`) |
  | 2호선 | 본선 `02-1I/O` 43역인데 운행 하나가 517정차, 한 역 최대 13회 / 지선 `02-2`, `02-3` 5역 | 안 된다(`:144` "a station twice") |
  | 6호선 | `06-00` 하나에 양방향과 응암 순환, 39역, 가장 긴 운행 73, 한 역 최대 2회 | 안 된다 |
- 서울 `SearchSTNTimeTableByIDService/1/1000/{STATION_CD}/{WEEK_TAG}/{INOUT_TAG}/`(키 `SEOUL_SUBWAY_MASTER`, env/api.env):
  - 1~9호선 458역 평일 0건인 역 없음. `WEEK_TAG` 1 평일, 2 토, 3 휴일. 필드 `TRAIN_NO, ARRIVETIME, LEFTTIME, SUBWAYSNAME, SUBWAYENAME, EXPRESS_YN, INOUT_TAG, FR_CODE`
  - 5호선 동대문역사문화공원: 평일 198편, 토 163, 휴 163, 토와 휴일 23:40 이후가 같다(하행 23:42 상일동, 23:50 군자, 23:58 왕십리)
  - 기존 조사(약 900회 호출)에서 한도 오류 없음. 일일 한도는 확인 안 함
- 기존 도구(`tools/transit-routing-benchmark/runner/`):
  - `seoul_timetable_trips.py fetch|build`: 역별 응답 캐시, `TRAIN_NO` 로 노선 순서대로 이어 운행을 만든다. 출발역 ARRIVETIME 00:00:00,
    종착역 LEFTTIME 00:00:00, 그 밖의 00:00:00 = 정차 안 함, 자정 넘으면 24:xx(`:173`, 03:00 전 출발을 같은 표의 24:xx 로).
    역 → stop_id 는 `RS_ACC1_S-1-{STATION_CD}`. `:215` route 의 service 가 둘 이상이면 실패
  - `add_missing_trips.py`(2g): 같은 route 기존 운행을 틀로 복사. `:170` base = route 의 모든 운행, `:190` 같은 정차 목록 60초 안 = 중복 실패,
    `:195` 틀과 serviceId 같아야 함, `:198` 붙인 운행이 이후 base 에 들어감, `:203` 추월 검사
  - `extend_calendar.py`(2f): `end_date` 만 늘린다. 창 끝 넘으면 실패
  - `add_station_entrances.py`(2c): stops.txt 만 고치고 trips·stop_times 는 바이트 그대로 복사
  - `build_otp_graph.sh`: 2b GBIS → 2d 버스 소요시간 → 2g 추가 운행 → 2e 지하철 보정 → 2c 가상 역 → 2f 운행 기간.
    `--from-zip` 은 1~2c 가 이미 들어간 zip 에서 시작. `:136` `--add-trips`/`--subway-corrections`/`--calendar-end` 중 하나 필수. 배포 포크 jar(ser.ver.id 278)
- 서버(리뷰로 확인): Java 에 `"B1"`, GTFS service_id 쓰는 곳 없음. 지하철 실시간 결속은 trainNo·lineId, `RailServiceClassifier.java:54-56` 은 route id 로 급행 판정.
  서버가 저장하는 GTFS 운행 id 는 `bus_arrival_label_event.trip_id`(버스) 뿐
- `KoreanPublicHolidayCalendar`(Java, ML 피처용)에 2027 없음, 2026-06-03 지방선거일 없음(별건)

## PR-0 운행 기간만 연장 (마감 12-22)
- 코드 변경 없음. `build_otp_graph.sh --profile national --from-zip <운영 입력 zip> --calendar-end <빌드일 + 2개월 - 1일>`
  예: 11-12 빌드 → `--calendar-end 20270111`. `transitServiceEnd` 는 P2M 그대로(힙 그대로)
- 운영 입력 zip 의 calendar 접미사가 `calend20261222` 라 `--from-zip` 출력 이름이 `calend2027MMDD` 로 바뀐다
- 검증: 로컬 OTP `serviceTimeRange.end` = 빌드일 + 2개월, 평일 코퍼스 경로가 옛 그래프와 같다, OTP 기동 후 힙이 옛 그래프와 비슷하다
- 배포: 아래 "배포 절차". 새 graphVersion 은 `…-calend2027MMDD-g<sha>`
- 이후 운영 규칙: 창이 빌드일 + 2개월이라 **약 6주마다 다시 빌드해야 한다**. 날짜 고정(`transitServiceEnd: "2027-03-31"`)은 창이 넓어져
  힙이 약 +270 MB 늘어 호스트 여유(730 MB)를 먹으므로 이번엔 하지 않는다. 다음 빌드 기한을 노트 `### 미결` 에 적는다

## PR-1 3·7·8·9호선 요일 시간표

### 운행 패턴(service_id)
| service_id | 요일 | 쓰는 운행 |
|---|---|---|
| `B1` | 매일 | 대상 route 밖 모든 운행 — 그대로 |
| `M_WD` | 월~금 | 대상 route 의 KTDB 운행. 지금 `B1` 에서 옮긴다 |
| `M_SA` | 토 | 토요일에만 있는 열차(서울 API `WEEK_TAG` 2) |
| `M_HO` | 일 | 휴일에만 있는 열차(`WEEK_TAG` 3) |
| `M_SH` | 토·일 | 토요일 표와 휴일 표에 열차번호와 역별 시각이 똑같이 있는 열차(열차 단위 판정) |

- 공휴일(`calendar_dates.txt`): 평일 공휴일은 `M_WD` 제외(2) + `M_HO`·`M_SH` 추가(1). 토요일 공휴일은 `M_SA` 제외 + `M_HO` 추가. 일요일 공휴일은 그대로
- 공휴일 목록: `config/otp-national/kr-holidays.json` = `{"coverage": {"from": "2026-11-01", "to": "2027-03-31"}, "days": [{date, name, source}]}`.
  12-25, 2027-01-01, 2027 설날 연휴와 대체공휴일, 삼일절. 행정안전부 발표로 확인해 근거를 적는다
- 운행 기간: PR-0 과 같은 규칙(빌드일 + 2개월 - 1일)

### 대상 route (allowlist)
- 대상 = day-types 데이터 파일이 있고 관문을 통과한 route 만. 자동 판정하지 않는다(정차역 접두로 판정하면 33개 전부가 대상이 되어,
  토·휴일 운행을 못 만든 route 가 `M_WD` 로만 남아 주말 운행이 0 이 된다)
- 관문(노선 방향별, 둘 다 통과해야 대상): 평일 API 운행과 KTDB 일치율 ≥ 95%, 토·휴일 unmatched ≤ 5%.
  못 넘으면 그 route 는 데이터 파일을 만들지 않고 `B1` 그대로 둔다(평일 일치가 낮으면 같은 방법으로 만든 주말 데이터도 못 믿는다). 수치는 리포트로 남긴다
- PR-1 대상 후보: `03-1D/U`, `07-1D/U`, `08-1D/U`, `09-1D/U`, `09-2D/U`

### 토·휴일 운행 만들기
- API 운행 → 운행 단위: `seoul_timetable_trips.py build` 규칙(3·7·8호선 그대로)
- route 배정: 정차 목록이 같은 route 먼저, 없으면 그 목록을 연속으로 품은 route. `EXPRESS_YN` 이면 급행 route(`09-2`)에만.
  배정 못 한 열차는 `unmatched`
- INOUT ↔ KTDB 방향(`D/U`) 짝은 가정하지 않는다. 도구가 평일 일치율이 높은 쪽으로 정하고 리포트에 남긴다. 두 짝의 일치율 차가 작으면 실패
- GTFS 행은 `add_missing_trips.py` 의 틀 복사를 쓴다. 틀은 같은 route 의 `M_WD` 운행만
- trip_id = `{route_id}_{SA|HO|SH}_{TRAIN_NO}` (`load_data` 의 `routeId_` 접두 검사와 맞음)
- 틀이 없는 정차 목록은 만들지 않고 `unmatched`. 운행을 지어내지 않는다
- **자정 넘은 운행일 확인(구현 첫 단계)**: 토 표와 휴일 표의 00:00~03:00 행을 금요일 밤 평일 운행, 토요일 밤 운행과 비교해 그 행이
  "그 표의 날 밤에 이어지는 열차"(지금 `:173` 가정)인지 "전날 밤 열차"인지 확인하고 노트 `### 확정` 에 적는다. 다르면 `:173` 규칙을 표별로 바꾼다

### 같은 날 운행끼리만 비교 (구현: add_missing_trips.py 는 고치지 않고 apply_day_types.py 가 함수만 가져다 씀)
- 틀 = 그 route 의 `M_WD` 운행(plan_trip), 새 운행 service_id = 데이터 파일의 `service`
- `:170`/`:190` 중복과 `:203` 추월: 운행일이 겹치는 운행끼리만 비교. 겹침 표 = WD↔WD, SA↔SA·SH, HO↔HO·SH, SH↔SA·HO·SH (WD 는 공휴일에만 HO·SH 와 바뀔 뿐 같은 날 같이 돌지 않음)
- 앞 파일이 붙인 운행은 틀로 쓰지 않는다(틀 = `M_WD` 만)
- 2g(add_missing_trips.py) 동작은 그대로

### 파이프라인
- 새 단계 2h `runner/apply_day_types.py`: **2e → 2f → 2h** 순서(2f 뒤라 공휴일 가드가 늘어난 운행 기간을 본다). 2c 는 trips 를 건드리지 않으니 순서와 무관
- `build_otp_graph.sh --day-types DIR --holidays JSON`, 접미사 `-daytypes`(2f 접미사 뒤), 리포트 `day-types-report.json`.
  `:136` `--from-zip` 가드의 "하나 필수" 목록에 `--day-types` 추가
- 가드: 공휴일이 운행 기간 밖이면 실패 / 운행 기간이 공휴일 JSON `coverage` 를 넘으면 실패(공휴일이 빠진 채 조용히 나가는 것 방지) /
  대상 route 에 `B1` 아닌 운행이 이미 있으면 실패(같은 입력이면 `alreadyApplied`)
- 데이터 받기: `seoul_timetable_trips.py fetch --line 0N호선 --weeks 1,2,3 --inouts 1,2`(3·7·8·9호선). 캐시(키 없음)를 남기고 받은 날짜를 `source.fetched` 에
- `seoul_timetable_trips.py build`/`--check-route` 는 2h 이전 zip 에만 돌린다(`:215` 가 service 둘 이상이면 실패). 도구 설명과 리포트에 적는다

## 변경 파일 (PR-1)
- `tools/transit-routing-benchmark/runner/seoul_timetable_trips.py`: `build-day-types` 하위 명령(route 배정, 방향 짝 추론, 열차 단위 SH 판정, 관문, 평일 일치 리포트)
- `tools/transit-routing-benchmark/runner/apply_day_types.py`(신규)
  ```python
  def run(src_zip: Path, out_zip: Path, data_dir: Path, holidays: Path) -> dict  # report
  ```
- `tools/transit-routing-benchmark/runner/build_otp_graph.sh`: 2h 단계, 인자, 접미사, `--from-zip` 가드
- `tools/transit-routing-benchmark/config/otp-national/kr-holidays.json`(신규)
- `tools/transit-routing-benchmark/config/otp-national/day-types/*.json`(신규, 생성물 커밋 — 기존 added-trips 와 같은 방식)
- 시험: `test_apply_day_types.py`(신규), `test_seoul_timetable_trips.py`(신규)
- `.gitignore`: `data/otp-national/day-types-report.json`

## 순서
- [ ] PR-0 빌드(**2026-11-09 23시 이후**, 진행 여정 0 일 때 시작, 사용자 결정) → 로컬 검증 → 배포(사용자 확인). **12-22 전**
- [x] PR-1 자정 넘은 운행일 확인 → 노트(API 가 표마다 24:xx 로 줌, 규칙 그대로)
- [x] PR-1 도구 + 시험(가짜 GTFS)
- [x] PR-1 데이터: 3·7·8·9호선 fetch → 관문 전부 통과 → day-types JSON 10개. 공휴일 JSON = 천문연 특일 API
- [ ] PR-1 빌드(새벽, 진행 여정 0 일 때 시작): PR-0 입력 zip 에 `--day-types` + `--holidays` + `--calendar-end`, 로컬 OTP 검증
- [ ] PR-1 배포(사용자 확인). 되돌리기 대상 = PR-0 그래프
- [ ] PR-2 이후: 4·5호선(갈래별 역 순서) → 1호선(route 7쌍 배정) → 2·6호선(순환 한 바퀴 단위). 단계마다 계획 보강

## 배포 절차 (PR-0, PR-1 공통)
- **새벽(KST)에만**. 빌드 시작 직전과 교체 직전에 진행 여정 0 을 각각 확인(0 아니면 미룸)
- `/srv/nextroute/otp/<graphVersion>/` 업로드(2MB 분할, sha256) → VPS compose graphVersion 4곳 + observability 1곳(백업 먼저, 외과 수정) →
  `up -d otp` → `deploy.sh api|worker`(`DEPLOY_REVISION`), worker2 재생성
- 힙: OTP 기동 후 힙과 컨테이너 메모리가 옛 그래프 + 300 MB 안이어야 배포한다(검증이 아니라 배포 조건)
- 되돌리기: 직전 graphVersion 으로 compose 복구 후 같은 순서. 12-23 이후 지금 운영 그래프로 되돌리면 대중교통이 없으므로 PR-0 그래프가 되돌리기 대상

## 시험 (PR-1)
- `test_apply_day_types.py`(가짜 GTFS: 대상 route 2개, 비대상 1개):
  - 대상 운행이 `M_WD` 로, 비대상은 `B1` 그대로 바이트 그대로
  - 토·휴일 운행이 틀의 stop_sequence·shape 로 붙고 trip_id 규칙대로
  - 토·휴일 같은 열차는 `M_SH`, 다른 열차는 `M_SA`/`M_HO`(같은 route 에 섞여도)
  - 평일 공휴일 → `M_WD` 제외, `M_HO`·`M_SH` 추가 / 토요일 공휴일 → `M_SA` 제외, `M_HO` 추가
  - 토·휴일 첫차가 평일 운행과 같은 시각이어도 실패 안 함 / SA 와 HO 가 같은 시각이어도 실패 안 함 / 같은 날 도는 운행끼리 60초 안이면 실패
  - 틀 없는 정차 목록 → 운행 안 만들고 `unmatched`
  - 다시 적용 → `alreadyApplied` 같은 바이트 / 공휴일이 기간 밖 → 실패 / 기간이 coverage 를 넘음 → 실패
- `seoul_timetable_trips` build-day-types: 급행 → `09-2` 배정, 방향 짝 추론과 모호하면 실패, 관문 미달 route 는 파일 안 만듦, 자정 넘는 운행 규칙
- `test_add_missing_trips.py`: 인자 없는 기존 동작 그대로
- 명령: `cd tools/transit-routing-benchmark/runner && python3 -m unittest test_apply_day_types test_add_missing_trips test_extend_calendar test_seoul_timetable_trips`

## 검증 (로컬 OTP)
- PR-0: `serviceTimeRange.end` = 빌드일 + 2개월, 평일 코퍼스 경로가 옛 그래프와 같다
- PR-1:
  - 대상 노선 한 역의 휴일 막차가 API 휴일 표와 같고, 평일은 옛 그래프와 같다(역은 구현 때 리포트에서 고른다)
  - 자정 넘은 운행일 세 시각: 2026-11-16(월) 00:05 KST(일요일 운행일 = 휴일 표), 11-14(토) 00:30(금요일 평일 운행), 12-26(토) 00:30(12-25 휴일 밤)
  - 공휴일: 12-25(금) 가 일요일과 같다
  - 평일 회귀: 코퍼스 평일 `planConnection` 결과와 **평일 패턴 코드 목록**이 PR-0 그래프와 같다
  - 리포트: route 별 토·휴일 운행 수가 API 와 같다(unmatched 제외), 관문 수치, 방향 짝

## 경계 리뷰
- 계약: 앱 API 는 그대로. 대상 노선의 주말·공휴일 경로와 소요시간이 바뀐다(의도). 평일 경로와 패턴 코드는 같아야 한다
- 상태: 그래프 교체 때 진행 중인 여정의 패턴 코드가 어긋날 수 있다 → 빌드와 교체는 새벽에, 진행 여정 0 일 때만(사용자 결정 2026-11-09)
- 실패 경로: 2h 가드 실패 → 빌드 중단. 배포 후 이상 → PR-0 그래프로 복구
- 플래그: 없음. 그래프 버전이 곧 스위치다. 대상 route 는 데이터 파일 유무로 정해진다

## 리스크
- 틀 없는 주말 정차 패턴(주말 전용 급행·단축 운행)은 빠진다 → unmatched ≤ 5% 관문
- 운행 기간 창이 2개월이라 약 6주마다 다시 빌드해야 한다. 놓치면 12-23 같은 절벽이 다시 온다
- 공휴일 목록은 사람이 관리(`coverage` 가드로 빠짐을 막음). 서버 `KoreanPublicHolidayCalendar` 와 출처가 둘 — 합치는 이슈를 따로 남긴다
- 서울 API 일일 한도 미확인 → 캐시로 나눠 받는다
- 5호선(이번 사고 노선)은 PR-2 에서 고친다(사용자 결정 2026-11-09)

## 1~9호선 밖 지하철 (조사 중)
- 사용자 요청(2026-11-09): 1~9호선 밖 지하철·철도 시간표 출처를 조사 에이전트로 찾는다(TAGO, 코레일, 운영기관 파일 데이터 등).
  결과에 따라 열차 단위 출처가 있는 노선은 PR-1 과 같은 틀 복사 방식으로 뒤 단계에 넣고, 역별 시각만 있는 노선은 방법을 따로 정한다.
  조사 결과는 이 절에 덧붙인다
- **조사 결과(2026-11-09, 조사 에이전트)**:
  - 최선 출처 = 철도데이터포털(KRIC) 파일 "전체_도시철도운행정보_20260630.xlsx"(키 없음, `data.kric.go.kr/rips/dataset/download.file?type=filedata&id=900&operation=1`,
    17.9 MB, 223,425행, 포털 수정 2026-10-12). 열차번호 + 요일구분 + 열차 단위 정차역 시각. 21개 노선(수인분당, 신분당, 경의중앙, 경춘, 경강, 서해, 동해,
    공항, 인천1, 우이, 신림, 김포, 의정부, 부산1~4, 부산김해, 대구1~3, 광주, 대전) + 1호선 코레일 구간. 노선마다 형식이 다르다(코레일 = 한 행 한 정거장·소수 시각,
    나머지 = 한 행 한 열차 `001-역+002-역…`)
  - KRIC 에 시각이 없는 노선(GTX-A, 대경선, 인천2, 에버라인)은 TAGO 만 있다(열차번호 없음, 역별 시각 + 종착역). 인천2 는 인천교통공사 파일(열차 순번별 전 역 시각)도 있다(미검증)
  - 서울 `SearchSTNTimeTableByIDService` 는 1~9호선(코레일 구간 포함)만. 다른 노선 역 코드는 전부 `INFO-200`
  - 토요일이 휴일과 따로 있는 노선은 신분당(값은 같음), 부산1~4, 대구1~3, 광주뿐. 나머지는 토요일 = 휴일 시각표 → `M_SH` 로 충분
  - KTDB 평일과 KRIC 평일 일치(60초): 대구·부산·우이·광주·수인분당 ≥92%, 대전 93%, 신분당·신림 87~88%, 김포 83%, 서해 77%, 경강 74%,
    의정부 65%, 경춘 63%, 경의중앙 61%, 인천1 53%, 공항 49%, 동해 44%. 낮은 노선은 KTDB 평일이 낡았다(2025-03 뒤 개정)
- **방향(다음 단계 계획에서 확정)**: KRIC 노선은 열차 단위라 틀 복사 없이 바로 운행을 만들 수 있다. 일치율이 관문을 넘는 노선은 PR-1 과 같은 방식(평일 KTDB 유지 + 토·휴 추가),
  못 넘는 노선은 평일까지 KRIC 로 바꾸는 별도 단계(평일 결과가 바뀌므로 회귀 기준이 달라짐). TAGO 노선은 시발역 출발시각 + KTDB 역간 소요시간으로 잇는다.
  KRIC 행 → GTFS stop_id 는 역 이름 대응이 필요(경강·서해 옛 표기 등)

## 범위 밖
- 1·2·4·5·6호선(PR-2 이후), 1~9호선 밖 지하철·철도(조사 뒤 별도 단계), 버스 주말 시간표, 서버 공휴일 달력 갱신, 실시간 행선지 경고(#557 2단계),
  운행 기간 창 고정(힙 여유 확보 뒤)
