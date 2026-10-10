package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 스냅샷에 붙일 지하철 안내를 만든다.
 *
 * <h2>지금 지하철 구간일 때만 한 번 읽는다</h2>
 *
 * <p>단계마다 고정을 조회하면 지연 로딩이 단계 수만큼 질의를 만든다(정확히 N+1 이다). 지금
 * 화면이 지하철이 아니면 아무것도 읽지 않고, 맞으면 <b>그 한 단계</b>만 읽는다.
 *
 * <h2>기다리는 단계에서도 안내가 필요하다</h2>
 *
 * <p>고정은 <b>타는 단계</b>가 소유하지만 사용자는 승강장에서부터 화면을 본다. 지금 단계가
 * {@code WAIT_SUBWAY} 면 짝 {@code RIDE_SUBWAY} 의 고정을 메모리에서 찾아 붙인다 — 질의를 더
 * 만들지 않는다.
 *
 * <h2>열차가 정해지면 링을 싣는다 — 잠정도</h2>
 *
 * <p>{@code PROVISIONAL}·{@code CONFIRMED} 면 링을 싣는다(2026-09-29 결정, 이전에는 확정만).
 * 후보가 하나뿐이면 판정기가 확정하지 않아 확정만 그리면 한산한 시간대에 링이 안 뜬다.
 * {@code bindingStatus} 는 사실대로 {@code PROVISIONAL} 을 내리고, 앱이 확정과 <b>구분 없이</b>
 * 그린다 — "추정/확인 중" 표시를 하지 않기로 했다(모바일 HANDOFF).
 *
 * <p>역에서 고르고 아직 안 움직인 고정({@code USER_SELECTED})도 링을 싣는다(#304) — 사용자가 직접 고른
 * 열차라 이동 관측을 확인 근거로 기다리지 않는다. 열차가 아직 승차역 전이면 링의 다음 역이 승차역이다.
 *
 * <p>{@code PENDING} 에서는 여전히 다음 역과 진행률을 비운다 — 고른 열차가 없다. 열차는
 * 정해졌는데 링이 없으면(캐시 유실·갱신기 꺼짐) 상태만 두고 값은 비운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneySubwayGuidanceComposer {

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayRingPort ringPort;

    /**
     * @param legs           그 개정본의 단계 전부(순번 오름차순)
     * @param optionsByLeg   이미 읽어 둔 노선 후보. 노선 이름을 여기서 얻는다
     * @return 지하철 구간이 아니거나, 실시간 없는 지하철이거나, 고정이 없으면 {@code null}
     */
    public SubwayGuidance compose(int routeRevision, int currentLegIndex, List<JourneyLeg> legs,
                                  Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        JourneyLeg ride = rideLegOf(currentLegIndex, legs);
        if (ride == null || !ride.hasSubwayRealtimeLine()) {
            // 실시간 없는 지하철(지방)은 판정이 열리지 않는다 — 옛 판정 행이 남아 있어도 "확인 중"을 그리지 않는다.
            return null;
        }
        SubwayLegBinding binding = bindingRepository
                .findByJourneyLegIdIn(List.of(ride.getId())).stream()
                .findFirst()
                .orElse(null);
        if (binding == null) {
            // 아직 이동 시작 관측이 오지 않았다. 기존 필드만으로 그리던 화면 그대로다.
            return null;
        }
        String lineName = lineNameOf(currentLegIndex, legs, optionsByLeg);
        if (!binding.getStatus().isBound()) {
            return SubwayGuidance.pending(binding.getStatus(), lineName, ride.getEndName());
        }
        return ringPort.find(binding.getJourneyId(), routeRevision, binding.getLegIndex(),
                        binding.getVersion())
                .filter(ring -> ring.matches(routeRevision, binding.getLegIndex(),
                        binding.getVersion()))
                .map(ring -> confirmed(binding, lineName, ring))
                .orElseGet(() -> SubwayGuidance.pending(binding.getStatus(), lineName,
                        ride.getEndName()));
    }

    private SubwayGuidance confirmed(SubwayLegBinding binding, String lineName,
                                     SubwayRingState ring) {
        return SubwayGuidance.of(binding.getStatus(), lineName, ring);
    }

    /**
     * 지금 화면이 가리키는 지하철 <b>타는</b> 단계.
     *
     * <p>기다리는 단계면 바로 뒤가 짝이다 — 검색 결과 하나가 대기·탑승 두 단계로 쪼개지고 그
     * 순서는 컴파일러가 보장한다.
     */
    private JourneyLeg rideLegOf(int currentLegIndex, List<JourneyLeg> legs) {
        JourneyLeg current = legAt(legs, currentLegIndex);
        if (current == null) {
            return null;
        }
        if (current.getType() == JourneyLegType.RIDE_SUBWAY) {
            return current;
        }
        if (current.getType() != JourneyLegType.WAIT_SUBWAY) {
            return null;
        }
        JourneyLeg next = legAt(legs, currentLegIndex + 1);
        return next != null && next.getType() == JourneyLegType.RIDE_SUBWAY ? next : null;
    }

    /**
     * 노선 이름의 원천은 선택된 노선 후보다.
     *
     * <p>후보는 <b>기다리는 단계</b>가 소유하므로 타는 단계에서 시작하면 한 칸 앞도 본다.
     */
    private String lineNameOf(int currentLegIndex, List<JourneyLeg> legs,
                              Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        return selectedNameOf(legAt(legs, currentLegIndex), optionsByLeg)
                .or(() -> selectedNameOf(legAt(legs, currentLegIndex - 1), optionsByLeg))
                .or(() -> selectedNameOf(legAt(legs, currentLegIndex + 1), optionsByLeg))
                .orElse(null);
    }

    private Optional<String> selectedNameOf(JourneyLeg leg,
                                            Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        if (leg == null || leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return optionsByLeg.getOrDefault(leg.getId(), List.of()).stream()
                .filter(option -> leg.getSelectedRouteOptionId().equals(option.getId()))
                .map(JourneyLegRouteOption::getRouteName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst();
    }

    private JourneyLeg legAt(List<JourneyLeg> legs, int index) {
        if (legs == null || index < 0 || index >= legs.size()) {
            return null;
        }
        JourneyLeg leg = legs.get(index);
        // legIndex 가 목록 위치와 어긋난 경우를 방어한다. 어긋나면 아무 단계도 고르지 않는다.
        return leg != null && leg.getLegIndex() == index ? leg : null;
    }
}
