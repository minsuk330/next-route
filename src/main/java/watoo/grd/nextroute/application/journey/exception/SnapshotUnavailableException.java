package watoo.grd.nextroute.application.journey.exception;

/**
 * 검색 결과 스냅샷을 찾을 수 없다. 만료됐거나 {@code pathKey} 가 그 스냅샷에 없다.
 *
 * <p>둘을 구분해 알리지 않는다. 앱이 할 일은 어느 쪽이든 "다시 검색"으로 같고, 남의
 * {@code searchSessionId} 를 찍어 보는 쪽에 "그 세션은 있는데 경로 번호가 틀렸다"를 알려 줄
 * 이유가 없다.
 */
public class SnapshotUnavailableException extends RuntimeException {

    public SnapshotUnavailableException() {
        super("검색 결과가 만료됐다");
    }
}
