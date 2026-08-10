package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;

import java.time.Instant;

/**
 * 사용자가 환승 정류장에 <b>탈 준비를 마치고 서 있을</b> 시각을 구한다.
 *
 * <pre>
 * userReadyAt = currentLegArrivalAt + alightingBuffer + transferWalkSeconds
 * </pre>
 *
 * <p>버스가 정류장에 선 시각을 그대로 쓰면 안 된다. 내리는 데 시간이 걸리고, 환승 정류장까지
 * 걸어가야 한다. 이 둘을 빼먹으면 환승 여유가 실제보다 넉넉해 보여 "탈 수 있다"고 말한 환승을
 * 사용자가 놓친다.
 *
 * <p>도보 시간은 새로 계산하지 않고 환승 걷기 단계의 계획 소요시간을 쓴다. 그 값은 검색 때
 * TMAP 실측으로 채워진 것이라 직선거리 추정보다 낫다. 개인별 걷는 속도는 1차에서 학습하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferReadinessCalculator {

    private final JourneyTransferRiskProperties properties;

    /**
     * 사용자가 그 환승에서 어디까지 왔는지.
     *
     * <p>준비 시각의 계산이 <b>단계마다 다르다</b> — 이미 한 일을 다시 더하면 안 되기 때문이다.
     */
    public enum Progress {
        /** 아직 앞 교통수단을 타고 있다. 하차도 환승 도보도 남았다. */
        RIDING,
        /** 내려서 환승 정류장으로 걷는 중이다. 하차는 끝났다. */
        WALKING,
        /** 이미 환승 정류장에서 기다리고 있다. 남은 일이 없다. */
        WAITING
    }

    /**
     * 준비 완료 시각.
     *
     * @param currentLegArrivalAt 지금 타고 있는 교통수단이 환승 정류장에 닿는 시각
     * @param transferWalkSeconds 환승 걷기 소요시간(초). 같은 정류장 환승이면 null 또는 0
     * @return 준비 완료 시각. 계산할 근거가 없으면 null
     *
     * <table>
     *   <caption>단계별 계산</caption>
     *   <tr><th>단계</th><th>준비 완료 시각</th></tr>
     *   <tr><td>{@code RIDING}</td><td>도착 + 하차 버퍼 + 환승 도보</td></tr>
     *   <tr><td>{@code WALKING}</td><td>지금 + 환승 도보(하차는 이미 끝났다)</td></tr>
     *   <tr><td>{@code WAITING}</td><td>지금(이미 서 있다)</td></tr>
     * </table>
     *
     * <p><b>이 구분이 없으면 정류장에 서 있는 사용자가 환승을 놓친 것으로 판정된다.</b> 앞 단계의
     * 도착 시각에 하차 버퍼와 도보를 계속 더하므로 준비 시각이 3분 넘게 미래로 밀리고, 2분 뒤에
     * 오는 차가 "이미 지나간 차"가 된다.
     *
     * @param now 걷는 중·기다리는 중일 때의 기준 시각. {@code RIDING} 이면 쓰지 않는다
     */
    public Instant userReadyAt(Instant currentLegArrivalAt, Integer transferWalkSeconds,
                               Progress progress, Instant now) {
        long walk = transferWalkSeconds == null ? 0L : Math.max(0, transferWalkSeconds);
        return switch (progress) {
            case WAITING -> now;
            case WALKING -> now == null ? null : now.plusSeconds(walk);
            case RIDING -> currentLegArrivalAt == null ? null : currentLegArrivalAt
                    .plusSeconds(properties.getAlightingBufferSeconds())
                    .plusSeconds(walk);
        };
    }

    /**
     * 환승 여유.
     *
     * <p>둘 중 하나라도 모르면 null 이다. 한쪽을 0으로 가정하고 계산하면 그럴듯하게 틀린 숫자가
     * 나오고, 그것이 알림으로 이어진다.
     */
    public Long transferSlackSeconds(Instant userReadyAt, Instant nextBusArrivalAt) {
        if (userReadyAt == null || nextBusArrivalAt == null) {
            return null;
        }
        return nextBusArrivalAt.getEpochSecond() - userReadyAt.getEpochSecond();
    }
}
