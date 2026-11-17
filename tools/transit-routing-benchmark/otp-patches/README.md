# OTP 빌드 패치

그래프를 **만들 때만** 쓰는 OTP 수정이다. 운영에서 그래프를 읽어 서비스하는 OTP 이미지는 그대로 둔다(그래프의 클래스와 직렬화 번호 278 은 같다).

## 0001-relink-entrances-after-island-pruning.patch
- 기준: `minsuk330/OpenTripPlanner` 의 `9dbfb229b8`(운영 이미지 `…-h-only-9dbfb229b868` 와 같은 커밋).
- 무엇: 작은 도로 섬을 정리한 뒤(island pruning) 정류장만 다시 길에 붙이던 것을, 출입구(`TransitEntranceVertex`)도 다시 붙인다.
- 왜: 통로(`pathways.txt`)가 있는 승강장은 길에 직접 붙지 않고 출입구로만 닿는다. 그런데 정리로 보행 권한을 잃은 도로 섬에 붙어 있던 출입구는 그대로 남아, 그 역의 출구가 통째로 막힌다(실험: 구로, 상록수, 가좌 등 출구 66곳에서 승강장까지 "경로 없음", 구로역 1번 출구에서 출발하면 신도림역까지 17분 걷게 함).
- `build_otp_graph.sh --station-pathways` 는 이 패치가 들어간 jar 만 받는다(파일 이름에 `entrance-relink`).

## 0002-link-around-avoided-edges.patch (0001 위에)
- 무엇: 지도에서 길을 만들 때 다리·터널·`layer`·실내, 서비스 도로(주차장 통로·진입로), 버스전용로, 자전거길, 계단, 건물 통로를 "피할 길"로 표시한다(그래프에 저장하지 않는 빌드 중 표시, 길이 쪼개져도 이어진다). 섬 정리가 끝난 뒤, 연결이 전부 피할 길에만 닿은 정류장·출입구에 25m 안의 가장 가까운 보통 길을 하나 더 잇는다(`-Dotp.linker.avoidedEdgeBandMeters`, 기본 25).
- 왜: OTP 는 정류장을 가장 가까운 길 하나에만 붙이고 층을 보지 않는다. 고가도로·주차장 통로·버스전용로에 붙으면 바로 옆 보도로 못 가고 돈다(한강진 블루스퀘어 정류장 → 한강진역 0.35km 를 1.4km, #620).
- 왜 섬 정리 **뒤**인가: 정리 전에 여러 길에 붙이면 정류장이 작은 조각과 큰 길을 이어 정리 대상이 살아남는다(실험: 정류장 있는 조각 정리 1,004 → 95, 오류동역 승강장 면이 남아 걷기 4 → 14분). 뒤에 붙이면 정리 결과는 운영과 같다(1,004, 걷기 섬 8,493).
- 실측(2026-11-18, 4차 그래프와 같은 입력): 정류장 12,732곳·출입구 1,068곳에 연결 추가, 그래프 927 → 931MB, 힙 약 +66MB. 역 150~400m 정류장 4,096쌍 중 2분 넘게 짧아짐 49·3분 넘게 길어짐 0, 150m 안 2,669쌍 중 짧아짐 16·길어짐 0. 카카오 걷기로 확인한 241쌍 중 카카오보다 5분 넘게 긴 쌍 64 → 44. 역 이름 검색 80 OD 는 전부 ±1분.
- jar 이름에 `entrance-relink` 가 들어가야 `--station-pathways` 가 받는다(예: `otp-h-only-9dbfb229-entrance-relink-avoid25.jar`).

## jar 만드는 법
```
git clone https://github.com/minsuk330/OpenTripPlanner.git && cd OpenTripPlanner
git checkout 9dbfb229b8
git apply <이 저장소>/tools/transit-routing-benchmark/otp-patches/0001-relink-entrances-after-island-pruning.patch
git apply <이 저장소>/tools/transit-routing-benchmark/otp-patches/0002-link-around-avoided-edges.patch
JAVA_HOME=<JDK 25> mvn -q package -DskipTests -P prettierSkip -pl otp-shaded -am
cp otp-shaded/target/otp-shaded-2.11.0-SNAPSHOT.jar <benchmark>/data/input/otp-h-only-9dbfb229-entrance-relink.jar
shasum -a 256 <그 jar>     # build_otp_graph.sh 에 OTP_JAR_SHA256 로 넘긴다
```
