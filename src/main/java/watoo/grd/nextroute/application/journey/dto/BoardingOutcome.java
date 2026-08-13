package watoo.grd.nextroute.application.journey.dto;

/**
 * 승차 시각 추정의 결과. {@code Optional<BoardingEstimate>} 로 두지 않는 이유는 하나다.
 *
 * <p>답이 없는 경우가 <b>두 가지</b>이고 대응이 다르다. "물어봤지만 근거가 없다"는 다음 주기에
 * 다시 물어보면 되지만, "막혀서 물어보지도 못했다"는 30초마다 다시 부르면 막힌 문을 계속
 * 두드리는 셈이다. 둘을 하나로 뭉치면 그 구분이 사라진다.
 *
 * @param estimate 추정 결과. 없으면 null
 * @param blocked  물어보는 것 자체가 막혔는가(차단·호출 한도)
 */
public record BoardingOutcome(BoardingEstimate estimate, boolean blocked) {

    public boolean isPresent() {
        return estimate != null;
    }

    /** 물어봤지만 근거가 없다. */
    public static BoardingOutcome none() {
        return new BoardingOutcome(null, false);
    }

    /** 물어보는 것 자체가 막혔다. 이름이 {@code blocked} 가 아닌 것은 접근자와 겹쳐서다. */
    public static BoardingOutcome ofBlocked() {
        return new BoardingOutcome(null, true);
    }

    public static BoardingOutcome of(BoardingEstimate estimate) {
        return new BoardingOutcome(estimate, false);
    }
}
