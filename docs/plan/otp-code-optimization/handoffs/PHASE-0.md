# Phase 0 handoff

- Phase 상태: PARTIAL
- 전체 목표 상태: NOT_EVALUATED
- 작업 위치·branch·base commit: `/Users/wa/golbob/개인/OpenTripPlanner`, `codex/otp-phase0-3`, `02c0b4b537eb07bb0b284726397821682248fe20`
- graph: `/private/tmp/otp-seoul-dev-graph/graph.obj`, SHA-256 `8b597d8bbc1c8588acf0c829735da7ff22ea9d160bd7cb272e23a11ea3002385`
- JVM/container: `maven:3.9-eclipse-temurin-25`, Java 25, ARM64 Docker, Maven cache volume `otp-m2`
- 읽은 선행 산출물: `PLAN.md`, OTP `test/performance/seoul-metro/README.md`, 기존 측정 raw 결과

## 수행 내용

- `TripPatternForDate`와 `RaptorRoutingRequestTransitDataCreator`의 현재 미커밋 변경을 보존했다.
- Docker Maven에서 Spotless/npm을 포함한 정상 명령으로 핵심 테스트를 실행했다.
- 명령:

  ```text
  docker exec otp-maven-node mvn -pl application -am \
    -Dtest=TripPatternForDateTest,RaptorRoutingRequestTransitDataCreatorTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```

- 결과: `BUILD SUCCESS`, 8개 모듈 성공, 핵심 테스트 6개 통과(실패 0, 오류 0, skip 0).
- Spotless가 적용된 뒤에도 동일 테스트가 통과했고 Checkstyle 위반은 0건이었다.
- 후보 full package도 `mvn package -DskipTests`로 성공했다. 후보 shaded JAR:
  `/Users/wa/golbob/개인/OpenTripPlanner/otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar`, SHA-256
  `4464a88b31c9cf04753295104ce338afe20811f6f2c40c13b3a672109709b379`.
- 원본 커밋을 `/private/tmp/otp-baseline`에 복원하고 원 저장소 `.git`을 읽기 전용으로 연결한 뒤 같은
  `mvn package -DskipTests`를 성공시켰다. 원본 shaded JAR SHA-256:
  `914d9a58c016d2ff1a697e2d6fb8d9d8dec7d93dada747d8162acd8a3da225b1`.

## 미완료

- 운영 graph/설정/query를 포함한 실행 manifest와 baseline A/A 재실행은 Phase 1에서 수행해야 한다.
- 기존 shaded JAR class 주입 산출물은 최종 비교의 증거로 사용하지 않는다.

## 다음 단계

Phase 1에서 수정된 측정기로 warmup/measured raw를 모두 보존하고, A/A 정합성과 sampler/GC 수집을 먼저 통과시킨다.
