package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Duration;
import java.time.Instant;

/**
 * 지하철 탑승 단계의 현재 단계 도착을 링의 하차 예정으로 맞춘다 — 고른 열차를 기다리는 시간(#469)과 탄 뒤의 진행(#481).
 *
 * <p><b>ETA 조립기와 환승 위험 판정기가 같이 쓴다(#476).</b> 조립기만 쓰면 화면의 현재 단계 도착은 늦춰지는데
 * 판정기는 열차 대기 없이 "환승 준비 시각"을 잡아, 놓칠 버스를 괜찮다고 판정하고 그 버스 도착으로 여정 도착을
 * 다시 잡는다. 두 자리가 다른 규칙을 들면 같은 응답 안에서 현재 단계 도착과 환승 판정이 어긋난다.
 *
 * <p>역에서 열차를 고르면 곧바로 타는 단계가 된다(2026-10-10 결정). 이동 단계 계산은 "단계 시작(또는 마지막 관측)
 * + 계획 소요시간"이라, 열차가 5분 뒤에 와도 지금 떠난 것처럼 나온다. 열차가 언제 오는지는 링만 안다 — 승차역
 * 구간 링의 하차 예정은 <b>열차의 승차역 도착 + 하차역까지 간격</b>이다.
 * <ul>
 *   <li>링의 하차 예정이 있으면 그것</li>
 *   <li>없으면(간격을 모른다) 열차의 승차역 도착(이미 지났으면 지금) + 계획 소요시간</li>
 *   <li>둘 다 없으면 그대로 둔다 — 열차가 언제 오는지 모르는데 지어내지 않는다</li>
 * </ul>
 *
 * <p><b>탄 뒤에도 링의 하차 예정이 있으면 그것이다(#481).</b> 앱 진행도는 지하에서 GPS 보고가 끊겨 "탑승 시각 + 계획
 * 소요시간"에 고정된다(journey 291: 15:20 고정, 잠금화면 링은 15:22~15:26). 그러면 화면 요약(journeyEta)과 잠금화면이
 * 같은 열차에 다른 도착을 말한다. 링이 STALE 이어도 마지막 하차 예정을 쓴다(사용자 결정 2026-10-22) — 앱 카운트다운이 이미
 * 그 값을 쓰고, 두 숫자를 하나로 두는 것이 목적이다. 품질은 APPROXIMATE 로 낮춘다. 하차 예정이 이미 지났는데 아직 타는
 * 중이면 지금이다. 링에 하차 예정이 없으면 앱 진행도 그대로다.
 *
 * <p>외부 호출도 저장소 조회도 없다. 링을 어디서 얻을지는 부르는 쪽의 몫이다 — 조립기는 스냅샷에 이미 실린 안내를,
 * 판정기는 같은 안내 조립기({@link JourneySubwayGuidanceComposer})로 읽은 값을 넘긴다.
 */
@Component
public class ChosenSubwayTrainWait {

    /**
     * @param guidance <b>{@code leg} 의</b> 지하철 안내. 지금 단계가 아닌 단계에 다른 단계의 안내를 넘기면 안 된다.
     *                 없으면 null
     * @param leg      지금 단계
     * @param moving   이동 단계 계산({@link CurrentLegArrivalService})이 낸 도착
     * @return 열차 대기를 넣은 도착. 해당이 없으면 {@code moving} 그대로
     */
    public CurrentLegArrival afterChosenTrainArrives(SubwayGuidance guidance, JourneyLeg leg,
                                                     CurrentLegArrival moving, Instant now) {
        if (leg == null || leg.getType() != JourneyLegType.RIDE_SUBWAY || guidance == null) {
            return moving;
        }
        Instant arrivalAt = guidance.estimatedAlightingAt();
        if (!guidance.trainBeforeBoarding()) {
            if (arrivalAt == null) {
                return moving;
            }
            if (arrivalAt.isBefore(now)) {
                arrivalAt = now;
            }
        } else if (arrivalAt == null) {
            Instant trainAt = guidance.estimatedNextStationAt();
            Integer planned = leg.getPlannedDurationSec();
            if (trainAt == null || planned == null) {
                return moving;
            }
            arrivalAt = (trainAt.isBefore(now) ? now : trainAt).plusSeconds(planned);
        }
        return new CurrentLegArrival(arrivalAt,
                (int) Math.max(0, Duration.between(now, arrivalAt).toSeconds()),
                moving.remainingDistanceMeters(), moving.remainingStops(),
                CurrentLegArrival.Source.REALTIME_ARRIVAL, trainArrivalQuality(guidance), now);
    }

    /** 링의 다음 역 예정이 실시간·실측이고 링이 살아 있을 때만 믿을 만하다. 남은 구간은 어차피 계획값이다. */
    private static CurrentLegArrival.Quality trainArrivalQuality(SubwayGuidance guidance) {
        boolean measured = guidance.etaSource() == SubwayRingEtaSource.LIVE
                || guidance.etaSource() == SubwayRingEtaSource.MEASURED;
        return measured && guidance.quality() == SubwayRingQuality.NORMAL
                ? CurrentLegArrival.Quality.GOOD
                : CurrentLegArrival.Quality.APPROXIMATE;
    }
}
