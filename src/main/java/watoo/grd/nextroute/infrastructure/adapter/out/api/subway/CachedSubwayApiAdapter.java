package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import watoo.grd.nextroute.application.subway.config.SubwayStationArrivalProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;

import java.util.List;
import java.util.Optional;

/**
 * 역별 지하철 실시간 조회 캐시 데코레이터.
 *
 * <p>같은 역을 보는 여정이 여럿이면 API 는 한 번만 부른다. 같은 역·같은 순간의 열차 목록은 누구에게나
 * 같기 때문이다. <b>그중 어느 열차를 탈 수 있는지는 사용자마다 준비 시각이 달라 답이 다르므로</b>,
 * 그 선택은 캐시 뒤(`SubwayRealtimeArrivalReader`)에 그대로 남는다.
 *
 * <p>캐시 miss 뒤의 호출은 {@link SingleFlight} 로 합친다. 호출 단위가 역 이름 하나로 명확해서
 * {@code CachedSearchTimeBusAdapter} 와 같은 모양이 그대로 들어맞는다. 이 API 는 여정 평가기뿐 아니라
 * 조회 요청 경로도 쓰기 때문에 동시 miss 가 평가 워커 수에 묶이지 않는다.
 *
 * <p><b>전체 수집({@link #getRealtimeArrival()})은 캐시하지 않는다.</b> 그쪽은 수집기가 자기 주기로
 * 부르고 결과를 스냅샷 캐시에 넣는 별도 경로다. 나머지 정적 조회도 그대로 위임한다.
 */
@Slf4j
@Primary
@Component
public class CachedSubwayApiAdapter implements SubwayApiPort {

    private final SeoulSubwayApiAdapter delegate;
    private final SubwayStationArrivalCachePort cache;
    private final SubwayStationArrivalProperties props;
    private final SubwayRealtimeMetrics metrics;

    private final SingleFlight<String, List<SubwayArrivalInfo>> stationFlight = new SingleFlight<>();

    public CachedSubwayApiAdapter(SeoulSubwayApiAdapter delegate,
                                  SubwayStationArrivalCachePort cache,
                                  SubwayStationArrivalProperties props,
                                  SubwayRealtimeMetrics metrics) {
        this.delegate = delegate;
        this.cache = cache;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public List<SubwayStationInfo> getSubwayStationMaster() {
        return delegate.getSubwayStationMaster();
    }

    @Override
    public List<SubwaySegmentInfo> getStationDistance() {
        return delegate.getStationDistance();
    }

    @Override
    public List<SubwayArrivalInfo> getRealtimeArrival() {
        return delegate.getRealtimeArrival();
    }

    @Override
    public List<SubwayArrivalInfo> getRealtimeArrivalByStation(String stationName) {
        Optional<List<SubwayArrivalInfo>> hit = cache.find(stationName);
        if (hit.isPresent()) {
            metrics.recordCacheAccess("hit");
            return hit.get();
        }
        metrics.recordCacheAccess("miss");

        SingleFlight.Outcome<List<SubwayArrivalInfo>> outcome = stationFlight.execute(stationName, () -> {
            List<SubwayArrivalInfo> fresh = delegate.getRealtimeArrivalByStation(stationName);
            cache.put(stationName, fresh);
            return fresh;
        }, props.getJoinWaitMs());

        metrics.recordSingleFlight(outcome.role());
        // 합류에 실패한 요청은 빈 목록이다. 부르는 쪽이 "모름"으로 내려보낸다 — 시간표로 지어내지 않는다.
        return outcome.hasValue() ? outcome.value() : List.of();
    }
}
