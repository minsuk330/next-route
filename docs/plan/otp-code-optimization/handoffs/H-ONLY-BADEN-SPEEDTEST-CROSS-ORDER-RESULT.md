# Baden-Württemberg 공식 SpeedTest 교차 순서 결과

실험일: 2026-09-17  
실험 상태: 완료 (진단용; 호스트 swap 활동으로 PR 성능 근거에는 직접 사용하지 않음)

## 역할과 JAR 파일명

- **A = upstream 원본**
  - `TripPatternForDates`가 `arrivalTimes[]`, `departureTimes[]` 평탄 배열을 생성한다.
  - 각 trip의 `TripTimes` 값을 생성 시 복사하고, 검색 시 평탄 배열을 읽는다.
- **H = H-only 최적화본**
  - `TripTimes[] tripTimesByIndex`와 `int[] offsetsByTrip` 참조를 보관한다.
  - 검색 시 `TripTimes.getArrivalTime/getDepartureTime + offset`으로 계산한다.

소스와 JAR 바이트코드를 모두 확인했다.

| 역할 | JAR | SHA-256 |
|---|---|---|
| A (upstream) | `/private/tmp/otp-baden-build-LgbuLS/otp-A-upstream.jar` | `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` |
| H (optimized) | `/private/tmp/otp-baden-build-LgbuLS/otp-H-optimized.jar` | `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` |

기존 `otp-h-only.jar`/`otp-baseline.jar`는 내용과 이름이 뒤바뀐 과거 산출물이다. 이번 실험에서는 위의 canonical 파일명을 사용했다.

## 고정 조건

- 공식 `travelSearch.csv` (SHA-256 `42c6f861a704b1c6a246b2a55506267be1d4c4c49cb6f11ec8e2a962d0bef65c`)
- 동일 `graph.obj` (SHA-256 `64ee49f2d24243ab21f88225d47f44aeab17017bb6a9b28a2d8d6e476b412bfc`)
- Java 26.0.2.1, G1GC, `-Xms512m -Xmx8g`, 공식 SpeedTest 명령 (`-p md -n 4 -i 3 -0`)
- 각 실행은 새 JVM에서 수행

실험 루트: `/private/tmp/otp-baden-speedtest-order-20260917`

## 결과

SpeedTest 로그의 `Worker`와 `Total` 4회 샘플을 그대로 집계했다 (단위 ms).

| 순서 | 빌드 | Worker 샘플 (평균 ± σ) | Total 샘플 (평균 ± σ) | SpeedTest total |
|---|---|---:|---:|---:|
| A → H | A | 90, 87, 83, 83 (85.8 ± 2.9) | 188, 182, 175, 174 (179.8 ± 5.7) | 45 s |
| A → H | H | 81, 80, 80, 80 (80.3 ± 0.4) | 161, 158, 157, 158 (158.5 ± 1.5) | 41 s |
| H → A | H | 82, 81, 82, 81 (81.5 ± 0.5) | 155, 153, 152, 151 (152.8 ± 1.5) | 39 s |
| H → A | A | 76, 74, 73, 74 (74.3 ± 1.1) | 156, 153, 151, 152 (153.0 ± 1.9) | 39 s |

쌍별 비교:

- **A→H:** H의 Total 평균이 179.8 → 158.5 ms (약 11.8% 낮음), Worker는 약 6.4% 낮음.
- **H→A:** Total은 152.8 vs 153.0 ms로 사실상 동일하며, Worker는 H가 약 9.7% 높음.

순서 효과가 크므로 두 쌍만으로 H의 일관된 속도 향상을 주장할 수 없다. 가장 안전한 결론은 **라우팅 성능 회귀가 관측되지 않았고, 이 공식 테스트에서 A와 H가 실질적으로 동률**이라는 것이다.

## 기능 정합성

네 실행의 결과 CSV가 모두 동일하다.

- 각 결과: 624 lines
- 결과 SHA-256: `a30bf2d90aa874b9828033446b08cac8c3e0c69ef807cbea6f67c7f2b27a61c7`
- A/H 및 실행 순서 간 `cmp` 차이: 0 bytes
- 공식 expected 비교: 네 실행 모두 `67 / 256` 성공, `189 / 256` unexpected

189건 불일치는 A와 H 사이의 차이가 아니라 해당 OTP 버전/graph와 제공 expected 파일의 기준 차이로 보인다. 네 실행에서 동일하게 재현되고 A/H 출력 파일이 byte-for-byte 동일하므로, 이번 비교에서 기능 회귀 증거는 없다.

## GC와 호스트 메모리 관찰

각 공식 실행은 종료 단계의 `System.gc()` 때문에 의미 있는 Full GC가 정확히 1회 기록된다. 이는 timed search 뒤의 정리 단계이며 검색 샘플 비교에는 포함되지 않는다.

| 실행 | Young pause 수 | 종료 Full GC | 최종 heap (committed / used) |
|---|---:|---:|---:|
| A→H / A | 148 | 1 (약 601.6 ms) | 7,254,016K / 3,046,764K |
| A→H / H | 122 | 1 (약 577.8 ms) | 7,737,344K / 3,043,179K |
| H→A / H | 124 | 1 (약 560.3 ms) | 8,065,024K / 3,041,666K |
| H→A / A | 136 | 1 (약 600.0 ms) | 8,122,368K / 3,042,269K |

호스트 카운터는 실행 중 swap/pageout 활동을 보였다. 따라서 이번 SpeedTest는 메모리 압박이 없는 순수 엔진 비교가 아니다.

| 실행 | Pageins Δ | Pageouts Δ | Swapins Δ |
|---|---:|---:|---:|
| A→H / A | +38,933 | +2,368 | +2,227 |
| A→H / H | +67,254 | +983 | +530 |
| H→A / H | +67,454 | +1,079 | +905 |
| H→A / A | +72,429 | +1,441 | +366 |

## 원시 로그

- A→H / A: `/private/tmp/otp-baden-speedtest-order-20260917/AH/A`
- A→H / H: `/private/tmp/otp-baden-speedtest-order-20260917/AH/H`
- H→A / H: `/private/tmp/otp-baden-speedtest-order-20260917/HA/H`
- H→A / A: `/private/tmp/otp-baden-speedtest-order-20260917/HA/A`

