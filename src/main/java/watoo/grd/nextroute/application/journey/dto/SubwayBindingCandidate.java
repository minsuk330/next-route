package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 탑승역을 그 시간대에 출발한 열차 한 대.
 *
 * <p>출발 시각이 <b>점이 아니라 구간</b>인 것이 판정의 전부다. 관측이 빌수록 구간이 넓어지고,
 * 넓은 구간은 관문에서 자동으로 보수적인 판정을 만든다 — 흐릿한 근거로 자신 있게 고르지 않는다.
 *
 * @param gapSeconds 이동 시작 구간과 이 출발 구간의 거리(초). 두 구간이 겹치면 0
 */
public record SubwayBindingCandidate(
        String trainNo,
        LocalDate serviceDate,
        String lineId,
        String direction,
        Instant departureFrom,
        Instant departureTo,
        SubwayTrainEvent.Quality quality,
        String terminalId,
        SubwayExpressType express,
        long gapSeconds
) {

    /** 이 후보가 가리키는 열차. 저장·조회 키가 되므로 매번 같은 조합으로 만든다. */
    public TrainIdentity identity() {
        return new TrainIdentity(serviceDate, lineId, direction, trainNo);
    }

    /** 급행·특급은 서는 역이 달라 계획 역 체인과 대조할 수 없다(MVP 는 통째로 배제한다). */
    public boolean isNormalTrain() {
        return express == SubwayExpressType.NORMAL;
    }

    public SubwayBindingCandidate withGapSeconds(long gap) {
        return new SubwayBindingCandidate(trainNo, serviceDate, lineId, direction,
                departureFrom, departureTo, quality, terminalId, express, gap);
    }

    /** 역 기준 조회 결과 한 줄을 후보로 옮긴다. 거리는 아직 재지 않았다. */
    public static SubwayBindingCandidate of(LocalDate serviceDate, String lineId, String direction,
                                            String trainNo, SubwayTrainEvent event) {
        return new SubwayBindingCandidate(trainNo, serviceDate, lineId, direction,
                event.from(), event.to(), event.quality(), event.terminalId(), event.express(), 0L);
    }
}
