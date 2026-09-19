# S1 → S2 핸드오프 (PR-0 산출)

작성: 2026-09-15. 세션: S1 (OTP fork H+F commit + clean rebuild). PR: minsuk330/OpenTripPlanner#1 (base dev-2.x, fork 내부).
다음 세션 S2 = PR-1 Dockerfile.otp + GHCR push + P2 graph 재빌드. 이 파일 값이 S2 입력.

## 확정 아티팩트 (S2 에서 그대로 사용)

| 항목 | 값 |
|---|---|
| fork repo | `/Users/wa/golbob/개인/OpenTripPlanner` |
| branch | `codex/otp-phase0-3` |
| **fork commit SHA** | `8dbad55ba40a36606e47f98688db958b4c3eeab0` (부모 baseline `02c0b4b537eb07bb0b284726397821682248fe20`) |
| **배포 JAR** | `otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar` |
| JAR 크기 | 188,023,014 bytes |
| **JAR SHA-256** | `dcd08dedf7060bca3f89a14bd645bf972eda4998cc3acd8e5bab17a02ce45284` |
| **serialization id** | `276` (JAR otp-project-info.properties `otp.serialization.version.id`) |
| project.version | `2.10.0-SNAPSHOT` |
| git.dirty | **false** (dirty 낡은 JAR `54bbdbcf..db7f` 아님 — 재현본) |
| git.build.time | `2026-09-15T04:16:03Z` |

## 빌드 재현 절차 (동일 JAR SHA 재현 = 이 명령)

```sh
cd <OpenTripPlanner fork>        # 커밋 8dbad55 체크아웃 상태
docker run --rm -v "$PWD":/work -w /work -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-25 \
  mvn -q clean package -pl otp-shaded -am -DskipTests -Dplugin.spotless.skip=true
shasum -a 256 otp-shaded/target/otp-shaded-2.10.0-SNAPSHOT.jar
# 기대: dcd08dedf7060bca3f89a14bd645bf972eda4998cc3acd8e5bab17a02ce45284
```

- **빌드 이미지 = maven:3.9-eclipse-temurin-25** (Java 25). host JDK 혼재(26/23) 회피 + temurin-25 런타임과 identity 일치.
- **`-Dplugin.spotless.skip=true` 필수**: 빌드 이미지에 node/npm 없어 Spotless(prettier) 가 validate 단계에서 죽음. `-Dspotless.skip` 은 무효(pom 이 `plugin.spotless.skip` 로 파라미터화). 포맷 체크라 JAR identity 무관.
- **채택 게이트**: JAR 채택 전 `unzip -p <jar> otp-project-info.properties` 로 `git.commit.id==8dbad55` && `git.dirty=false` 확인. 안 그러면 target/ 의 낡은 dirty JAR 오인.

## S2 가 할 일 (요약)
- Dockerfile.otp: `FROM eclipse-temurin:25-jre` + curl(apt) + JAR COPY + otp-config bake + exec-form entrypoint. **x86**(`--platform linux/amd64`) GHCR push → digest 고정. OCI label 에 fork revision(8dbad55) + JAR SHA(dcd08ded..) 넣기.
- P2: 그 이미지로 graph 재빌드(`docker run <image> --build --save /var/opentripplanner`) → graph header `0000276` 확인 → graph.obj SHA-256 → S2-out.md.

## 성능/정확성 게이트
- **P4_FORMAL_PENDING**: H+F 는 성능 gate 미완 실험 최적화. "완료" 표기 금지. shadow 배포는 dormant(OTP HTTP 호출 0)라 이 미완이 프로덕션 검색에 영향 0. 실제 전환 전 README correctness fingerprint(4 OD) 재현 필수.
