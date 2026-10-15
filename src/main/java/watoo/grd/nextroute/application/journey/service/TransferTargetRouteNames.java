package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.util.List;
import java.util.Optional;

/**
 * 환승 위험 알림 문구에 넣을 "갈아탈 차" 노선 이름(#428 PR-2).
 *
 * <p>갈아탈 차는 판정의 {@code toLegIndex} — 기다리는 단계다. 노선 후보는 그 단계가 소유하고, 도착 예측기
 * ({@code BusTransferTargetArrivalPredictor}·{@code SubwayTransferTargetArrivalPredictor})도 <b>고른 후보</b>
 * ({@code selectedRouteOptionId})로 도착 시각을 잰다. 문구의 노선과 문구의 "N분 뒤"가 같은 차를 가리키게
 * 하려고 같은 후보를 읽는다.
 *
 * <p>고른 후보가 없으면 후보가 <b>하나뿐일 때만</b> 그 이름을 쓴다. 후보가 여럿인데 아무거나 고르면 "146번이
 * 3분 뒤 와요"가 사실은 다른 버스의 시각일 수 있다 — 그럴 바엔 이름 없이 기존 문구가 낫다.
 *
 * <p>모르면 {@link Optional#empty()}. 알림을 막을 이유가 아니라 문구가 밋밋해질 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class TransferTargetRouteNames {

    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;

    public Optional<String> of(long journeyId, int routeRevision, int toLegIndex) {
        Optional<JourneyLeg> leg = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(journeyId, routeRevision)
                .stream()
                .filter(l -> l.getLegIndex() == toLegIndex)
                .findFirst();
        if (leg.isEmpty() || leg.get().getId() == null) {
            return Optional.empty();
        }
        List<JourneyLegRouteOption> options =
                routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(List.of(leg.get().getId()));
        Long selected = leg.get().getSelectedRouteOptionId();
        Optional<JourneyLegRouteOption> chosen = selected != null
                ? options.stream().filter(o -> selected.equals(o.getId())).findFirst()
                : (options.size() == 1 ? Optional.of(options.get(0)) : Optional.empty());
        return chosen.map(JourneyLegRouteOption::getRouteName)
                .map(String::strip)
                .filter(name -> !name.isEmpty());
    }
}
