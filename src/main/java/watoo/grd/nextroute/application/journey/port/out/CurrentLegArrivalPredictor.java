package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;

/**
 * 단계 종료 시각을 구하는 방법 하나.
 *
 * <p>단계 종류마다 근거가 다르다. 이동 단계는 앱 GPS 진행도로 구하고, 기다리는 단계는 실시간
 * 도착 정보로 구한다. 후자는 아직 없다(다음 단계의 몫). 하나의 큰 분기문 대신 구현을 나눠 두는
 * 이유는 대기 단계가 외부 API·ML·캐시를 쓰게 되어 이동 단계와 실패 방식이 전혀 다르기 때문이다.
 */
public interface CurrentLegArrivalPredictor {

    /** 이 구현이 맡는 단계 종류인지. */
    boolean supports(JourneyLegType type);

    /**
     * @param leg      대상 단계. 저장된 progress 요약을 들고 있다
     * @param geometry 그 단계의 좌표·구간별 거리·배분시간. 없으면 null
     * @param now      서버 기준 지금. 관측이 얼마나 오래됐는지 판정하는 데만 쓴다
     * @return 계산할 근거가 없으면 {@link CurrentLegArrival#unknown()}
     */
    CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry, Instant now);
}
