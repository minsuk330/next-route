package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.util.List;

/**
 * 하나의 경로 후보. 시간은 초 단위로 보존한다(effective total 은 최종도착-요청출발; 분 반올림은 표시 최종단계).
 * WAIT/RIDE 를 분리해 담아 ETA composer 가 대기를 중복 가산하지 않게 한다.
 *
 * @param staticArrivalAt          보강 전 정적 도착시각
 * @param moveSeconds              이동(승차) 초 합
 * @param waitSeconds              대기 초 합(boardAt-userReadyAt; buffer 포함, 중복가산 금지)
 * @param walkSeconds              도보 초 합
 * @param navigationSupported      서버 판정(mode+ID매핑+geometry OK+진행계약). 클라 값 불신
 * @param navigationBlockedReasons 미지원 사유(코드 문자열)
 */
public record RoutingItinerary(
        Instant staticArrivalAt,
        int moveSeconds,
        int waitSeconds,
        int walkSeconds,
        int transferCount,
        boolean navigationSupported,
        List<String> navigationBlockedReasons,
        Fare fare,
        List<RoutingLeg> legs
) {
    public RoutingItinerary {
        navigationBlockedReasons = navigationBlockedReasons == null
                ? List.of() : List.copyOf(navigationBlockedReasons);
        legs = legs == null ? List.of() : List.copyOf(legs);
        if (fare == null) {
            fare = Fare.unknown();
        }
    }
}
