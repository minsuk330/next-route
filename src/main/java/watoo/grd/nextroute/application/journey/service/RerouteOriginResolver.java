package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.util.List;
import java.util.Optional;

/**
 * 옛 {@code transferIndex} 가 가리키던 자리를 서울 좌표계 원점({@link RerouteOrigin})으로 푼다
 * (journey-reroute-nextstop PR-B stage3, 이중 쓰기).
 *
 * <p><b>못 풀면 빈 결과다. 예외가 아니다.</b> 그 제안은 옛 주소({@code transfer_index})만 들고
 * 저장되고, 읽는 쪽이 폴백한다 — 전환 기간에 origin 이 없는 행이 있는 것은 정상이다. 반대로
 * 여기서 실패를 올리면 원점을 못 푸는 여정에서 재탐색 자체가 죽는다.
 *
 * <p><b>가짜값을 만들지 않는다.</b> 순환노선처럼 같은 정류장이 한 노선에 두 번 나오면 어느
 * 순번인지 단정할 수 없으므로 {@code seq} 를 비운다. seq 가 비면
 * {@link RerouteOrigin#isRealtimeAddressable()} 이 false 라 그 원점은 실시간 후보가 되지 못하고,
 * 그것이 "애매하면 제안하지 않는다" 라는 확정 규칙과 같은 결론이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerouteOriginResolver {

    private final TransferConnectionRepository transferRepository;
    private final JourneyLegRepository legRepository;
    private final BusRouteStopRepository busRouteStopRepository;

    /**
     * 환승 대기 원점. {@code transfer_connection.toLegIndex} 가 가리키는 <b>탑승 leg 의 승차
     * 정류장</b>이 곧 원점이다.
     *
     * @return 못 풀면 {@link Optional#empty()} — 옛 주소로 계속 도는 정상 경로다
     */
    public Optional<RerouteOrigin> resolveTransferWait(Long journeyId, int routeRevision, int transferIndex) {
        Optional<TransferConnection> transfer = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        journeyId, routeRevision, transferIndex);
        if (transfer.isEmpty()) {
            return Optional.empty();
        }
        int boardingLegIndex = transfer.get().getToLegIndex();

        Optional<JourneyLeg> leg = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(journeyId, routeRevision)
                .stream()
                .filter(candidate -> candidate.getLegIndex() == boardingLegIndex)
                .findFirst();
        if (leg.isEmpty() || leg.get().getStartStopId() == null) {
            return Optional.empty();
        }

        String stopId = leg.get().getStartStopId();
        String routeId = leg.get().getRouteId();
        return Optional.of(new RerouteOrigin(
                boardingLegIndex, stopId, routeId, seqOf(routeId, stopId), RerouteOriginKind.TRANSFER_WAIT));
    }

    /**
     * 그 노선에서 이 정류장이 몇 번째인지. 노선을 모르거나(지하철 등) 표에 없거나 <b>두 번 이상
     * 나오면</b> null — 위 클래스 주석의 "가짜값 금지" 다.
     */
    private Integer seqOf(String routeId, String stopId) {
        if (routeId == null) {
            return null;
        }
        List<BusRouteStop> matches = busRouteStopRepository.findByRouteIdAndStopId(routeId, stopId);
        if (matches.size() != 1) {
            return null;
        }
        return matches.get(0).getSeq();
    }
}
