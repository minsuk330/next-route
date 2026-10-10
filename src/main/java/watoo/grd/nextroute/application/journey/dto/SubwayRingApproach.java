package watoo.grd.nextroute.application.journey.dto;

import java.util.List;

/**
 * 승차역에 아직 오지 않은 고른 열차가 지금 어디쯤인지(#368). 링이 승차역 대기 구간일 때만 있다.
 *
 * <p>링 진행률은 승차역 구간에서 0 에 묶여 있다(#304). 열차가 다가오는 것을 진행률로 그리면 사용자가 이미 타고 가는 중으로
 * 읽기 때문이다. 그래서 다가오는 모습은 진행률이 아니라 이 별도 칸으로 말한다.
 *
 * @param stationName           열차가 지금 있는(또는 막 떠난) 역
 * @param stationsUntilBoarding 승차역까지 남은 역 수. 승차역 바로 앞 역이면 1
 * @param stations              열차 현재 역부터 승차역 바로 앞 역까지, 열차가 지나올 순서. 크기 = {@code stationsUntilBoarding}
 */
public record SubwayRingApproach(String stationName, int stationsUntilBoarding, List<String> stations) {

    public SubwayRingApproach {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }
}
