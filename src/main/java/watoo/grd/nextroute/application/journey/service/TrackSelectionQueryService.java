package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 단독 추적(#265) 선택 화면 조회: 좌표 → 정류장/역, 정류장 → 노선·방향, 방향 → 하차 후보.
 *
 * <p>플래그({@code journey.track.enabled})가 꺼져 있으면 404 다 — 조회도 OTP 를 부른다.
 */
@Service
@RequiredArgsConstructor
public class TrackSelectionQueryService {

    private final JourneyTrackProperties properties;
    private final TrackPatternSource source;

    /**
     * @param mode {@code BUS} 또는 {@code SUBWAY}(철도·경전철 포함). null 이면 전부
     */
    public List<TrackViews.Stop> stopsNear(double lat, double lon, RoutingMode mode) {
        requireEnabled();
        List<TransitStop> stops = source.withPermit(deadline -> source.port().stopsNear(lat, lon,
                properties.getNearRadiusMeters(), properties.getNearLimit(), deadline));
        return stops.stream()
                .filter(stop -> matches(mode, stop.mode()))
                .map(stop -> new TrackViews.Stop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), stop.mode()))
                .toList();
    }

    /** 정류장에서 탈 수 있는 노선·방향. 노선 이름 순. */
    public List<TrackViews.Direction> directions(String gtfsStopId) {
        requireEnabled();
        List<TransitPattern> patterns = source.withPermit(deadline -> source.patternsAtStop(gtfsStopId, deadline));
        List<TrackViews.Direction> directions = new ArrayList<>();
        for (TrackPatternSelector.Branch branch : TrackPatternSelector.branches(gtfsStopId, patterns)) {
            TransitPattern pattern = branch.pattern();
            if (pattern.route().mode() == RoutingMode.UNSUPPORTED || pattern.route().mode() == RoutingMode.WALK) {
                continue;
            }
            directions.add(new TrackViews.Direction(pattern.route().gtfsId(), pattern.route().mode(),
                    pattern.route().shortName(), color(pattern.route().color()), branch.directionLabel(),
                    branch.terminalName(), pattern.code()));
        }
        directions.sort((a, b) -> compareNullable(a.routeName(), b.routeName()));
        return directions;
    }

    /**
     * 그 노선·방향으로 {@code fromStopId} 다음부터 갈 수 있는 정류장(한 바퀴 안). 패턴 코드가 낡았으면 노선으로 다시 찾는다.
     *
     * @throws BusinessException 422 {@code TRACK_STOPS_INVALID} — 그 노선이 그 정류장을 안 지난다
     */
    public List<TrackViews.AlightStop> alightStops(String routeGtfsId, String fromStopId, String patternCode) {
        requireEnabled();
        return source.withPermit(deadline -> {
            List<TransitPattern> patterns = source.patternsAtStop(fromStopId, deadline);
            TrackPatternSelector.Branch branch = TrackPatternSelector
                    .branchFor(routeGtfsId, fromStopId, patternCode, patterns)
                    .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID));
            Optional<TransitPatternDetail> detail = source.pattern(branch.pattern().code(), deadline);
            if (detail.isEmpty()) {
                throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
            }
            TrackPatternSelector.Lap lap = TrackPatternSelector.lap(branch.pattern(), fromStopId);
            List<TransitStop> stops = detail.get().stops();
            List<TrackViews.AlightStop> result = new ArrayList<>();
            for (int k = 0; k < lap.remaining().size(); k++) {
                int index = lap.boardIndex() + 1 + k;
                if (index >= stops.size()) {
                    break;
                }
                TransitStop stop = stops.get(index);
                result.add(new TrackViews.AlightStop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), k));
            }
            return result;
        });
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    private static boolean matches(RoutingMode wanted, RoutingMode actual) {
        if (wanted == null) {
            return actual != RoutingMode.UNSUPPORTED;
        }
        if (wanted == RoutingMode.SUBWAY) {
            return actual == RoutingMode.SUBWAY || actual == RoutingMode.RAIL;
        }
        return wanted == actual;
    }

    private static String color(String raw) {
        return raw == null ? null : "#" + raw;
    }

    private static int compareNullable(String a, String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareTo(b);
    }
}
