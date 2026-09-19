# H-only 실험 A/H 역할 정의

작성: 2026-09-17  
상태: `SOURCE_VERIFIED / CANONICAL`

## 고정 의미

모든 H-only 실험에서 A/H는 디렉터리명·JAR basename이 아니라 소스 내용으로 판정한다.

| 역할 | 의미 | `TripPatternForDates` 핵심 구조 |
| --- | --- | --- |
| **A** | OpenTripPlanner upstream 원본 | `int[] arrivalTimes`, `int[] departureTimes`를 `nStops × numberOfTripSchedules`로 만들고 요청 생성 때 stop×trip 값을 복사 |
| **H** | 이번 H-only 메모리 최적화 | 두 평탄 배열을 제거하고 `TripTimes[] tripTimesByIndex`와 `int[] offsetsByTrip`를 보관; 조회 시 원본 값에 날짜 offset을 더함 |

## 소스 확인

- A 원본 소스: `/private/tmp/otp-baden-build-LgbuLS/A/.../TripPatternForDates.java`
  - `new int[nStops * numberOfTripSchedules]` 두 번
  - 모든 trip·stop을 순회해 arrival/departure 값을 복사
- H 최적화 소스: `/private/tmp/otp-baden-build-LgbuLS/H/.../TripPatternForDates.java`
  - `new TripTimes[numberOfTripSchedules]`
  - `new int[numberOfTripSchedules]`
  - 조회 시 `TripTimes` getter와 `offsetsByTrip` 사용

## 현재 Baden JAR의 SHA 매핑

두 파일의 basename은 과거 생성 과정에서 뒤섞였으므로 SHA를 canonical identity로
사용한다.

| 역할 | 현재 파일 경로(legacy basename) | SHA-256 |
| --- | --- | --- |
| A upstream 원본 | `/private/tmp/otp-baden-build-LgbuLS/otp-h-only.jar` | `2e1808e38a84664ffe8b976aef77023bf9240b2726778020ad968224f1f2fdc6` |
| H 최적화 | `/private/tmp/otp-baden-build-LgbuLS/otp-baseline.jar` | `3d583ae579dd1d1d89e8dbd3c7983a89b82c96381c3daf38b26201cc06385b7d` |

따라서 `otp-h-only.jar`라는 이름을 H로, `otp-baseline.jar`라는 이름을 A로 해석하면
안 된다. 새 실행은 반드시 SHA와 바이트코드 확인 후 시작한다.

## 결과 문서 적용 규칙

- raw 디렉터리의 `pair*-A`/`pair*-H` 접미사는 실행 당시 bookkeeping이며 semantic role이 아니다.
- 결과표의 역할은 `status.txt`의 `jarSha256`로 재분류한다.
- 기존 문서에서 이 매핑과 반대로 표기된 A/H 열은 위 기준으로 교정한다.
- A/H 정합성·성능·메모리 결론은 교정된 semantic role을 사용한다.
