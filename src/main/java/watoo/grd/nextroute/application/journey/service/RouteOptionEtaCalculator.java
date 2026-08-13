package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * "이 버스를 타면 목적지에 언제 닿나".
 *
 * <p>비교 기준이 <b>목적지 도착 시각</b>인 이유는 "다음 차가 언제 오냐"만 보면 <b>빨리 오는 차가
 * 더 늦게 도착</b>할 수 있기 때문이다(D4).
 *
 * <pre>
 * 목적지 ETA = 예측 승차 시각
 *            + 승차 단계 소요(대기 단계 다음 RIDE_* 단계)
 *            + 그 뒤 모든 RIDE_* · WALK · TRANSFER_WALK 단계의 소요 합
 *            ( 그 뒤 WAIT_* 단계는 0으로 둔다 )
 * </pre>
 *
 * <h2>이후 대기 시간을 0으로 두는 것 (R1)</h2>
 * <b>대기 단계의 소요는 언제나 비어 있다</b> — 기다리는 시간은 검색 결과에 없다
 * ({@code JourneyCompiler}). "값이 없으면 포기" 규칙으로 짜면 환승이 둘 이상인 여정에서 제안이
 * <b>영원히 0건</b>이 된다.
 *
 * <p>0으로 두어도 <b>개선폭은 정확하다</b>. 그 값은 기준선에도 후보에도 똑같이 빠지므로 뺄셈에서
 * 상쇄된다. 왜곡되는 것은 화면에 보이는 절대 도착 시각뿐이라, 뒤에 대기 단계가 남아 있으면
 * 신뢰도를 낮춰 표시한다({@link Result#hasFurtherTransfer()}).
 *
 * <h2>승차·도보 소요가 비면 후보를 버린다</h2>
 * 그 값들은 검색 결과가 채우므로 정상 여정에는 반드시 있다. 없다면 데이터가 깨진 것이고, 0으로
 * 치면 도착 시각이 실제보다 빨라져 <b>없는 개선폭</b>을 만들어 낸다.
 */
@Component
public class RouteOptionEtaCalculator {

    /**
     * @param destinationEtaAt   목적지 도착 예상 시각
     * @param remainingRideSec   승차 시각 이후의 소요 합(대기 제외)
     * @param walkingSec         대기 단계 이후 걷는 시간의 합. 화면 표시용
     * @param hasFurtherTransfer 뒤에 대기 단계가 남아 있는지. 있으면 절대 시각이 낙관적이다
     */
    public record Result(Instant destinationEtaAt, int remainingRideSec, int walkingSec,
                         boolean hasFurtherTransfer) {
    }

    /**
     * @param legs        한 개정본의 단계 <b>전부</b>, {@code legIndex} 오름차순
     * @param waitLeg     갈아탈 대기 단계
     * @param boardingAt  그 대기 단계에서 차를 타는 예측 시각
     * @return 소요가 깨져 계산할 수 없으면 빈 결과. 그 후보는 버린다
     */
    public Optional<Result> compute(List<JourneyLeg> legs, JourneyLeg waitLeg, Instant boardingAt) {
        return computeFrom(
                legs.stream()
                        .map(leg -> new Segment(leg.getLegIndex(), leg.getType(),
                                leg.getPlannedDurationSec()))
                        .toList(),
                waitLeg.getLegIndex(), boardingAt);
    }

    /**
     * <b>아직 저장되지 않은</b> 경로용. 재검색이 방금 가져온 후보는 DB 에 없어 엔티티가 없다.
     *
     * <p>대기 단계 <b>객체</b>가 아니라 하한 <b>번호</b>를 받는 것이 차이다. 걷기만으로 목적지에
     * 닿는 후보에는 넘길 대기 단계가 아예 없기 때문이다.
     *
     * @param fromLegIndexExclusive 이 번호까지는 이미 승차 시각에 반영돼 있다. 그보다 뒤만 더한다.
     *                              대기 단계가 없는 경로에는 (첫 단계 번호 - 1) 을 넘긴다
     */
    public Optional<Result> compute(List<JourneyLegDraft> legs, int fromLegIndexExclusive,
                                    Instant boardingAt) {
        return computeFrom(
                legs.stream()
                        .map(leg -> new Segment(leg.legIndex(), leg.type(), leg.plannedDurationSec()))
                        .toList(),
                fromLegIndexExclusive, boardingAt);
    }

    /**
     * 합산 규칙은 <b>한 벌뿐이다.</b> 저장된 경로용과 후보용이 각자 계산하면 ③과 ④의 개선폭이
     * 계산 방식의 차이가 된다.
     */
    private Optional<Result> computeFrom(List<Segment> segments, int fromLegIndexExclusive,
                                         Instant boardingAt) {
        int remaining = 0;
        int walking = 0;
        boolean furtherTransfer = false;

        for (Segment segment : segments) {
            if (segment.legIndex() <= fromLegIndexExclusive) {
                // 이미 지나온 구간과 대기 단계 자체는 예측 승차 시각에 이미 반영돼 있다.
                continue;
            }
            if (segment.type().isWaiting()) {
                // 검색 결과에 없는 값이다. 여기서 포기하면 환승이 둘 이상인 여정이 통째로 죽는다.
                furtherTransfer = true;
                continue;
            }
            Integer duration = segment.plannedDurationSec();
            if (duration == null) {
                return Optional.empty();
            }
            remaining += duration;
            if (segment.type() == JourneyLegType.WALK
                    || segment.type() == JourneyLegType.TRANSFER_WALK) {
                walking += duration;
            }
        }
        return Optional.of(new Result(boardingAt.plusSeconds(remaining), remaining, walking,
                furtherTransfer));
    }

    /** 합산에 필요한 것만 남긴 한 단계. 엔티티와 초안을 같은 규칙으로 다루기 위한 그릇이다. */
    private record Segment(int legIndex, JourneyLegType type, Integer plannedDurationSec) {
    }
}
