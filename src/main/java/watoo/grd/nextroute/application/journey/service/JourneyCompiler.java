package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.journey.service.StopIdentityResolver.StopRef;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.ArrayList;
import java.util.List;

/**
 * 검색 결과 경로 하나를 여정 단계로 쪼갠다.
 *
 * <p>쪼개는 기준은 <b>사용자가 버튼을 누르는 지점</b>이다. 검색 결과의 "370번 버스" 한 구간을
 * 그대로 한 단계로 두면, "탔어요"를 누른 뒤에도 같은 단계에 머물러 한 단계가 "아직 안 탔음"과
 * "타고 있음" 두 가지 뜻을 갖는다. 그래서 교통수단 구간은 기다리기와 타고 가기로 나눈다.
 *
 * <p>이 계산기는 아무것도 조회하지 않고 아무것도 저장하지 않는다. 입력 {@link PathResult} 를
 * 고치지도 않는다. 결과를 엔티티로 옮기는 일은 여정 시작 쪽이 맡는다.
 */
@Component
@RequiredArgsConstructor
public class JourneyCompiler {

    private static final int TRAFFIC_SUBWAY = 1;
    private static final int TRAFFIC_BUS = 2;
    private static final int TRAFFIC_WALK = 3;

    /** 검색 응답의 구간 소요시간은 분 단위다. */
    private static final int SECONDS_PER_MINUTE = 60;

    private final StopIdentityResolver stopIdentityResolver;

    public JourneyPlan compile(PathResult path) {
        if (path == null || path.subPaths() == null || path.subPaths().isEmpty()) {
            return new JourneyPlan(List.of(), List.of());
        }
        List<SubPathResult> subPaths = path.subPaths();

        List<JourneyLegDraft> legs = new ArrayList<>();
        List<Emitted> emitted = new ArrayList<>();

        for (int i = 0; i < subPaths.size(); i++) {
            SubPathResult sub = subPaths.get(i);
            if (isWalk(sub)) {
                emitted.add(emitWalk(legs, subPaths, i, sub));
            } else {
                emitted.add(emitTransit(legs, i, sub));
            }
        }

        return new JourneyPlan(withInitialStates(legs), buildTransfers(emitted));
    }

    // ── 걷기 ────────────────────────────────────────────────────────────────

    private Emitted emitWalk(List<JourneyLegDraft> legs, List<SubPathResult> subPaths,
                             int index, SubPathResult sub) {
        boolean betweenTransits = hasTransitBefore(subPaths, index) && hasTransitAfter(subPaths, index);

        // 걷지 않는 환승을 0초짜리 걷기 단계로 만들지 않는다. 사용자가 지나가지도 않을 단계가
        // 화면에 뜨기 때문이다. 근거가 확실할 때만("같은 정류장"이라고 판별될 때만) 지운다.
        if (betweenTransits && isZeroLength(sub) && sameStopAround(subPaths, index)) {
            return Emitted.skippedWalk(index);
        }

        legs.add(new JourneyLegDraft(
                legs.size(),
                betweenTransits ? JourneyLegType.TRANSFER_WALK : JourneyLegType.WALK,
                JourneyLegState.PENDING,
                null,
                null,
                sub.startName(),
                sub.endName(),
                walkDurationSec(sub),
                null,
                List.of(),
                null));
        return Emitted.walk(index, legs.size() - 1);
    }

    /**
     * 걷기 소요시간. 실측이 있으면 그것을, 없으면 검색 결과의 분 단위 값을 쓴다.
     *
     * <p>도보 구간은 TMAP 실측으로 보강되는 경우가 있어 두 값이 공존한다. 실측이 더 정확하다.
     */
    private Integer walkDurationSec(SubPathResult sub) {
        if (sub.walkTotalTimeSeconds() != null) {
            return sub.walkTotalTimeSeconds();
        }
        return sub.sectionTime() * SECONDS_PER_MINUTE;
    }

    private boolean isZeroLength(SubPathResult sub) {
        boolean noMinutes = sub.sectionTime() == 0;
        boolean noSeconds = sub.walkTotalTimeSeconds() == null || sub.walkTotalTimeSeconds() == 0;
        boolean noDistance = sub.distance() == null || sub.distance() <= 0;
        return noMinutes && noSeconds && noDistance;
    }

    /** 걷기 앞뒤 교통수단이 같은 정류장인지. 모르겠으면 같다고 하지 않는다. */
    private boolean sameStopAround(List<SubPathResult> subPaths, int walkIndex) {
        SubPathResult before = lastTransitBefore(subPaths, walkIndex);
        SubPathResult after = firstTransitAfter(subPaths, walkIndex);
        if (before == null || after == null) {
            return false;
        }
        return stopIdentityResolver.resolve(endStopRef(before), startStopRef(after))
                == StopIdentityResolver.Verdict.SAME;
    }

    // ── 교통수단 ─────────────────────────────────────────────────────────────

    /** 교통수단 한 구간을 기다리기 + 타고 가기 두 단계로 만든다. */
    private Emitted emitTransit(List<JourneyLegDraft> legs, int index, SubPathResult sub) {
        boolean subway = sub.trafficType() == TRAFFIC_SUBWAY;
        List<JourneyRouteOptionDraft> options = routeOptions(sub);

        String startStopId = startStopId(sub);
        String endStopId = endStopId(sub);
        String evidenceQuality = evidenceQuality(startStopId, endStopId, options);

        int waitIndex = legs.size();
        int rideIndex = waitIndex + 1;

        legs.add(new JourneyLegDraft(
                waitIndex,
                subway ? JourneyLegType.WAIT_SUBWAY : JourneyLegType.WAIT_BUS,
                JourneyLegState.PENDING,
                startStopId,
                startStopId,
                sub.startName(),
                sub.startName(),
                // 기다리는 시간은 검색 결과에 없다. 실시간 도착 정보가 붙는 Phase 3 의 몫이다.
                null,
                evidenceQuality,
                options,
                rideIndex));

        legs.add(new JourneyLegDraft(
                rideIndex,
                subway ? JourneyLegType.RIDE_SUBWAY : JourneyLegType.RIDE_BUS,
                JourneyLegState.PENDING,
                startStopId,
                endStopId,
                sub.startName(),
                sub.endName(),
                sub.sectionTime() * SECONDS_PER_MINUTE,
                evidenceQuality,
                List.of(),
                waitIndex));

        return Emitted.transit(index, waitIndex, rideIndex);
    }

    /**
     * 노선 후보. 응답이 준 순서를 그대로 보존한다.
     *
     * <p>후보가 여럿이어도 하나로 줄이지 않는다. 같은 정류장에서 목적지로 가는 버스가 둘이면
     * 사용자는 먼저 오는 것을 탄다 — 서버가 미리 하나를 고르면 실제와 어긋난다.
     */
    private List<JourneyRouteOptionDraft> routeOptions(SubPathResult sub) {
        if (sub.lanes() == null || sub.lanes().isEmpty()) {
            return List.of();
        }
        List<JourneyRouteOptionDraft> options = new ArrayList<>(sub.lanes().size());
        for (int i = 0; i < sub.lanes().size(); i++) {
            LaneResult lane = sub.lanes().get(i);
            options.add(JourneyRouteOptionDraft.of(i, sourceRouteId(lane), routeName(lane)));
        }
        return options;
    }

    private String sourceRouteId(LaneResult lane) {
        if (hasText(lane.busLocalBlID())) {
            return lane.busLocalBlID();
        }
        if (lane.busID() != null) {
            return String.valueOf(lane.busID());
        }
        if (lane.subwayCode() != null) {
            return String.valueOf(lane.subwayCode());
        }
        return null;
    }

    private String routeName(LaneResult lane) {
        return hasText(lane.busNo()) ? lane.busNo() : lane.name();
    }

    /**
     * 근거 품질. 실시간 대조에 쓸 식별자가 없으면 "모름"으로 표시한다.
     *
     * <p>식별자가 없다고 단계 상태를 {@code UNKNOWN} 으로 만들지 않는다. 그러면 사용자가 손으로
     * 진행할 수도 없다. 진행은 그대로 두고 "서버가 자동으로 따라가지는 못한다"만 남긴다.
     */
    private String evidenceQuality(String startStopId, String endStopId,
                                   List<JourneyRouteOptionDraft> options) {
        boolean stopsKnown = hasText(startStopId) && hasText(endStopId);
        boolean routeKnown = options.stream().anyMatch(JourneyRouteOptionDraft::hasIdentifier);
        return stopsKnown && routeKnown ? null : JourneyLeg.EVIDENCE_QUALITY_UNKNOWN;
    }

    // ── 식별자 ───────────────────────────────────────────────────────────────

    private String startStopId(SubPathResult sub) {
        if (sub.trafficType() == TRAFFIC_BUS && hasText(sub.startLocalStationID())) {
            return sub.startLocalStationID();
        }
        if (sub.trafficType() == TRAFFIC_SUBWAY && sub.startId() != null) {
            return String.valueOf(sub.startId());
        }
        return stationId(sub, true);
    }

    private String endStopId(SubPathResult sub) {
        if (sub.trafficType() == TRAFFIC_BUS && hasText(sub.endLocalStationID())) {
            return sub.endLocalStationID();
        }
        if (sub.trafficType() == TRAFFIC_SUBWAY && sub.endID() != null) {
            return String.valueOf(sub.endID());
        }
        return stationId(sub, false);
    }

    /** 구간 식별자가 없으면 정류장 목록의 처음·끝에서 가져온다. */
    private String stationId(SubPathResult sub, boolean first) {
        List<StationResult> stations = sub.stations();
        if (stations == null || stations.isEmpty()) {
            return null;
        }
        StationResult station = first ? stations.get(0) : stations.get(stations.size() - 1);
        return hasText(station.stationID()) ? station.stationID() : null;
    }

    private StopRef startStopRef(SubPathResult sub) {
        return new StopRef(startStopId(sub), sub.startArsID(), sub.startName(),
                sub.startX(), sub.startY());
    }

    private StopRef endStopRef(SubPathResult sub) {
        return new StopRef(endStopId(sub), sub.endArsID(), sub.endName(),
                sub.endX(), sub.endY());
    }

    // ── 상태와 환승 ──────────────────────────────────────────────────────────

    /** 첫 단계만 진행 중이다. 나머지는 차례를 기다린다. */
    private List<JourneyLegDraft> withInitialStates(List<JourneyLegDraft> legs) {
        if (legs.isEmpty()) {
            return legs;
        }
        JourneyLegDraft first = legs.get(0);
        legs.set(0, new JourneyLegDraft(
                first.legIndex(), first.type(), JourneyLegState.ACTIVE,
                first.startStopId(), first.endStopId(), first.startName(), first.endName(),
                first.plannedDurationSec(), first.evidenceQuality(),
                first.routeOptions(), first.pairedLegIndex()));
        return legs;
    }

    private List<TransferConnectionDraft> buildTransfers(List<Emitted> emitted) {
        List<Emitted> transits = emitted.stream().filter(Emitted::isTransit).toList();
        if (transits.size() < 2) {
            return List.of();
        }
        List<TransferConnectionDraft> transfers = new ArrayList<>(transits.size() - 1);
        for (int i = 0; i + 1 < transits.size(); i++) {
            Emitted from = transits.get(i);
            Emitted to = transits.get(i + 1);
            transfers.add(new TransferConnectionDraft(
                    i,
                    from.rideLeg(),
                    walkLegBetween(emitted, from.subPathIndex(), to.subPathIndex()),
                    to.waitLeg()));
        }
        return transfers;
    }

    private Integer walkLegBetween(List<Emitted> emitted, int fromSubPath, int toSubPath) {
        return emitted.stream()
                .filter(e -> e.subPathIndex() > fromSubPath && e.subPathIndex() < toSubPath)
                .map(Emitted::walkLeg)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ── 보조 ─────────────────────────────────────────────────────────────────

    private boolean isWalk(SubPathResult sub) {
        return sub.trafficType() == TRAFFIC_WALK;
    }

    private boolean hasTransitBefore(List<SubPathResult> subPaths, int index) {
        return lastTransitBefore(subPaths, index) != null;
    }

    private boolean hasTransitAfter(List<SubPathResult> subPaths, int index) {
        return firstTransitAfter(subPaths, index) != null;
    }

    private SubPathResult lastTransitBefore(List<SubPathResult> subPaths, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!isWalk(subPaths.get(i))) {
                return subPaths.get(i);
            }
        }
        return null;
    }

    private SubPathResult firstTransitAfter(List<SubPathResult> subPaths, int index) {
        for (int i = index + 1; i < subPaths.size(); i++) {
            if (!isWalk(subPaths.get(i))) {
                return subPaths.get(i);
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 검색 구간 하나가 어떤 단계들을 만들었는지. 환승을 이을 때 쓴다. */
    private record Emitted(int subPathIndex, Integer waitLeg, Integer rideLeg, Integer walkLeg) {

        static Emitted transit(int subPathIndex, int waitLeg, int rideLeg) {
            return new Emitted(subPathIndex, waitLeg, rideLeg, null);
        }

        static Emitted walk(int subPathIndex, int walkLeg) {
            return new Emitted(subPathIndex, null, null, walkLeg);
        }

        /** 단계를 만들지 않은 걷기(같은 정류장 환승). 환승 연결의 walkLegIndex 는 null 이 된다. */
        static Emitted skippedWalk(int subPathIndex) {
            return new Emitted(subPathIndex, null, null, null);
        }

        boolean isTransit() {
            return waitLeg != null;
        }
    }
}
