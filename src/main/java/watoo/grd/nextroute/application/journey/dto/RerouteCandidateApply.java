package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;

/**
 * 후보를 실제로 적용할 때 필요한 재료. 화면에는 나가지 않는다.
 *
 * <h2>이름이 계약이다</h2>
 * 종류가 둘이라 JSON 에 종류를 적는 칸이 필요한데, Jackson 기본 동작에 맡기면 <b>클래스 이름이
 * 그대로 JSON 에 박힌다.</b> 그러면 클래스를 옮기거나 이름을 바꾸는 리팩터링 한 번이 이미 저장된
 * 행을 못 읽게 만든다. 그래서 {@code @JsonSubTypes} 의 {@code name} 을 손으로 고정한다.
 *
 * <p>그 값은 {@link watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind} 및 V42 의
 * {@code kind} check 제약과 <b>같은 문자열</b>이다. 세 곳이 다른 이름을 쓰면 어느 것이 진짜인지
 * 아무도 모른다.
 *
 * <p>모르는 {@code type} 은 역직렬화에서 거절된다(Jackson 기본). 조용히 null 로 만들면 수락
 * 경로가 빈 후보를 적용하려 든다.
 *
 * <h2>왜 sealed 인가</h2>
 * 종류를 늘리려면 어차피 V42 의 check 제약·{@code RerouteCandidateProvider.kind()}·수락 로직을
 * 함께 고쳐야 하는 호환성 변경이다. sealed 가 그 변경을 <b>컴파일 시점에</b> 드러내는 편이 낫다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RerouteCandidateApply.RouteOptionApply.class,
                name = "ROUTE_OPTION"),
        @JsonSubTypes.Type(value = RerouteCandidateApply.RemainingPathApply.class,
                name = "REMAINING_PATH")
})
public sealed interface RerouteCandidateApply {

    /**
     * 같은 승·하차 구간에서 다른 노선을 고른다. leg 구조는 그대로다.
     *
     * <h3>저장 후보 vs 동적 후보</h3>
     * 후보에는 두 출처가 있다. journey 시작 때 저장해 둔 {@code journey_leg_route_option}(대부분)과,
     * 재탐색이 <b>실시간으로 새로 찾아낸</b> 후보(national-otp P2-F PR4b 의 OTP pattern)다. 저장 후보는
     * {@code routeOptionIndex}(그 leg 의 몇 번째)로 특정된다. 동적 후보는 저장 목록에 없어 그 번호로
     * 못 찾으므로, {@code routeOptionIndex} 를 {@link #DYNAMIC} 로 두고 <b>스냅샷 필드로 새 행을 만들어</b>
     * 선택한다({@link RerouteCandidateApply}의 "적용 재료" 계약 — {@code RemainingPathApply} 가 경로 원본을
     * 통째로 담는 것과 같은 이유). 스냅샷은 저장 시점의 값이므로 수락 시점에 재조회하지 않는다.
     *
     * @param waitLegIndex     어느 {@code WAIT_*} leg 인지
     * @param routeOptionIndex 저장 후보면 그 leg 의 몇 번째, 동적 후보면 {@link #DYNAMIC}(음수)
     * @param routeId          서울 내부 노선 id (선택·매칭 키)
     * @param directionKey     진행 순서 그룹 키
     * @param sourceRouteId    동적 후보 insert 용 스냅샷 (저장 후보면 무시)
     * @param routeName        〃
     * @param wayCode          〃 (버스 null)
     * @param boardingSeq      〃 (내부 노선 승차 순번). 동적 후보 판별은 {@link #isDynamic()}
     *                         ({@code routeOptionIndex < 0}) 이 하고, insert 시 이 값이 채워진다
     * @param alightingSeq     〃
     * @param laneIndex        〃 (원래 검색 lane. 동적 후보는 대응 lane 이 없으므로 {@code null} →
     *                         수락 시 새 option_index 로 채운다. {@code lane_index >= 0} 제약 때문에
     *                         null 을 그대로 저장하지 않는다)
     */
    record RouteOptionApply(int waitLegIndex, int routeOptionIndex, String routeId, String directionKey,
                            String sourceRouteId, String routeName, Integer wayCode, Integer boardingSeq,
                            Integer alightingSeq, Integer laneIndex) implements RerouteCandidateApply {

        /** {@code routeOptionIndex} 가 이 값이면 저장 목록에 없는 동적 후보 — 스냅샷으로 새 행을 만든다. */
        public static final int DYNAMIC = -1;

        /** 저장 후보용 하위호환 생성자(스냅샷 없음). 기존 호출부·옛 저장 JSON 이 그대로 쓴다. */
        public RouteOptionApply(int waitLegIndex, int routeOptionIndex, String routeId, String directionKey) {
            this(waitLegIndex, routeOptionIndex, routeId, directionKey,
                    null, null, null, null, null, null);
        }

        /** 저장 목록에 없어 새 행을 만들어야 하는 동적 후보인가. (JSON 프로퍼티 아님) */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isDynamic() {
            return routeOptionIndex < 0;
        }
    }

    /**
     * 남은 구간을 다른 경로로 갈아끼운다.
     *
     * <h3>origin 이 왜 여기에도 있나</h3>
     * {@code fromLegIndex} 는 <b>leg 경계</b>까지만 가리킨다. 주행 중인 버스의 <b>중간 정류장</b>에서
     * 갈아타는 후보는 "몇 번째 leg 부터"로 표현할 수 없다 — 그 leg 를 통째로 버리는 게 아니라
     * 중간에서 자르기 때문이다(journey-reroute-nextstop PR-B stage4). 수락 경로가 어디서 잘라야
     * 하는지 알려면 후보 자체가 그 지점을 들고 있어야 한다.
     *
     * <p>{@code origin} 은 <b>nullable 이고, 없으면 옛 뜻 그대로</b>({@code fromLegIndex} 부터 교체)다.
     * 이미 저장된 행에는 이 칸이 없으므로 Jackson 이 null 로 읽고, 그것이 정확히 옛 동작이다
     * ({@code RouteOptionApply} 가 스냅샷 필드를 덧붙일 때 쓴 것과 같은 하위호환 방식 — 새 하위타입을
     * 만들지 않는다).
     *
     * @param fromLegIndex 이 leg 부터 교체한다. 그 앞은 완료·진행 구간이라 보존된다
     * @param path         경로 원본. 수락 경로가 {@code JourneyCompiler.compile(path)} 로 leg 를
     *                     만든다. <b>화면 요약만 저장하면 여기 쓸 재료가 없다</b>
     * @param origin       중간 하차 지점(서울 좌표계). null 이면 leg 경계 교체(옛 동작)
     */
    record RemainingPathApply(int fromLegIndex, PathResult path, RerouteOrigin origin)
            implements RerouteCandidateApply {

        /** 옛 호출부·옛 저장 JSON 용(leg 경계 교체). */
        public RemainingPathApply(int fromLegIndex, PathResult path) {
            this(fromLegIndex, path, null);
        }
    }
}
