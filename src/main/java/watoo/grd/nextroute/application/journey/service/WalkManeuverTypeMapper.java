package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.ManeuverType;

import java.util.Map;

/**
 * TMAP 보행자 안내의 회전 코드를 계약 타입으로 바꾼다.
 *
 * <p>이 경계가 있어야 외부 규격 숫자가 API 응답으로 새지 않는다. TMAP 이 코드를 늘리거나 뜻을
 * 바꿔도 앱은 {@link ManeuverType} 만 안다.
 *
 * <p>표에 없는 코드는 {@link ManeuverType#UNKNOWN} 이다. 추측해서 표를 늘리지 않는다 — 틀린 방향을
 * 자신 있게 말하는 것이 모른다고 말하는 것보다 나쁘다. 앱은 UNKNOWN 이면 안내 문구를 그대로 읽는다.
 */
@Slf4j
@Component
public class WalkManeuverTypeMapper {

    private static final String POINT_TYPE_START = "SP";
    private static final String POINT_TYPE_END = "EP";

    /**
     * TMAP 보행자 경로안내 {@code turnType} 표.
     *
     * <p>시설 코드(육교·지하보도·계단·경사로·보행자전용도로·횡단보도)는 회전이 아니라 "지나가는
     * 곳"이라 {@link ManeuverType#STRAIGHT} 로 접는다. 안내 문구가 시설 이름을 들고 있다.
     */
    private static final Map<Integer, ManeuverType> BY_TURN_TYPE = Map.ofEntries(
            Map.entry(11, ManeuverType.STRAIGHT),
            Map.entry(12, ManeuverType.LEFT),
            Map.entry(13, ManeuverType.RIGHT),
            Map.entry(14, ManeuverType.UTURN),
            Map.entry(16, ManeuverType.SHARP_LEFT),
            Map.entry(17, ManeuverType.SLIGHT_LEFT),
            Map.entry(18, ManeuverType.SLIGHT_RIGHT),
            Map.entry(19, ManeuverType.SHARP_RIGHT),
            // 경유지 계열
            Map.entry(184, ManeuverType.STRAIGHT),
            Map.entry(185, ManeuverType.STRAIGHT),
            // 시설 진입 계열
            Map.entry(125, ManeuverType.STRAIGHT),
            Map.entry(126, ManeuverType.STRAIGHT),
            Map.entry(127, ManeuverType.STRAIGHT),
            Map.entry(128, ManeuverType.STRAIGHT),
            Map.entry(129, ManeuverType.STRAIGHT),
            Map.entry(130, ManeuverType.STRAIGHT),
            Map.entry(131, ManeuverType.STRAIGHT),
            // 출발·도착. 지점 종류로도 잡히지만 코드만 오는 경우가 있어 표에도 둔다.
            Map.entry(200, ManeuverType.DEPART),
            Map.entry(201, ManeuverType.ARRIVE)
    );

    /**
     * @param pointType TMAP 지점 종류. {@code SP}(출발)·{@code EP}(도착)·그 외
     * @param turnType  TMAP 회전 코드. 없을 수 있다
     */
    public ManeuverType map(String pointType, Integer turnType) {
        // 지점 종류가 먼저다. 출발·도착은 회전 코드가 무엇이든 출발·도착이다.
        if (POINT_TYPE_END.equals(pointType)) {
            return ManeuverType.ARRIVE;
        }
        if (POINT_TYPE_START.equals(pointType)) {
            return ManeuverType.DEPART;
        }
        if (turnType == null) {
            return ManeuverType.UNKNOWN;
        }
        ManeuverType mapped = BY_TURN_TYPE.get(turnType);
        if (mapped == null) {
            // 표를 넓힐 근거를 남긴다. 코드 숫자뿐이라 개인정보가 아니다.
            log.debug("도보 안내 회전 코드 미등록 — turnType={}", turnType);
            return ManeuverType.UNKNOWN;
        }
        return mapped;
    }
}
