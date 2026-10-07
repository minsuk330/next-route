package watoo.grd.nextroute.application.subway.dto;

/**
 * OTP(GTFS) 노선 하나. feed 는 노선×방향(×지선)마다 따로 둔다 — {@code 서울2호선(본선)<내선>}.
 *
 * @param gtfsId    {@code 1:RR_ACC1_S-1-02-1I}
 * @param shortName {@code 서울2호선}. 내부 line_id 를 정하는 근거다
 * @param longName  {@code 서울2호선(본선)<내선>}. 방향·급행 여부를 들고 있다
 */
public record OtpRailRoute(String gtfsId, String shortName, String longName) {
}
