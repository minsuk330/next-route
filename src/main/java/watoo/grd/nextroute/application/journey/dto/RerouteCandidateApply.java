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
     * @param waitLegIndex     어느 {@code WAIT_*} leg 인지
     * @param routeOptionIndex 그 leg 의 몇 번째 후보인지
     */
    record RouteOptionApply(int waitLegIndex, int routeOptionIndex,
                            String routeId, String directionKey) implements RerouteCandidateApply {
    }

    /**
     * 남은 구간을 다른 경로로 갈아끼운다.
     *
     * @param fromLegIndex 이 leg 부터 교체한다. 그 앞은 완료·진행 구간이라 보존된다
     * @param path         경로 원본. 수락 경로가 {@code JourneyCompiler.compile(path)} 로 leg 를
     *                     만든다. <b>화면 요약만 저장하면 여기 쓸 재료가 없다</b>
     */
    record RemainingPathApply(int fromLegIndex, PathResult path) implements RerouteCandidateApply {
    }
}
