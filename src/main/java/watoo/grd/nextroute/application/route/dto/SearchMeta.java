package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 응답 완전성 계약(Step 5). 기본 경로는 그대로 두고, 어떤 선택 보강이 생략·실패했는지
 * 클라이언트가 알 수 있도록 하위 호환적으로 노출한다.
 *
 * <p>{@code outcome} 은 이 응답에 실제로 실린 보강 수준이다.
 * <ul>
 *   <li>{@code full} — 이 요청에 적용 가능한 보강이 전부 성공.
 *   <li>{@code partial} — 일부 선택 보강이 생략·실패. 기본 경로는 정상.
 *   <li>{@code base_only} — 적용 가능한 선택 보강이 전부 생략·실패. ODsay 기본 경로만.
 * </ul>
 * {@code failed}(필수 ODsay 실패)는 응답 body 가 아니라 504/502 에러로 나가므로 여기 나타나지 않는다.
 *
 * <p>{@code degraded} 는 값이 비어 있거나 실시간이 아닌 선택 보강 필드의 목록이다.
 * 클라이언트는 이 목록에 있는 component 의 보강 필드(예: 버스 도착정보, 도보 상세)를
 * 정상 최신값으로 오인하면 안 된다. component/reason 은 모두 저카디널리티 고정 문자열이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchMeta(
        String outcome,
        List<DegradedComponent> degraded
) {

    /**
     * @param component 보강 단계 식별자(예: {@code walk}, {@code bus_realtime}, {@code ml}, {@code polyline}).
     * @param reason    생략·실패 사유(예: {@code timeout}, {@code bulkhead_full}, {@code upstream_error}).
     */
    public record DegradedComponent(String component, String reason) {
    }
}
