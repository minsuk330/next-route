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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 검색 결과 경로 하나를 여정 단계로 쪼갠다.
 *
 * <p>쪼개는 기준은 앱의 GPS progress engine이 판정할 수 있는 행동 경계다. 같은 교통수단 구간도
 * 대기와 탑승은 ETA 소스와 전이 조건이 다르므로 별도 leg로 만든다.
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
        return compileDetailed(path).plan();
    }

    /**
     * 쪼개기 결과와 함께 "각 단계가 원본 몇 번째 구간에서 나왔는지"를 돌려준다.
     *
     * <p>지도·GPS 투영에 쓸 좌표는 원본 구간이 들고 있는데, 저장된 단계에는 그 순번이 없다.
     * 좌표를 찾을 때마다 이름·좌표로 거꾸로 맞히는 것보다 같은 쪼개기를 한 번 더 돌려 순번을
     * 받는 편이 어긋날 여지가 없다. 쪼개기는 같은 입력에 같은 결과를 준다.
     */
    public Compiled compileDetailed(PathResult path) {
        if (path == null || path.subPaths() == null || path.subPaths().isEmpty()) {
            return new Compiled(new JourneyPlan(List.of(), List.of()), List.of());
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

        return new Compiled(
                new JourneyPlan(withInitialStates(legs), buildTransfers(emitted)),
                subPathIndexByLegIndex(legs.size(), emitted));
    }

    /** 단계 순번 → 원본 구간 순번. 단계를 만들지 않은 걷기는 어느 칸도 차지하지 않는다. */
    private List<Integer> subPathIndexByLegIndex(int legCount, List<Emitted> emitted) {
        Integer[] byLeg = new Integer[legCount];
        for (Emitted e : emitted) {
            assign(byLeg, e.waitLeg(), e.subPathIndex());
            assign(byLeg, e.rideLeg(), e.subPathIndex());
            assign(byLeg, e.walkLeg(), e.subPathIndex());
        }
        // null 을 담을 수 있어야 해서 List.of 를 쓰지 않는다. 원본을 잃은 단계가 null 이다.
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(byLeg)));
    }

    private void assign(Integer[] byLeg, Integer legIndex, int subPathIndex) {
        if (legIndex != null && legIndex < byLeg.length) {
            byLeg[legIndex] = subPathIndex;
        }
    }

    /**
     * 쪼개기 결과와 원본 구간 순번.
     *
     * @param plan                    단계·환승 연결
     * @param subPathIndexByLegIndex  단계 순번으로 찾는 원본 구간 순번
     */
    public record Compiled(JourneyPlan plan, List<Integer> subPathIndexByLegIndex) {

        public Compiled {
            subPathIndexByLegIndex = subPathIndexByLegIndex == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(subPathIndexByLegIndex));
        }

        /** 없는 단계이거나 원본을 잃은 단계면 null. */
        public Integer subPathIndexOf(int legIndex) {
            if (legIndex < 0 || legIndex >= subPathIndexByLegIndex.size()) {
                return null;
            }
            return subPathIndexByLegIndex.get(legIndex);
        }
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
                null,
                null,
                sub.startName(),
                sub.endName(),
                sub.startX(),
                sub.startY(),
                sub.endX(),
                sub.endY(),
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

        String sourceStartStopId = sourceStartStopId(sub);
        String sourceEndStopId = sourceEndStopId(sub);

        int waitIndex = legs.size();
        int rideIndex = waitIndex + 1;

        legs.add(new JourneyLegDraft(
                waitIndex,
                subway ? JourneyLegType.WAIT_SUBWAY : JourneyLegType.WAIT_BUS,
                JourneyLegState.PENDING,
                null,
                null,
                sourceStartStopId,
                sourceStartStopId,
                sub.startName(),
                sub.startName(),
                sub.startX(),
                sub.startY(),
                sub.startX(),
                sub.startY(),
                // 기다리는 시간은 검색 결과에 없다. 실시간 도착 정보가 붙는 Phase 3 의 몫이다.
                null,
                JourneyLeg.EVIDENCE_QUALITY_UNKNOWN,
                options,
                rideIndex));

        legs.add(new JourneyLegDraft(
                rideIndex,
                subway ? JourneyLegType.RIDE_SUBWAY : JourneyLegType.RIDE_BUS,
                JourneyLegState.PENDING,
                null,
                null,
                sourceStartStopId,
                sourceEndStopId,
                sub.startName(),
                sub.endName(),
                sub.startX(),
                sub.startY(),
                sub.endX(),
                sub.endY(),
                sub.sectionTime() * SECONDS_PER_MINUTE,
                JourneyLeg.EVIDENCE_QUALITY_UNKNOWN,
                List.of(),
                waitIndex));

        return Emitted.transit(index, waitIndex, rideIndex);
    }

    /**
     * 노선 후보. 응답이 준 순서를 그대로 보존한다.
     *
     * <p>후보가 여럿이어도 하나로 줄이지 않는다. 첫 lane은 현재 선택 노선이 되고 나머지는
     * 환승 시점 재평가에 쓸 대체 routeOption으로 남는다.
     */
    private List<JourneyRouteOptionDraft> routeOptions(SubPathResult sub) {
        if (sub.lanes() == null || sub.lanes().isEmpty()) {
            return List.of();
        }
        List<JourneyRouteOptionDraft> options = new ArrayList<>(sub.lanes().size());
        for (int i = 0; i < sub.lanes().size(); i++) {
            LaneResult lane = sub.lanes().get(i);
            options.add(JourneyRouteOptionDraft.of(
                    i, sourceRouteId(lane), routeName(lane), sub.wayCode()));
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

    // ── 식별자 ───────────────────────────────────────────────────────────────

    /** ODSAY가 준 출발 ID. 내부 ID와 같은지 보장하지 않으므로 Resolver가 나중에 해석한다. */
    private String sourceStartStopId(SubPathResult sub) {
        if (sub.trafficType() == TRAFFIC_BUS && hasText(sub.startLocalStationID())) {
            return sub.startLocalStationID();
        }
        if (sub.trafficType() == TRAFFIC_SUBWAY && sub.startId() != null) {
            return String.valueOf(sub.startId());
        }
        return stationId(sub, true);
    }

    /** ODSAY가 준 도착 ID. 내부 ID와 같은지 보장하지 않으므로 Resolver가 나중에 해석한다. */
    private String sourceEndStopId(SubPathResult sub) {
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
        return new StopRef(sourceStartStopId(sub), sub.startArsID(), sub.startName(),
                sub.startX(), sub.startY());
    }

    private StopRef endStopRef(SubPathResult sub) {
        return new StopRef(sourceEndStopId(sub), sub.endArsID(), sub.endName(),
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
                first.startStopId(), first.endStopId(),
                first.sourceStartStopId(), first.sourceEndStopId(),
                first.startName(), first.endName(),
                first.startX(), first.startY(), first.endX(), first.endY(),
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
