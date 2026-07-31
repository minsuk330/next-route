package watoo.grd.nextroute.application.journey.exception;

/**
 * 남이 검색한 결과로 여정을 시작하려 했다.
 *
 * <p>로그인 없이 검색한 결과({@code ownerUserId} 가 비어 있음)로 시작하는 것은 허용한다(D4).
 * 검색은 누구나 할 수 있고, 그 결과로 자기 여정을 시작하는 것은 남의 것을 가져오는 것이 아니다.
 * 막아야 할 것은 <b>다른 사람의</b> 검색 결과를 집어 가는 경우다.
 */
public class SnapshotOwnerMismatchException extends RuntimeException {

    public SnapshotOwnerMismatchException() {
        super("다른 사용자의 검색 결과다");
    }
}
