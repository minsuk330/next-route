package watoo.grd.nextroute.application.route.mapping;

import java.util.regex.Pattern;

/**
 * 서울 버스 id → OTP GTFS id(#298 A안). 앱이 서울 id 로 고른 정류장·노선을 단독 추적 시작(GTFS id)에 넘기게 한다.
 *
 * <p>{@link OtpSeoulIdentityResolver} 의 역방향이다({@code ^\d+:BS_TAGO_SEB(\d{9})$}, {@code ^\d+:BR_TAGO_SEB(\d{9})(?:_[UD])?$}).
 * feed 접두사는 {@code 1:}(2026-10-07 실측 전 정류장). 공항버스는 방향별로 {@code _U}/{@code _D} 가 붙는데 서울 노선
 * id 로는 어느 쪽인지 모른다 — 접미사 없이 내고 시작 쪽 노선 비교가 흡수한다({@code TrackPatternSelector}).
 *
 * <p>서울 9자리 숫자가 아니면 null — 지어내지 않는다.
 */
public final class OtpGtfsIds {

    private static final String FEED = "1:";
    private static final Pattern SEOUL_ID = Pattern.compile("^\\d{9}$");

    private OtpGtfsIds() {
    }

    public static String seoulBusStop(String stopId) {
        return stopId != null && SEOUL_ID.matcher(stopId).matches() ? FEED + "BS_TAGO_SEB" + stopId : null;
    }

    public static String seoulBusRoute(String routeId) {
        return routeId != null && SEOUL_ID.matcher(routeId).matches() ? FEED + "BR_TAGO_SEB" + routeId : null;
    }
}
