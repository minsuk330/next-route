package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.SeoulBusIdentity;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.StopPattern;
import watoo.grd.nextroute.application.route.routing.StopPatternPort;
import watoo.grd.nextroute.application.route.routing.StopPatternResult;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대기 leg 의 대안 후보를 <b>OTP pattern 에서 실시간으로</b> 찾는 출처(national-otp P2-F PR-4).
 *
 * <p>저장된 {@code journey_leg_route_option}(여정 시작 때 검색이 준 것)은 시작 시점에 고정되고 원래
 * 검색이 준 노선만 담는다. 같은 정류장에 실제로 서는 다른 버스를 다 못 볼 수 있다. 이 출처는
 * 재탐색 시점에 OTP 에 "이 정류장에서 탈 수 있는 노선이 무엇이냐"를 다시 물어 그 틈을 메운다.
 *
 * <h2>OTP 가 이미 걸러 준다</h2>
 * 쿼리가 {@code omitNonPickups}/{@code omitCanceled} 라 승차 가능·미취소·운행 중인 pattern 만 온다.
 * 승차금지·하차전용·미운행 검증을 여기서 다시 하지 않는다(codex 리뷰 B6 — OTP 에 위임).
 *
 * <h2>남은 판정은 우리 몫</h2>
 * <ol>
 *   <li><b>방향</b>: OTP 는 {@code direction_id} 가 없어(KTDB feed) 상·하행을 정차 순서로만 가른다.
 *       승차 정류장 index &lt; 하차 정류장 index 인 pattern 만 취한다. 승차 정류장이 한 pattern 에
 *       두 번 이상 나오면(순환·지선) 어느 occurrence 의 순번인지 확정할 수 없어 <b>제외</b>한다
 *       (codex B5, "모름&gt;틀림").</li>
 *   <li><b>서울 노선 매핑</b>: OTP route gtfsId → {@link OtpSeoulIdentityResolver#resolveBus}. 스코프
 *       밖(경기·인천 등)이라 매핑 실패면 제외.</li>
 *   <li><b>승차 순번</b>: 서울 {@code bus_route_stop} 에서 {@code (routeId, stopId)} 순번이 <b>정확히
 *       하나</b>일 때만 채운다({@link TransferStopResolver#resolveSeq}, 저장 경로와 동일 규칙 codex B4).
 *       아니면 제외.</li>
 * </ol>
 *
 * <p>후보는 저장 목록에 없는 <b>동적</b> 후보다. {@link JourneyLegRouteOption} 을 저장하지 않고
 * 임시로 빚어 낸다({@code optionIndex = }{@link #DYNAMIC_OPTION_INDEX}). 소비자
 * ({@link RouteOptionCandidateProvider})가 이 표시를 보고 수락 시 새 행으로 넣을 스냅샷을 만든다.
 *
 * <p><b>OTP 장애/timeout 은 예외로 던지지 않는다</b> — {@link SourceOutcome} 상태로 알린다. 그래야
 * 소비자가 "대안 없음"과 "출처 장애"를 갈라 저장값으로 물러설지 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpPatternRouteOptionSource implements RerouteRouteOptionSource {

    /**
     * 저장 목록에 없는 동적 후보 표시. {@code RouteOptionApply.DYNAMIC} 과 같은 뜻(음수)이며,
     * 소비자가 {@code optionIndex < 0} 으로 동적/저장을 가른다. 임시 엔티티는 DB 에 저장되지 않아
     * {@code lane_index >= 0} CHECK 과 무관하다.
     */
    static final int DYNAMIC_OPTION_INDEX = -1;

    private final StopPatternPort stopPatternPort;
    private final OtpSeoulIdentityResolver identityResolver;
    private final TransferStopResolver busResolver;

    @Override
    public SourceOutcome optionsFor(JourneyLeg waitLeg, RoutingDeadline deadline) {
        String boardingGtfsId = waitLeg.getSourceStartStopId();
        String alightingGtfsId = waitLeg.getSourceEndStopId();
        if (isBlank(boardingGtfsId) || isBlank(alightingGtfsId)) {
            // OTP 정류장 id 가 없으면 pattern 을 물어볼 수 없다. 장애가 아니라 조회 불가 — 정상 0건.
            return SourceOutcome.ok(List.of());
        }

        StopPatternResult result = stopPatternPort.patternsThroughStop(boardingGtfsId, deadline);
        switch (result.status()) {
            case TIMEOUT -> {
                return SourceOutcome.timeout();
            }
            case ERROR -> {
                return SourceOutcome.error();
            }
            default -> { /* OK 는 아래에서 처리 */ }
        }

        List<JourneyLegRouteOption> options = new ArrayList<>();
        // 같은 노선이 여러 variant pattern 으로 와도 resolveBus/resolveSeq 를 한 번만 부른다(N+1 완화).
        Set<String> resolvedRoutes = new LinkedHashSet<>();
        for (StopPattern pattern : result.patterns()) {
            if (!servesInOrder(pattern, boardingGtfsId, alightingGtfsId)) {
                continue;
            }
            if (!resolvedRoutes.add(pattern.routeGtfsId())) {
                continue; // 이미 이 노선으로 후보를 만들었거나 제외했다.
            }
            toOption(waitLeg, pattern, boardingGtfsId).ifPresent(options::add);
        }
        return SourceOutcome.ok(options);
    }

    /**
     * 이 pattern 이 승차 정류장에서 하차 정류장 <b>방향으로</b> 실제로 가는가.
     *
     * <p>승차 정류장이 두 번 이상 나오면(순환·지선) 어느 occurrence 의 순번을 서울 DB 순번과
     * 맞출지 확정할 수 없어 제외한다(codex B5). 한 번만 나오면 그 뒤 어딘가에 하차 정류장이
     * 있으면 방향이 맞다.
     */
    private boolean servesInOrder(StopPattern pattern, String boardingGtfsId, String alightingGtfsId) {
        List<String> stops = pattern.stopGtfsIds();
        int boardingIdx = -1;
        int boardingCount = 0;
        for (int i = 0; i < stops.size(); i++) {
            if (boardingGtfsId.equals(stops.get(i))) {
                boardingCount++;
                if (boardingIdx < 0) {
                    boardingIdx = i;
                }
            }
        }
        if (boardingCount != 1) {
            return false;
        }
        for (int i = boardingIdx + 1; i < stops.size(); i++) {
            if (alightingGtfsId.equals(stops.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * pattern 하나를 후보로 빚는다. 서울 노선 매핑·승차 순번 중 하나라도 실패하면 후보에서 뺀다.
     */
    private Optional<JourneyLegRouteOption> toOption(JourneyLeg waitLeg, StopPattern pattern,
                                                     String boardingGtfsId) {
        Optional<SeoulBusIdentity> identity = identityResolver.resolveBus(
                boardingGtfsId, pattern.routeGtfsId(), pattern.routeShortName());
        if (identity.isEmpty()) {
            return Optional.empty(); // 스코프 밖(경기·인천 등) 또는 존재하지 않는 노선/정류장.
        }
        SeoulBusIdentity bus = identity.get();

        TransferStopResolver.SeqResolution seq = busResolver.resolveSeq(bus.routeId(), bus.stopId());
        if (!seq.isSingle()) {
            // 순번이 없거나 여러 개면 저장 경로와 같은 규칙으로 제외한다(codex B4).
            return Optional.empty();
        }

        return Optional.of(JourneyLegRouteOption.builder()
                .journeyLegId(waitLeg.getId())
                .optionIndex(DYNAMIC_OPTION_INDEX)
                .laneIndex(0) // 임시 엔티티라 DB 에 저장되지 않는다 — 대응 lane 이 없다.
                .routeId(bus.routeId())
                .sourceRouteId(pattern.routeGtfsId())
                .routeName(pattern.routeShortName())
                // direction_id 가 없어 방향은 정차 순서로 이미 걸렀다. dedup 안정 키만 남긴다.
                .directionKey(bus.routeId() + "|" + pattern.directionId())
                .wayCode(null) // 버스는 방향 코드가 없다(지하철 전용).
                .boardingSeq(seq.candidates().get(0))
                .alightingSeq(null) // 재탐색 평가는 boardingSeq 만 쓴다. 하차 순번은 후속.
                .build());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
