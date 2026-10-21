package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog.ExpressService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 공용 정차 패턴 해석기(subway-express PR-S1). 경로 검색(S2)·실시간 표시(S3)·급행 추적(S4)이 같은 답을 쓰도록 한 곳에 둔다.
 *
 * <ul>
 *   <li>{@link #allStopPath} — 완행으로 두 역 사이를 갈 때 지나는 역({@link SubwayLineOrder#allStopPath})</li>
 *   <li>{@link #resolve} — 급행 열차 하나의 서비스와 서는 역, <b>그 열차 종착역까지로 자른</b> 운행 범위</li>
 *   <li>{@link #trim} — 완행 경로를 서는 역만 남기고, 각 정차역 앞에서 지나친 역을 붙인다</li>
 * </ul>
 *
 * <p>모르는 것은 지어내지 않는다 — 서비스를 못 고르거나, 각역 구간을 못 펼치거나, 종착역이 패턴에 없으면 empty 다.
 * 호출부는 empty 를 "추적 불가"로 다룬다.
 */
@Component
public class SubwayStopPatternResolver {

    private final SubwayExpressCatalog catalog;
    private final SubwayLineOrder lineOrder;

    public SubwayStopPatternResolver(SubwayExpressCatalog catalog, SubwayLineOrder lineOrder) {
        this.catalog = catalog;
        this.lineOrder = lineOrder;
    }

    /**
     * 해석된 급행 정차 패턴.
     *
     * @param stopIds           진행 순서 정차역 id. 종착역을 알면 거기까지
     * @param terminalStationId 자른 종착역 id. 몰라서 안 잘랐으면 null
     */
    public record ResolvedPattern(ExpressService service, String direction, List<String> stopIds,
                                  String terminalStationId) {

        public ResolvedPattern {
            stopIds = List.copyOf(stopIds);
        }

        public String serviceId() {
            return service.id();
        }

        public String catalogVersion() {
            return service.catalogVersion();
        }

        public boolean stopsAt(String stationId) {
            return stationId != null && stopIds.contains(stationId);
        }
    }

    /**
     * 정차역 하나와 그 앞에서 지나친 역(진행 순서).
     *
     * @param passingBefore 직전 정차역(또는 경로 시작)과 이 역 사이에서 서지 않고 지나는 역
     */
    public record PatternStop(String stationId, List<String> passingBefore) {

        public PatternStop {
            passingBefore = List.copyOf(passingBefore);
        }
    }

    /** @see SubwayLineOrder#allStopPath */
    public List<String> allStopPath(String lineId, String direction, String boardStationId, String alightStationId) {
        return lineOrder.allStopPath(lineId, direction, boardStationId, alightStationId);
    }

    /**
     * 실시간 방향 라벨을 모를 때의 완행 경로(subway-express S2 경로 검색·geometry). 노선의 두 방향 중 두 역을 이 순서로
     * 지나는 쪽이 하나면 그 경로다. 둘 다면(순환선) {@code preferredDirection} 쪽, 그것도 아니면 empty — 고르지 않는다.
     *
     * <p>OTP 노선 라벨은 노선마다 실시간과 반대일 수 있어({@code SubwayRoutePatternAssembler} 반전표) 라벨 대신 역 순서로
     * 정한다. 직선 노선은 두 역을 이 순서로 지나는 방향이 하나뿐이다.
     *
     * @param preferredDirection 둘 다 지날 때 고를 방향. 모르면 null
     */
    public List<String> allStopPathEitherDirection(String lineId, String preferredDirection, String boardStationId,
                                                   String alightStationId) {
        if (lineId == null) {
            return List.of();
        }
        List<String> found = List.of();
        String foundDirection = null;
        for (String direction : SubwayLinePatternWarmup.directions(lineId)) {
            List<String> path = lineOrder.allStopPath(lineId, direction, boardStationId, alightStationId);
            if (path.isEmpty()) {
                continue;
            }
            if (!found.isEmpty()) {
                if (direction.equals(preferredDirection)) {
                    return path;
                }
                return foundDirection.equals(preferredDirection) ? found : List.of();
            }
            found = path;
            foundDirection = direction;
        }
        return found;
    }

    /**
     * 급행 열차 하나의 정차 패턴. 호출부는 이미 급행으로 판정한 열차만 넘긴다.
     *
     * @param destination      행선지 이름이나 종착 역 id(위치 기반 출발 기록은 이름 없이 terminalId 만 있다)
     * @param currentStationId 지금 있는(또는 막 지난) 역 id. 모르면 null
     */
    public Optional<ResolvedPattern> resolve(String lineId, String direction, String trainNo, String destination,
                                             String currentStationId) {
        SubwayTrainIdentity train = SubwayTrainIdentity.of(lineId, direction, trainNo, destination);
        if (train.direction() == null) {
            return Optional.empty();
        }
        Optional<ExpressService> service = catalog.resolve(train, currentStationId);
        if (service.isEmpty()) {
            return Optional.empty();
        }
        Optional<SubwayStopPattern> pattern = catalog.pattern(service.get().id(), train.direction(), train.trainNo(),
                lineOrder::allStopPath);
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        String terminal = train.terminalId() != null
                ? train.terminalId()
                : catalog.stationId(lineId, train.destinationName()).orElse(null);
        List<String> stops = pattern.get().stopIds();
        if (terminal == null) {
            return Optional.of(new ResolvedPattern(service.get(), train.direction(), stops, null));
        }
        int at = stops.indexOf(terminal);
        if (at < 0) {
            // 종착역이 이 서비스가 서는 역이 아니다 — 패턴이나 서비스 고르기가 틀렸다는 뜻이라 답하지 않는다
            return Optional.empty();
        }
        return Optional.of(new ResolvedPattern(service.get(), train.direction(), stops.subList(0, at + 1), terminal));
    }

    /**
     * 바인딩 때 해석해 둔 서비스로 정차 패턴을 다시 만든다(subway-express PR-S4 링). 서비스는 바꾸지 않는다 — 여정 도중
     * 카탈로그가 바뀌어도 그 서비스 id 를 계속 쓴다. 열차별 예외 정차는 {@code trainNo} 로 지금 다시 계산한다.
     *
     * @param destination 행선지 이름이나 종착 역 id. 알면 종착역까지로 자르고, 모르면(null) 자르지 않는다
     * @return 서비스가 카탈로그에서 사라졌거나 방향·각역 구간을 못 펼치면 empty
     */
    public Optional<ResolvedPattern> resolveService(String serviceId, String lineId, String direction, String trainNo,
                                                    String destination) {
        if (serviceId == null) {
            return Optional.empty();
        }
        SubwayTrainIdentity train = SubwayTrainIdentity.of(lineId, direction, trainNo, destination);
        Optional<ExpressService> service = catalog.service(serviceId);
        if (train.direction() == null || service.isEmpty() || !service.get().lineId().equals(lineId)) {
            return Optional.empty();
        }
        Optional<SubwayStopPattern> pattern = catalog.pattern(serviceId, train.direction(), train.trainNo(),
                lineOrder::allStopPath);
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        List<String> stops = pattern.get().stopIds();
        String terminal = train.terminalId() != null ? train.terminalId()
                : train.destinationName() == null ? null : catalog.stationId(lineId, train.destinationName()).orElse(null);
        int at = terminal == null ? -1 : stops.indexOf(terminal);
        if (at < 0) {
            return Optional.of(new ResolvedPattern(service.get(), train.direction(), stops, null));
        }
        return Optional.of(new ResolvedPattern(service.get(), train.direction(), stops.subList(0, at + 1), terminal));
    }

    /**
     * 완행 경로를 정차역만 남긴다. 각 정차역에 직전 정차역과의 사이에서 지나친 역({@code passingBefore})을 붙인다.
     * 마지막 정차역 뒤에 남은 통과역은 버린다 — 하차역이 그 안이면 이 열차는 하차역에 서지 않는다는 뜻이고, 그 판단은
     * {@link ResolvedPattern#stopsAt} 으로 한다.
     *
     * @param allStops 진행 순서 완행 경로({@link #allStopPath})
     */
    public static List<PatternStop> trim(List<String> allStops, ResolvedPattern pattern) {
        List<PatternStop> out = new ArrayList<>();
        List<String> passing = new ArrayList<>();
        for (String stationId : allStops) {
            if (pattern.stopsAt(stationId)) {
                out.add(new PatternStop(stationId, passing));
                passing = new ArrayList<>();
            } else {
                passing.add(stationId);
            }
        }
        return List.copyOf(out);
    }
}
