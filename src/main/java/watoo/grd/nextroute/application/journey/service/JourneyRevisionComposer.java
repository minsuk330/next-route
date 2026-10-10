package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.util.ArrayList;
import java.util.List;

/**
 * 수락된 후보로 <b>새 개정본의 재료</b>를 만든다. DB 를 읽지도 쓰지도 않는다.
 *
 * <h2>순서가 계약이다 — 합성 먼저, 쪼개기 나중</h2>
 * 후보 경로를 홀로 쪼갠 뒤 번호만 미는 방식은 <b>성립하지 않는다.</b> {@link JourneyCompiler} 의
 * 쪼개기는 구간 하나만 보고 결정하지 않는다.
 *
 * <ul>
 *   <li>도보의 종류는 <b>앞뒤에 교통수단이 있는지</b>로 갈린다({@code WALK} vs {@code TRANSFER_WALK})</li>
 *   <li>앞뒤가 같은 정류장이고 길이가 0이면 <b>단계를 아예 만들지 않는다</b> — 뒤 번호가 밀린다</li>
 *   <li>환승 연결은 <b>인접한 두 교통수단 구간</b>을 보고 만들어진다 — 접합부 환승은 후보 안에서
 *       보이지 않는다</li>
 * </ul>
 *
 * <p>그래서 <b>스냅샷을 먼저 합성하고 그 합성본을 다시 쪼갠다.</b> 그러면 "새 스냅샷을 쪼갠 결과가
 * 저장된 단계"라는 계약이 정의상 참이 된다 — 지도(geometry)와 ④의 좌표 찾기가 그 계약 위에 서 있다.
 *
 * <h2>앞 구간이 달라지면 거절한다</h2>
 * 뒤가 바뀌면 <b>앞 구간의 마지막 도보 종류도 바뀔 수 있다</b>(예: 걷기만으로 닿는 후보를 붙이면
 * 그 앞 도보의 "뒤에 교통수단이 있다"가 거짓이 된다). 그대로 저장하면 지나온 길의 종류가 바뀐다.
 * 그런 후보는 {@code snapshot-mismatch} 로 거절한다.
 *
 * <h2>중간 하차 합성</h2>
 * 주행 중인 버스의 <b>중간 정류장</b>에서 갈아타는 후보는 그 구간을 통째로 버리지 않는다. 앞 절반
 * (승차→X)은 실제로 타고 가는 길이라 스냅샷에 남아야 한다 — {@code [앞 구간] + [승차→X 절단] +
 * [후보]}. 자를 자리와 그때까지의 소요는 {@link RideTruncation} 으로 <b>부르는 쪽이</b> 준다
 * (실시간 근거는 이 계산기가 아니라 수락 경로가 들고 있다).
 *
 * <p>절단은 {@link RerouteOriginKind#RIDE_INTERIOR} 원점이 명시된 후보에만 허용한다. 그 명시가
 * 없으면 아래 "구간 한가운데" 거절이 그대로 살아 있어야 한다 — 그 검사가 막는 사고(짝 대기 단계만
 * 앞에 남아 <b>다른 노선</b>을 가리키는 것)를 여기서는 앞 절반을 남겨 두는 방식으로 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyRevisionComposer {

    /** 검색 응답의 구간 소요시간은 분 단위다({@link JourneyCompiler} 와 같은 규칙). */
    private static final int SECONDS_PER_MINUTE = 60;

    /** 중간 하차는 버스 구간만 자른다(초기 스코프). */
    private static final int TRAFFIC_BUS = 2;

    private final JourneyCompiler compiler;
    private final JourneyTransitIdEnricher transitIdEnricher;
    private final JourneyGuidanceInfoEnricher guidanceInfoEnricher;

    /**
     * @param currentSnapshot 지금 개정본의 경로 원본
     * @param currentLegs     지금 개정본의 단계 전부({@code legIndex} 오름차순)
     * @param apply           사용자가 고른 후보의 적용 재료
     * @return 만들 수 없으면 사유가 담긴 실패
     */
    public Result compose(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                          RerouteCandidateApply apply) {
        return compose(currentSnapshot, currentLegs, apply, null);
    }

    /**
     * @param truncation 주행 중인 구간을 중간 정류장에서 자르라는 지시. null 이면 leg 경계 교체
     *                   (옛 동작)
     */
    public Result compose(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                          RerouteCandidateApply apply, RideTruncation truncation) {
        RerouteCandidateApply.RemainingPathApply remaining =
                (RerouteCandidateApply.RemainingPathApply) apply;
        return composeRemainingPath(currentSnapshot, currentLegs, remaining, truncation);
    }

    private Result composeRemainingPath(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                                        RerouteCandidateApply.RemainingPathApply apply,
                                        RideTruncation truncation) {
        int fromLegIndex = apply.fromLegIndex();
        if (currentSnapshot == null || apply.path() == null || apply.path().subPaths() == null) {
            return Result.failed("snapshot-unmappable");
        }

        JourneyCompiler.Compiled current = compiler.compileDetailed(currentSnapshot);
        Integer cut = current.subPathIndexOf(fromLegIndex);
        if (cut == null) {
            // 그 단계가 어느 원본 구간에서 나왔는지 모른다. 자를 자리를 못 찾는다.
            log.warn("[JourneyReroute] 교체 시작 단계의 원본 구간을 못 찾았다 fromLegIndex={}", fromLegIndex);
            return Result.failed("snapshot-unmappable");
        }

        boolean insideTransit = fromLegIndex > 0 && cut.equals(current.subPathIndexOf(fromLegIndex - 1));
        if (truncation == null) {
            if (insideTransit) {
                // 교체 시작점이 원본 구간의 <b>한가운데</b>다. 교통수단 한 구간은 대기·주행 두 단계가
                // 되므로, 주행 단계부터 자르면 짝이 되는 대기 단계만 앞 구간에 남는다 — 종류는 그대로라
                // 앞 구간 검사도 통과하지만, 그 대기 단계는 이제 <b>다른 노선</b>을 가리킨다.
                log.warn("[JourneyReroute] 교체 시작점이 구간 중간이다 fromLegIndex={}", fromLegIndex);
                return Result.failed("snapshot-unmappable");
            }
        } else {
            String rejection = truncationRejection(apply, truncation, insideTransit);
            if (rejection != null) {
                log.warn("[JourneyReroute] 중간 절단을 못 한다 fromLegIndex={} 사유={}", fromLegIndex, rejection);
                return Result.failed(rejection);
            }
        }

        PathResult snapshot;
        if (truncation == null) {
            snapshot = splice(currentSnapshot, cut, apply.path());
        } else {
            SubPathResult truncated = truncate(currentSnapshot.subPaths().get(cut),
                    apply.origin(), truncation);
            if (truncated == null) {
                log.warn("[JourneyReroute] 절단할 정류장을 못 찾았다 fromLegIndex={} index={}",
                        fromLegIndex, truncation.alightStationIndex());
                return Result.failed("truncation-unmappable");
            }
            snapshot = spliceTruncated(currentSnapshot, cut, truncated, apply.path());
        }

        JourneyPlan plan = guidanceInfoEnricher.enrich(transitIdEnricher.enrich(compiler.compile(snapshot)));
        if (plan.legs().isEmpty()) {
            return Result.failed("snapshot-unmappable");
        }
        String mismatch = prefixMismatch(plan.legs(), currentLegs, fromLegIndex);
        if (mismatch != null) {
            log.warn("[JourneyReroute] 합성 뒤 앞 구간이 달라졌다 — {}", mismatch);
            return Result.failed("snapshot-mismatch");
        }
        if (truncation == null) {
            return Result.of(new Composed(snapshot, plan.legs(), plan.transfers(), fromLegIndex,
                    Reason.REMAINING_PATH_ACCEPTED));
        }
        return Result.of(new Composed(snapshot, plan.legs(), plan.transfers(), fromLegIndex,
                Reason.REMAINING_PATH_MID_RIDE_ACCEPTED, fromLegIndex));
    }

    /**
     * 절단을 받아 줄 수 있는 후보인가. 받아 줄 수 없으면 지표 태그가 될 사유.
     *
     * <p>절단은 "구간 한가운데" 거절의 <b>예외</b>지, 그 검사를 무르는 장치가 아니다. 그래서 조건이
     * 오히려 좁다 — {@code RIDE_INTERIOR} 원점이 <b>그 주행 단계를</b> 가리키고, 잘린 구간이 실제로
     * 구간 한가운데여야 한다. 원점 없이 온 절단 지시는 후보가 어디서 내리는지 모르는 상태라
     * 받아들이면 안 된다.
     */
    private String truncationRejection(RerouteCandidateApply.RemainingPathApply apply,
                                       RideTruncation truncation, boolean insideTransit) {
        RerouteOrigin origin = apply.origin();
        if (origin == null || origin.kind() != RerouteOriginKind.RIDE_INTERIOR
                || origin.legIndex() != apply.fromLegIndex()) {
            return "truncation-unexpected";
        }
        if (!insideTransit) {
            // 주행 단계가 아니라 대기·도보 단계를 가리킨 절단이다. 자를 주행 구간이 없다.
            return "truncation-unexpected";
        }
        if (truncation.rideSeconds() < 0) {
            return "truncation-unmappable";
        }
        return null;
    }

    /**
     * 옛 스냅샷의 앞 구간 + 후보 경로.
     *
     * <p>{@code info}·{@code pathType}·{@code laneGraphics} 는 <b>후보 경로의 것</b>을 그대로 쓴다.
     * 억지로 합산하면 "앞 구간을 포함한 총 소요"처럼 보이는 틀린 숫자가 생기는데, 그 값을 쓰는 곳은
     * 여정 시작의 목적지 이름 한 줄뿐이고 그것은 세션이 이미 들고 있다.
     */
    private PathResult splice(PathResult currentSnapshot, int cut, PathResult candidate) {
        List<SubPathResult> subPaths = new ArrayList<>(currentSnapshot.subPaths().subList(0, cut));
        subPaths.addAll(candidate.subPaths());
        return new PathResult(candidate.pathType(), candidate.info(), subPaths,
                candidate.laneGraphics());
    }

    /** 옛 스냅샷의 앞 구간 + <b>승차→X 로 자른 주행 구간</b> + 후보 경로. */
    private PathResult spliceTruncated(PathResult currentSnapshot, int cut, SubPathResult truncated,
                                       PathResult candidate) {
        List<SubPathResult> subPaths = new ArrayList<>(currentSnapshot.subPaths().subList(0, cut));
        subPaths.add(truncated);
        subPaths.addAll(candidate.subPaths());
        return new PathResult(candidate.pathType(), candidate.info(), subPaths,
                candidate.laneGraphics());
    }

    /**
     * 주행 구간을 X 에서 자른다. 자를 수 없으면 null.
     *
     * <h3>자를 자리는 부르는 쪽이 준다</h3>
     * 스냅샷의 정류장 id 는 검색이 준 것(OTP {@code gtfsId})이고 원점의 {@code stopId} 는 서울
     * id 다. 여기서 둘을 맞히려 들면 번역이 반만 되는 자리를 하나 더 만든다 — 그래서 위치는
     * {@link RideTruncation#alightStationIndex()} 로 받고, 이 함수는 <b>자르기만</b> 한다.
     *
     * <h3>버스만</h3>
     * 하차 정류장 id 는 버스 칸({@code endLocalStationID})에 넣는다. 지하철 구간에 같은 값을 넣으면
     * 서울 버스 id 가 지하철 칸에 들어가므로, 모드가 버스가 아니면 자르지 않는다(초기 스코프).
     *
     * <h3>지어내지 않는 값</h3>
     * 거리·요금·도착 출구는 잘린 구간의 값을 알 수 없다 — 원래 값을 그대로 두면 "그만큼 더 간다"는
     * 뜻이 되므로 비운다. 지도선은 투영 없이 <b>남은 정류장 좌표를 이은 선</b>이다(PLAN 확정).
     */
    private SubPathResult truncate(SubPathResult sub, RerouteOrigin origin,
                                   RideTruncation truncation) {
        if (sub.trafficType() != TRAFFIC_BUS) {
            return null;
        }
        List<StationResult> stations = sub.stations();
        int index = truncation.alightStationIndex();
        if (stations == null || index <= 0 || index >= stations.size()) {
            return null;
        }
        StationResult alight = stations.get(index);
        if (nameMismatch(truncation.expectedStopName(), alight.stationName())) {
            // 순번 산수로 찾은 자리에 다른 정류장이 있다. 두 정류장 목록이 어긋났다는 뜻이고,
            // 그대로 자르면 사용자는 엉뚱한 곳에서 내리라는 안내를 받는다.
            log.warn("[JourneyReroute] 절단 자리의 정류장 이름이 다르다 기대={} 실제={}",
                    truncation.expectedStopName(), alight.stationName());
            return null;
        }
        List<StationResult> kept = List.copyOf(stations.subList(0, index + 1));

        return new SubPathResult(
                sub.trafficType(), toMinutes(truncation.rideSeconds()), null, sub.lanes(), kept,
                sub.startName(), alight.stationName(),
                sub.startX(), sub.startY(), alight.x(), alight.y(),
                sub.trainType(), null, sub.startId(), sub.way(), sub.wayCode(), stopLine(kept),
                sub.startExitNo(), sub.startExitX(), sub.startExitY(), null, null, null,
                sub.walkSteps(), sub.startLocalStationID(), origin.stopId(), sub.startArsID(),
                null, null, sub.walkTotalTimeSeconds(), sub.transferArrivals());
    }

    /**
     * 두 정류장 이름이 서로 다른가. 대조할 이름이 없으면 "다르지 않다"(대조를 건너뛴다).
     *
     * <p>공백만 무시한다 — 두 원천이 "강남역.6번출구"와 "강남역 .6번출구"처럼 띄어쓰기에서
     * 갈리는 일이 흔하다. 그보다 더 느슨하게 맞추면 <b>옆 정류장</b>까지 같은 이름으로 접힌다.
     */
    private boolean nameMismatch(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return !expected.replace(" ", "").equals(actual.replace(" ", ""));
    }

    /**
     * 검색 결과의 구간 소요는 분 단위다. 초를 그대로 담을 칸이 없다.
     *
     * <p>그래서 저장된 스냅샷만으로는 초 단위가 복원되지 않는다 — 단계의 {@code plannedDurationSec}
     * 를 초 단위로 정확히 쓰려면 수락 경로가 {@link RideTruncation#rideSeconds()} 를 직접 써야 한다.
     */
    private int toMinutes(int seconds) {
        return Math.round(seconds / (float) SECONDS_PER_MINUTE);
    }

    /** 남은 정류장 좌표를 이은 선. 좌표가 빠진 정류장이 있거나 2점 미만이면 null(가짜 선 금지). */
    private List<CoordPoint> stopLine(List<StationResult> stations) {
        if (stations.size() < 2) {
            return null;
        }
        List<CoordPoint> points = new ArrayList<>(stations.size());
        for (StationResult station : stations) {
            if (station.x() == null || station.y() == null) {
                return null;
            }
            points.add(new CoordPoint(station.x(), station.y()));
        }
        return List.copyOf(points);
    }

    /**
     * 앞 구간이 저장된 단계와 같은가.
     *
     * <p>번호·종류만 본다. 이름·정류장은 같은 원본 구간에서 나오므로 종류가 같으면 따라온다.
     *
     * @return 어긋난 곳의 설명. 같으면 null
     */
    private String prefixMismatch(List<JourneyLegDraft> drafts, List<JourneyLeg> currentLegs,
                                  int fromLegIndex) {
        List<JourneyLeg> prefix = currentLegs.stream()
                .filter(leg -> leg.getLegIndex() < fromLegIndex)
                .toList();
        if (drafts.size() < prefix.size()) {
            return "합성 결과가 앞 구간보다 짧다: " + drafts.size() + " < " + prefix.size();
        }
        for (JourneyLeg leg : prefix) {
            JourneyLegDraft draft = drafts.get(leg.getLegIndex());
            if (draft.legIndex() != leg.getLegIndex() || draft.type() != leg.getType()) {
                return "legIndex=" + leg.getLegIndex() + " 종류가 " + leg.getType()
                        + " 에서 " + draft.type() + " 로 바뀌었다";
            }
        }
        return null;
    }

    /** {@code journey_revision.reason} 어휘. 왜 경로가 바뀌었는지를 운영이 읽는 값이다. */
    public static final class Reason {
        public static final String REMAINING_PATH_ACCEPTED = "REMAINING_PATH_ACCEPTED";
        /** 중간 하차. 앞 절반을 계속 타고 가므로 leg 경계 교체와 운영상 구별해야 한다. */
        public static final String REMAINING_PATH_MID_RIDE_ACCEPTED =
                "REMAINING_PATH_MID_RIDE_ACCEPTED";

        private Reason() {
        }
    }

    /**
     * 주행 중인 구간을 어디서 자를지.
     *
     * <p><b>부르는 쪽이 준다.</b> 자를 정류장은 원점을 스냅샷 정류장 목록에 맞춰 본 결과이고
     * ({@link #truncate} 의 "자를 자리는 부르는 쪽이 준다"), 그때까지의 소요는 <b>실시간</b> 근거라
     * 이 계산기가 가진 재료로는 만들 수 없다. 둘 다 여기서 지어내면 틀린 하차 안내가 된다.
     *
     * @param alightStationIndex 자를 정류장의 구간 정류장 목록 안 위치. 0(승차 정류장)은 자를 것이
     *                           없으므로 거절된다
     * @param rideSeconds        승차 → 그 정류장까지의 소요(초)
     * @param expectedStopName   그 자리에 있어야 하는 정류장 이름. 위치를 <b>서울 노선 순번의 차이</b>
     *                           로 계산하므로(PR-4), 스냅샷 정류장 목록이 서울 노선과 어긋나면 엉뚱한
     *                           정류장을 자른다. 그 어긋남을 잡는 유일한 대조점이 이름이다 —
     *                           id 는 두 세계가 서로 다른 체계를 쓴다. null 이면 대조하지 않는다
     */
    public record RideTruncation(int alightStationIndex, int rideSeconds, String expectedStopName) {

        /** 이름 대조 없이 자른다(대조할 이름을 못 구한 경우·기존 호출부). */
        public RideTruncation(int alightStationIndex, int rideSeconds) {
            this(alightStationIndex, rideSeconds, null);
        }
    }

    /**
     * 새 개정본의 재료.
     *
     * @param legs              새 단계 <b>전부</b>(앞 구간 포함). 앞 구간은 저장 쪽이 엔티티 복제로
     *                          덮는다
     * @param transfers         합성본이 낸 환승 전부. 접합부 환승도 여기 들어 있다
     * @param truncatedLegIndex 중간에서 잘린 주행 단계. 없으면 null.
     *                          <p>이 단계는 <b>새로 만드는 단계도, 그대로 복제하는 단계도 아니다</b>
     *                          — 사용자가 지금 타고 있는 그 버스라 진행도는 살리고 하차 목표만 X 로
     *                          바뀐다. 저장 쪽이 그 제3의 경우를 알아보려면 번호가 필요하다
     */
    public record Composed(PathResult snapshot, List<JourneyLegDraft> legs,
                           List<TransferConnectionDraft> transfers, int fromLegIndex,
                           String reason, Integer truncatedLegIndex) {

        /** 자르지 않는 합성(대부분)용. */
        public Composed(PathResult snapshot, List<JourneyLegDraft> legs,
                        List<TransferConnectionDraft> transfers, int fromLegIndex, String reason) {
            this(snapshot, legs, transfers, fromLegIndex, reason, null);
        }
    }

    /** 성공 또는 사유. 사유는 그대로 지표 태그가 된다. */
    public record Result(Composed composed, String failureReason) {

        static Result of(Composed composed) {
            return new Result(composed, null);
        }

        static Result failed(String reason) {
            return new Result(null, reason);
        }

        public boolean failed() {
            return composed == null;
        }
    }
}
