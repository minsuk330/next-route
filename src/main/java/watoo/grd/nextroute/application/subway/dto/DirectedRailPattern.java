package watoo.grd.nextroute.application.subway.dto;

import java.util.List;

/**
 * 실시간 방향 라벨(상행·하행·내선·외선)에 맞춰 놓은 패턴.
 *
 * <p>OTP 라벨을 그대로 믿을 수 없어서 이 단계가 따로 있다. 경의중앙선 서울역 노선은 라벨이
 * 실시간과 반대이고, 6호선은 방향이 아예 없다. 그 보정을 거친 결과다.
 *
 * @param loop 순환 노선(내선·외선)인지. 승차역이 다시 나오면 한 바퀴가 끝난 것으로 읽는 근거다
 */
public record DirectedRailPattern(
        String routeGtfsId,
        String routeLongName,
        boolean loop,
        int tripCount,
        List<RailPattern.Stop> stops
) {
}
