package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 열차의 직전 위치와, 그 열차가 남긴 도착·출발 사건을 보관한다.
 *
 * <p>스냅샷은 덮어쓰기라 "지금"만 남는다. 그런데 우리가 답해야 하는 질문은 과거형이다 —
 * "이 역을 방금 출발한 열차가 누구인가", "그 열차가 다음 역에 언제 도착했나". 그래서 별도 보관이
 * 필요하다.
 *
 * <p><b>여기 있는 것은 전부 사라져도 되는 값이다.</b> 유실되면 탑승 판정이 잠정 상태에 머물 뿐,
 * 잘못된 열차에 고정되지는 않는다. 복구 불가능한 상태를 여기 두지 않는다.
 */
public interface SubwayTrainLogPort {

    /** 전이 감지의 좌변. 이 열차를 마지막으로 본 자리. */
    Optional<SubwayTrainObservation> lastSeen(TrainIdentity train);

    /**
     * 여러 열차의 직전 위치를 한 번에(#317 역 도착 응답). 없는 열차는 결과에 없다.
     *
     * <p>기본 구현은 한 대씩 묻는다. 저장소가 묶어 읽을 수 있으면 덮어쓴다.
     */
    default Map<TrainIdentity, SubwayTrainObservation> lastSeenAll(Collection<TrainIdentity> trains) {
        Map<TrainIdentity, SubwayTrainObservation> out = new LinkedHashMap<>();
        for (TrainIdentity train : trains) {
            lastSeen(train).ifPresent(seen -> out.put(train, seen));
        }
        return out;
    }

    void remember(TrainIdentity train, SubwayTrainObservation observation);

    /**
     * 사건 한 건을 남긴다.
     *
     * <p>같은 {@code (type, stationId)} 가 이미 있으면 <b>품질이 오를 때만</b> 교체한다.
     * 무조건 추가하면 같은 출발이 여러 건으로 보이고, 무조건 무시하면 잠정 판정이
     * 확정으로 승격되지 못한다.
     */
    void append(TrainIdentity train, SubwayTrainEvent event);

    /**
     * 어떤 역을 그 시간대에 출발한 열차들. 탑승 판정이 후보를 찾는 진입점이다.
     *
     * <p>조회 축이 <b>역</b>이라 열차별 보관과 색인이 다르다.
     */
    List<TrainDeparture> findDepartures(java.time.LocalDate serviceDate, String lineId,
                                        String direction, String stationId,
                                        Instant from, Instant to);

    /**
     * 특정 열차의 도착 사건. 다음 역 정차로 확정하는 경로(L4)가 쓴다.
     *
     * <p>관측 해상도가 60~90초라 탑승 순간만으로는 열차를 가르기 어렵다. 그래서 이쪽이
     * 부가가 아니라 주된 확정 경로다.
     */
    List<SubwayTrainEvent> findArrivals(TrainIdentity train, Instant from, Instant to);

    /** 역 기준 조회의 결과. 어느 열차가 언제 출발했는지가 한 줄이다. */
    record TrainDeparture(String trainNo, SubwayTrainEvent event) {
    }
}
