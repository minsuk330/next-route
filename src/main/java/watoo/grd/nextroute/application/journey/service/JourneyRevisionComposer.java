package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.route.dto.PathResult;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyRevisionComposer {

    private final JourneyCompiler compiler;
    private final JourneyTransitIdEnricher transitIdEnricher;

    /**
     * @param currentSnapshot 지금 개정본의 경로 원본
     * @param currentLegs     지금 개정본의 단계 전부({@code legIndex} 오름차순)
     * @param apply           사용자가 고른 후보의 적용 재료
     * @return 만들 수 없으면 사유가 담긴 실패
     */
    public Result compose(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                          RerouteCandidateApply apply) {
        if (apply instanceof RerouteCandidateApply.RouteOptionApply routeOption) {
            // 구조가 바뀌지 않는다. 스냅샷도 단계도 그대로 복제하고 선택 노선만 갈아끼운다.
            return Result.of(new Composed(currentSnapshot, List.of(), List.of(),
                    routeOption.waitLegIndex(), Reason.ROUTE_OPTION_ACCEPTED));
        }
        RerouteCandidateApply.RemainingPathApply remaining =
                (RerouteCandidateApply.RemainingPathApply) apply;
        return composeRemainingPath(currentSnapshot, currentLegs, remaining);
    }

    private Result composeRemainingPath(PathResult currentSnapshot, List<JourneyLeg> currentLegs,
                                        RerouteCandidateApply.RemainingPathApply apply) {
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

        if (fromLegIndex > 0 && cut.equals(current.subPathIndexOf(fromLegIndex - 1))) {
            // 교체 시작점이 원본 구간의 <b>한가운데</b>다. 교통수단 한 구간은 대기·주행 두 단계가
            // 되므로, 주행 단계부터 자르면 짝이 되는 대기 단계만 앞 구간에 남는다 — 종류는 그대로라
            // 앞 구간 검사도 통과하지만, 그 대기 단계는 이제 <b>다른 노선</b>을 가리킨다.
            log.warn("[JourneyReroute] 교체 시작점이 구간 중간이다 fromLegIndex={}", fromLegIndex);
            return Result.failed("snapshot-unmappable");
        }

        PathResult snapshot = splice(currentSnapshot, cut, apply.path());
        JourneyPlan plan = transitIdEnricher.enrich(compiler.compile(snapshot));
        if (plan.legs().isEmpty()) {
            return Result.failed("snapshot-unmappable");
        }
        String mismatch = prefixMismatch(plan.legs(), currentLegs, fromLegIndex);
        if (mismatch != null) {
            log.warn("[JourneyReroute] 합성 뒤 앞 구간이 달라졌다 — {}", mismatch);
            return Result.failed("snapshot-mismatch");
        }
        return Result.of(new Composed(snapshot, plan.legs(), plan.transfers(), fromLegIndex,
                Reason.REMAINING_PATH_ACCEPTED));
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
        public static final String ROUTE_OPTION_ACCEPTED = "ROUTE_OPTION_ACCEPTED";
        public static final String REMAINING_PATH_ACCEPTED = "REMAINING_PATH_ACCEPTED";

        private Reason() {
        }
    }

    /**
     * 새 개정본의 재료.
     *
     * @param legs      새 단계 <b>전부</b>(앞 구간 포함). 앞 구간은 저장 쪽이 엔티티 복제로 덮는다.
     *                  {@code ROUTE_OPTION} 이면 비어 있다 — 구조가 안 바뀐다
     * @param transfers 합성본이 낸 환승 전부. 접합부 환승도 여기 들어 있다
     */
    public record Composed(PathResult snapshot, List<JourneyLegDraft> legs,
                           List<TransferConnectionDraft> transfers, int fromLegIndex,
                           String reason) {

        /** 구조가 바뀌지 않는 수락인가. */
        public boolean keepsStructure() {
            return legs.isEmpty();
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
