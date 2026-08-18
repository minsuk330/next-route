package watoo.grd.nextroute.application.subway.service;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "조회역에서 <b>몇 정거장 떨어져</b> 있는가"를 낸다. 값은 언제나 조회한 역 기준이다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@code barvlDt}(남은 초)는 실측 표본의 절반이 0 이고, 그중 상당수가 <b>미산출</b>이다 —
 * 원천이 "전역 도착", "[3]번째 전역" 처럼 <b>거리</b>로만 답하고 시간을 계산해 주지 않는다.
 * 초가 없다고 그 열차를 화면에서 지우면 사용자는 오고 있는 열차를 통째로 못 본다. 초가 없으면
 * 거리라도 준다.
 *
 * <p>거리는 {@link SubwayArrivalCode} 가 대부분 이미 말해 준다. 코드로 답이 나오지 않는 자리
 * ({@link SubwayArrivalCode#ENROUTE})에서만 {@code arvlMsg2} 문구를 읽는다.
 *
 * <h2>문구를 최후에 두는 이유</h2>
 *
 * <p>{@code arvlMsg2} 는 사람이 읽으라고 만든 문자열이라 형식이 고정이라는 보장이 없다. 코드가
 * 답할 수 있는 것을 문구로 답하게 두면, 원천이 표현을 한 번 바꾸는 날 거리 표기가 통째로
 * 사라진다. 그래서 문구는 코드가 침묵할 때만 본다.
 *
 * <p>읽어 내지 못하면 {@code null} 이다 — 지어내지 않는다. 부르는 쪽은 {@code null} 을
 * "거리 모름"으로 다루고 {@code arvlMsg2} 원문을 그대로 보여 주면 된다.
 */
public final class SubwayStationsAway {

    /** {@code "[3]번째 전역"}, {@code "[2]번째 전역 (강남)"} 을 읽는다. */
    private static final Pattern NTH_PREVIOUS_STATION =
            Pattern.compile("\\[(\\d{1,2})]\\s*번째\\s*전역");

    private SubwayStationsAway() {
    }

    /**
     * @param code           {@code arvlCd} 를 해석한 값
     * @param arrivalMessage {@code arvlMsg2} 원문
     * @return 조회역까지 남은 정거장 수. 조회역에 있으면 {@code 0}, 알 수 없으면 {@code null}
     */
    public static Integer resolve(SubwayArrivalCode code, String arrivalMessage) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            // 진입·도착은 조회역에 와 있다는 뜻이다. 남은 거리가 없다.
            case ENTERING, ARRIVED -> 0;
            // 전역 출발·진입·도착. 한 정거장 남았다.
            case PREV_DEPARTED, PREV_ENTERING, PREV_ARRIVED -> 1;
            // 초가 있으면 그쪽이 더 정확하다. 여기서 거리를 읽어 내는 것은 초가 없을 때뿐이다.
            case ENROUTE -> parseNthPreviousStation(arrivalMessage);
            // 조회역을 이미 출발했다. "남은 거리"라는 질문 자체가 성립하지 않는다.
            case DEPARTED, UNKNOWN -> null;
        };
    }

    private static Integer parseNthPreviousStation(String arrivalMessage) {
        if (arrivalMessage == null || arrivalMessage.isBlank()) {
            return null;
        }
        Matcher matcher = NTH_PREVIOUS_STATION.matcher(arrivalMessage);
        if (!matcher.find()) {
            // "5분 30초 후" 처럼 시간으로 답한 경우다. 거리는 없지만 초가 있으므로 손해가 없다.
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
