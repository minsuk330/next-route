package watoo.grd.nextroute.application.notification.port.out;

import java.time.LocalDateTime;

/**
 * 출발지→도착지 소요시간(초) 추정 추상화. 현재 구현은 경로검색 재사용이나, notification 쪽은
 * "경로 전체 검색"이 아니라 "소요시간 추정"만 의존한다(캐시/ODsay 대체/ML 보정 격리 목적).
 */
public interface RouteTravelTimeEstimator {

    /**
     * @param query 출발/도착 좌표 및 기준시각
     * @return 추정 소요시간(초)
     * @throws IllegalStateException 경로를 찾을 수 없을 때
     */
    int estimateSeconds(TravelTimeQuery query);

    /** {@code at} 은 미래 목표시각 기준 추정을 위한 힌트(현 ODsay 는 출발시각 파라미터 미지원, 참고용). */
    record TravelTimeQuery(double startX, double startY, String startName,
                           double endX, double endY, String endName,
                           LocalDateTime at) {
    }
}
