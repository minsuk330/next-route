package watoo.grd.nextroute.application.journey.dto;

import java.util.List;

/**
 * 그 지하철 단계의 <b>계획</b> 역 체인. 0 번이 승차역, 마지막이 하차역이다.
 *
 * <p>실시간 로그가 아니라 계획에서 읽는다. 관측 해상도가 60~90초라 로그에서 뽑으면 한 역을
 * 통째로 놓치는 일이 실제로 있고, 그러면 링이 <b>남은 역 수를 조용히 틀리게</b> 센다.
 *
 * <p>{@code canonicalKey} 는 서울 실시간 {@code statnId} 다. 이름이 아니라 이 값으로 다음 역이
 * 바뀌었는지를 판정한다 — 역명은 표기가 흔들려서({@code 성수}/{@code 성수종착}) 같은 역인데
 * 링이 0 으로 리셋된다. 해석하지 못한 역은 {@code null} 이고, 그 역은 도착 조회를 못 해
 * 위치 폴백만 남는다.
 */
public record SubwayRingChain(List<Station> stations) {

    public SubwayRingChain {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }

    /**
     * @param sequence 승차역을 0 으로 세는 순번
     */
    public record Station(int sequence, String canonicalKey, String name) {
    }

    public boolean isUsable() {
        // 승차역 하나만으로는 "다음 역"이 없다.
        return stations.size() >= 2;
    }

    /** 하차역의 순번. 곧 체인의 마지막이다. */
    public int alightingSequence() {
        return stations.size() - 1;
    }

    public String alightingName() {
        return stations.isEmpty() ? null : stations.get(stations.size() - 1).name();
    }

    /** 체인 밖이면 {@code null}. 부르는 쪽이 "모름"으로 다룬다. */
    public Station at(int sequence) {
        if (sequence < 0 || sequence >= stations.size()) {
            return null;
        }
        return stations.get(sequence);
    }

    /**
     * 그 역이 체인의 몇 번째인가. 못 찾으면 {@code -1}.
     *
     * <p>{@code canonicalKey} 로만 찾는다 — 이름으로 찾으면 표기 흔들림이 그대로 순번 오류가
     * 된다.
     */
    public int sequenceOfKey(String canonicalKey) {
        if (canonicalKey == null) {
            return -1;
        }
        for (Station station : stations) {
            if (canonicalKey.equals(station.canonicalKey())) {
                return station.sequence();
            }
        }
        return -1;
    }
}
