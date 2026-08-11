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
     * TMAP <b>보행자</b> 경로안내 {@code turnType} 표.
     *
     * <p>출처는 TMAP 공식 문서 「경로안내 샘플예제」의 보행자 응답 스펙이다
     * ({@code https://tmap-skopenapi.readme.io/reference/경로안내-샘플예제}).
     *
     * <p><b>자동차 경로안내 표를 옮겨 쓰면 안 된다.</b> 코드 뜻이 다르다 — 130·131 은 자동차에만
     * 있고(토끼굴 진입·1시 방향), 보행자에는 없다. 옮겨 쓰면 틀린 방향을 자신 있게 말하게 된다.
     *
     * <p>방향이 없는 시설 코드(육교·지하보도·계단·경사로·엘리베이터)는 회전이 아니라 "지나가는
     * 곳"이라 {@link ManeuverType#STRAIGHT} 로 접는다. 안내 문구가 시설 이름을 들고 있다.
     * 횡단보도(211~217)만 {@link ManeuverType#CROSSWALK} 로 따로 둔다.
     */
    private static final Map<Integer, ManeuverType> BY_TURN_TYPE = Map.ofEntries(
            // 1~7: "안내 없음". 안내점으로 오긴 하지만 회전이 아니다.
            Map.entry(1, ManeuverType.STRAIGHT),
            Map.entry(2, ManeuverType.STRAIGHT),
            Map.entry(3, ManeuverType.STRAIGHT),
            Map.entry(4, ManeuverType.STRAIGHT),
            Map.entry(5, ManeuverType.STRAIGHT),
            Map.entry(6, ManeuverType.STRAIGHT),
            Map.entry(7, ManeuverType.STRAIGHT),
            Map.entry(11, ManeuverType.STRAIGHT),
            Map.entry(12, ManeuverType.LEFT),
            Map.entry(13, ManeuverType.RIGHT),
            Map.entry(14, ManeuverType.UTURN),
            Map.entry(16, ManeuverType.SHARP_LEFT),
            Map.entry(17, ManeuverType.SLIGHT_LEFT),
            Map.entry(18, ManeuverType.SLIGHT_RIGHT),
            Map.entry(19, ManeuverType.SHARP_RIGHT),
            // 시설 진입 계열 — 방향 정보가 없다.
            Map.entry(125, ManeuverType.STRAIGHT),   // 육교
            Map.entry(126, ManeuverType.STRAIGHT),   // 지하보도
            Map.entry(127, ManeuverType.STRAIGHT),   // 계단 진입
            Map.entry(128, ManeuverType.STRAIGHT),   // 경사로 진입
            Map.entry(129, ManeuverType.STRAIGHT),   // 계단+경사로 진입
            Map.entry(218, ManeuverType.STRAIGHT),   // 엘리베이터
            // 경유지 계열
            Map.entry(184, ManeuverType.STRAIGHT),
            Map.entry(185, ManeuverType.STRAIGHT),
            Map.entry(186, ManeuverType.STRAIGHT),
            Map.entry(187, ManeuverType.STRAIGHT),
            Map.entry(188, ManeuverType.STRAIGHT),
            Map.entry(189, ManeuverType.STRAIGHT),
            // 출발·도착. 지점 종류로도 잡히지만 코드만 오는 경우가 있어 표에도 둔다.
            Map.entry(200, ManeuverType.DEPART),
            Map.entry(201, ManeuverType.ARRIVE),
            // 횡단보도 계열. 좌측·우측·8시·10시·2시·4시 구분은 안내 문구에 남는다.
            Map.entry(211, ManeuverType.CROSSWALK),
            Map.entry(212, ManeuverType.CROSSWALK),
            Map.entry(213, ManeuverType.CROSSWALK),
            Map.entry(214, ManeuverType.CROSSWALK),
            Map.entry(215, ManeuverType.CROSSWALK),
            Map.entry(216, ManeuverType.CROSSWALK),
            Map.entry(217, ManeuverType.CROSSWALK),
            Map.entry(233, ManeuverType.STRAIGHT)    // 직진 임시
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
