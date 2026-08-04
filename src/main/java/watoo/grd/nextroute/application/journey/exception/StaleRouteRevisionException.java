package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;

/**
 * 앱이 이전 경로 개정본을 가리켰다.
 *
 * <p>사용자가 잘못한 것이 아니라 그 사이 경로가 교체된 것이므로 거절이 아니라 충돌이다. 앱은
 * 현재 개정본으로 좌표를 다시 받아 지도·진행 엔진을 통째로 교체해야 한다.
 */
@Getter
public class StaleRouteRevisionException extends RuntimeException {

    private final int currentRouteRevision;

    public StaleRouteRevisionException(int currentRouteRevision) {
        super("이전 경로 개정본이다. 현재=" + currentRouteRevision);
        this.currentRouteRevision = currentRouteRevision;
    }
}
