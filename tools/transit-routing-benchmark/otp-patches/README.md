# OTP 빌드 패치

그래프를 **만들 때만** 쓰는 OTP 수정이다. 운영에서 그래프를 읽어 서비스하는 OTP 이미지는 그대로 둔다(그래프의 클래스와 직렬화 번호 278 은 같다).

## 0001-relink-entrances-after-island-pruning.patch
- 기준: `minsuk330/OpenTripPlanner` 의 `9dbfb229b8`(운영 이미지 `…-h-only-9dbfb229b868` 와 같은 커밋).
- 무엇: 작은 도로 섬을 정리한 뒤(island pruning) 정류장만 다시 길에 붙이던 것을, 출입구(`TransitEntranceVertex`)도 다시 붙인다.
- 왜: 통로(`pathways.txt`)가 있는 승강장은 길에 직접 붙지 않고 출입구로만 닿는다. 그런데 정리로 보행 권한을 잃은 도로 섬에 붙어 있던 출입구는 그대로 남아, 그 역의 출구가 통째로 막힌다(실험: 구로, 상록수, 가좌 등 출구 66곳에서 승강장까지 "경로 없음", 구로역 1번 출구에서 출발하면 신도림역까지 17분 걷게 함).
- `build_otp_graph.sh --station-pathways` 는 이 패치가 들어간 jar 만 받는다(파일 이름에 `entrance-relink`).

## jar 만드는 법
```
git clone https://github.com/minsuk330/OpenTripPlanner.git && cd OpenTripPlanner
git checkout 9dbfb229b8
git apply <이 저장소>/tools/transit-routing-benchmark/otp-patches/0001-relink-entrances-after-island-pruning.patch
JAVA_HOME=<JDK 25> mvn -q package -DskipTests -P prettierSkip -pl otp-shaded -am
cp otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar <benchmark>/data/input/otp-h-only-9dbfb229-entrance-relink.jar
shasum -a 256 <그 jar>     # build_otp_graph.sh 에 OTP_JAR_SHA256 로 넘긴다
```
