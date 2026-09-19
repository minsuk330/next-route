# Baden-Württemberg A/H SpeedTest 결과 (소스 역할 기준)

실행일: 2026-09-17  
상태: `COMPLETED / ROLE_VERIFIED`

## 실행 정체성

임시 build의 basename은 역할을 보장하지 않는다. 소스와 바이트코드를 기준으로
A=upstream 원본, H=이번 최적화로 확정한다. 아래 파일 경로는 legacy basename이고,
역할은 SHA와 클래스 내용으로 판정한다.

| 역할 | 실행 JAR | SHA-256 | 바이트코드 확인 |
| --- | --- | --- | --- |
| A upstream 원본 | `/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar` | `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` | `arrivalTimes[]`, `departureTimes[]` 평탄 배열 복사 |
| H 최적화 | `/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar` | `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` | `TripTimes[]`, `offsetsByTrip` 참조 |

공유 graph SHA-256은
`64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`, 공식 Baden
`travelSearch.csv` SHA-256은
`42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`이다.

두 JAR는 91,401개 entry가 같고 `TripPatternForDates.class`와 build metadata 외의
내용 차이는 확인되지 않았다.

## 조건

- 동일 graph와 동일 공식 corpus 256건
- `-p md -n 4 -i 3 -0`
- Java 26.0.2.1, `-Xms512m -Xmx10g -XX:+UseG1GC`
- 독립 JVM, 보존 raw 디렉터리의 실행 순서는 H 최적화(`baseline/` legacy dir) 후
  A 원본(`h-only/` legacy dir)이며, 결과 표는 semantic role로 재분류했다.
- Docker 미사용
- 공식 expected CSV와 A/H 결과를 별도로 비교

실행은 공식 `SpeedTest` main을 직접 호출한 local equivalent다. Maven exec plugin 대신
각 JAR와 동일 dependency classpath를 사용했으며, upstream CI 실행 결과로 표현하지 않는다.

## 결과

| 지표 | A 원본 | H 최적화 | H 변화율 `(A-H)/A` |
| --- | ---: | ---: | ---: |
| Worker 평균 | 80.3 ms | 86.5 ms | 약 -7.7% |
| Total 평균 | 160.5 ms | 168.5 ms | 약 -5.0% |
| 공식 expected 일치 | 67/256 | 67/256 | 동일 |
| 결과 CSV SHA-256 | `a30bf2d90aa874b9828033446b08cac8c3e0c69ef807cbea6f67c7f2b27a61c7` | 동일 | 동일 |

두 실행 모두 종료 코드 0이며, 공식 expected와 189건이 불일치했다. 이 189건은 A와
H가 동일하게 발생했고 변형 간 결과 CSV는 byte-for-byte 동일하므로, 이번 A/H 비교에서
H 고유 오류로 해석하지 않는다.

이번 실행에는 별도 GC 로그 플래그를 넣지 않았으므로 SpeedTest 결과만으로 GC 횟수나
pause를 주장하지 않는다. 이전 Baden 공식 로그의 `8dbad55...` 혼합 커밋 metadata와
구분하기 위해, 이번 문서의 성능 수치는 위의 SHA로 검증한 renamed JAR 실행에만 한정한다.

## 해석과 한계

- 이 단일 SpeedTest에서는 H 최적화가 Worker/Total 평균에서 오히려 높게 관측됐다.
- 표본은 4 samples × 256 cases이며, 교차 반복이나 host pressure 통제가 없는 단일 실행이다.
  따라서 upstream 보편 성능 개선으로 일반화하지 않는다.
- 이 결과는 소스 역할 기준으로 재분류한 기준값이다. 이후 HTTP 지속 부하 P5/P6도
  legacy basename이 아니라 위의 SHA로 A/H를 판정한다.

## 과거 Baden 실행의 라벨 감사

초기 Baden 산출물에서는 JAR basename과 의미론적 역할이 섞여 기록됐다. 소스 내용을
대조한 결과 `/A`는 A 원본(평탄 배열), `/H`는 H 최적화(참조/offset)이다.

| 과거 산출물 | 보존된 실행 경로 | 실제 변형 | 판정 |
| --- | --- | --- | --- |
| `official/A-rerun` | `process.csv`의 `maven.multiModuleProjectDirectory=/private/tmp/otp-baden-build-LgbuLS/A` | A 원본 (`arrivalTimes`, `departureTimes`) | **A 원본으로 확정** |
| `official/H-run` | `process.csv`의 `maven.multiModuleProjectDirectory=/private/tmp/otp-baden-build-LgbuLS/H` | H 최적화 (`TripTimes[]`, `offsetsByTrip`) | **H 최적화로 확정** |
| `crossover-20260916-225714/A1,A2` | `process.csv`의 source path `.../A` | A 원본 | **A 원본으로 확정** |
| `crossover-20260916-225714/H1,H2` | `process.csv`의 source path `.../H` | H 최적화 | **H 최적화로 확정** |

근거는 source worktree의 `TripPatternForDates.java`와 실행 command line이다. `/A`에는
원본의 `arrivalTimes`/`departureTimes` 평탄 배열이, `/H`에는 최적화의
`TripTimes[]`/`offsetsByTrip` 필드가 있다. 따라서 legacy JAR basename만 보고 작성된
이전 표가 있다면 위 의미론적 역할로 열을 교정해야 한다.
`official/A` 최초 실행은 보존된 process command가 불완전하므로 변형을 확정하지 않고
성능 근거에서 제외한다.

P3/P5/P6의 raw도 동일하게 JAR SHA로 역할을 재분류해야 하며, legacy basename을
근거로 한 기존 표는 신뢰하지 않는다. Helsinki shared-graph 및 VPS A/HF 결과는
별도 SHA와 정의를 가지므로 이 Baden 역할 표와 섞지 않는다.

## 원시 자료

- [A upstream raw log](/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-speedtest-renamed/h-only/speedtest.log)
- [H optimized raw log](/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-speedtest-renamed/baseline/speedtest.log)
- [A upstream result CSV](/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-speedtest-renamed/h-only/travelSearch-results-md.csv)
- [H optimized result CSV](/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-speedtest-renamed/baseline/travelSearch-results-md.csv)
- [SHA-256 manifest](/Users/wa/golbob/개인/nextroute/.artifacts/otp-baden-speedtest-renamed/SHA256SUMS.txt)
