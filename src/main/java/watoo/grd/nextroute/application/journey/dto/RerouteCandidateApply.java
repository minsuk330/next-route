package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;

/**
 * 후보를 실제로 적용할 때 필요한 재료. 화면에는 나가지 않는다.
 *
 * <h2>이름이 계약이다</h2>
 * JSON 에 종류를 적는 칸이 필요한데, Jackson 기본 동작에 맡기면 <b>클래스 이름이 그대로 JSON 에
 * 박힌다.</b> 그러면 클래스를 옮기거나 이름을 바꾸는 리팩터링 한 번이 이미 저장된 행을 못 읽게
 * 만든다. 그래서 {@code @JsonSubTypes} 의 {@code name} 을 손으로 고정한다.
 *
 * <p>그 값은 {@link watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind} 및 V42 의
 * {@code kind} check 제약과 <b>같은 문자열</b>이다. 세 곳이 다른 이름을 쓰면 어느 것이 진짜인지
 * 아무도 모른다.
 *
 * <p>모르는 {@code type} 은 역직렬화에서 거절된다(Jackson 기본). 조용히 null 로 만들면 수락
 * 경로가 빈 후보를 적용하려 든다. <b>폐기된 {@code ROUTE_OPTION}(③) 으로 저장된 옛 행이 정확히
 * 이 경우다</b>(journey-reroute-nextstop PR-5) — 그 후보는 "없는 것"으로 거절되고, 제안은 TTL 로
 * 사라진다. 옛 행을 읽다가 엉뚱한 뜻으로 적용하는 것보다 낫다.
 *
 * <h2>왜 아직 sealed 인가</h2>
 * 지금은 구현이 하나뿐이다. 그래도 봉투를 유지하는 이유는 저장된 JSON 에 {@code type} 칸이 이미
 * 박혀 있어서다 — 봉투를 걷으면 그 칸을 모르는 필드로 만나 옛 행이 통째로 안 읽힌다. 종류를 다시
 * 늘리는 일은 V42 check 제약·{@code RerouteCandidateProvider.kind()}·수락 로직이 함께 바뀌는
 * 호환성 변경이라, sealed 가 그 변경을 <b>컴파일 시점에</b> 드러내는 편이 낫다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RerouteCandidateApply.RemainingPathApply.class,
                name = "REMAINING_PATH")
})
public sealed interface RerouteCandidateApply {

    /**
     * 이 단계부터 교체한다. 그 앞은 완료·진행 구간이라 보존된다.
     *
     * <p>수락 재확인이 "이미 지나온 자리인가"를 이 값으로 본다.
     */
    int fromLegIndex();

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
     * (record 에 칸을 더하는 하위호환 방식 — 새 하위타입을 만들지 않는다).
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
