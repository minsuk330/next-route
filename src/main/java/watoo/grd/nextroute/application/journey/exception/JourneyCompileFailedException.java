package watoo.grd.nextroute.application.journey.exception;

/**
 * 경로를 단계로 쪼개지 못했다. 스냅샷은 살아 있지만 안에 구간이 없다.
 *
 * <p>만료(410)와 구분한다. 다시 검색하라고 안내해야 하는 것은 같지만, 이쪽은 <b>서버가 만든
 * 결과가 이상하다</b>는 뜻이라 로그로 남겨 원인을 봐야 한다. 만료로 묻어 두면 그 신호가 사라진다.
 */
public class JourneyCompileFailedException extends RuntimeException {

    public JourneyCompileFailedException() {
        super("경로를 여정 단계로 쪼갤 수 없다");
    }
}
