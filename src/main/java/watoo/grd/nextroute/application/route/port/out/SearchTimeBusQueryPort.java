package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;

import java.util.List;

/**
 * 경로검색 전용 버스 실시간 조회 포트.
 * 재시도 없음, 단축 timeout (~1.5s). 검색 레이턴시 보호용.
 *
 * <p>반환은 {@link BusQueryResult}로 <b>조회 성공(빈 결과 포함)</b>과 <b>차단/제한/오류</b>를 구분한다.
 * 빈 리스트를 그대로 돌려주면 "버스 없음"(NO_VEHICLE)과 "조회 못 함"(BLOCKED/LIMITED)이 섞여 오분류된다.
 */
public interface SearchTimeBusQueryPort {

    /**
     * 특정 정류소·노선·경유순번의 실시간 도착예정정보 (서울 {@code /arrive/getArrInfoByRoute}).
     * 응답은 해당 노선 1건. 호출 전 seq(ord)가 확정돼야 한다.
     */
    BusQueryResult<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, int ord);

    /**
     * 요청 전체 deadline에서 배정받은 호출 예산. 구현체는 동시 슬롯 대기와 HTTP 응답 대기를
     * 합쳐 이 시간 안에 종료해야 한다. 기존 비검색 호출과 테스트 대역은 기본 메서드를 사용한다.
     */
    default BusQueryResult<BusArrivalInfo> getArrInfoByStop(
            String stopId, String routeId, int ord, long timeoutMs) {
        return getArrInfoByStop(stopId, routeId, ord);
    }

    BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId);

    default BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId, long timeoutMs) {
        return getBusPosByRtid(busRouteId);
    }

    /**
     * 노선 하나의 전 정류장 도착(서울 {@code /arrive/getArrInfoByRouteAll}). 재평가 감시자(#415-3) 전용.
     *
     * <p>검색 몫·검색 동시 슬롯을 쓰지 않는다 — 부르는 쪽이 자기 일일 몫을 따로 확인한다. 공유 차단기는
     * 따른다(code 7 이면 모두가 멈춰야 한다). 재시도 없이 {@code timeoutMs} 안에 끝난다.
     *
     * <p>기본 구현은 {@code ERROR} 다. 시험 대역이 이 메서드를 몰라도 깨지지 않게.
     */
    default BusQueryResult<BusArrivalInfo> getArrInfoByRouteAll(String routeId, long timeoutMs) {
        return BusQueryResult.error();
    }

    /**
     * @param outcome  조회 결과 종류.
     * @param data     OK일 때만 의미 있음(빈 리스트면 실제 "버스 없음"). 그 외엔 빈 리스트.
     * @param cacheHit provider 호출 없이 캐시에서 반환됐는지(검색 호출 cap·quota 미소모).
     */
    record BusQueryResult<T>(Outcome outcome, List<T> data, boolean cacheHit) {
        public static <T> BusQueryResult<T> ok(List<T> data) {
            return new BusQueryResult<>(Outcome.OK, data, false);
        }
        public static <T> BusQueryResult<T> cached(List<T> data) {
            return new BusQueryResult<>(Outcome.OK, data, true);
        }
        public static <T> BusQueryResult<T> blocked() {
            return new BusQueryResult<>(Outcome.BLOCKED, List.of(), false);
        }
        public static <T> BusQueryResult<T> limited() {
            return new BusQueryResult<>(Outcome.LIMITED, List.of(), false);
        }
        public static <T> BusQueryResult<T> error() {
            return new BusQueryResult<>(Outcome.ERROR, List.of(), false);
        }
        public boolean isOk() { return outcome == Outcome.OK; }
    }

    enum Outcome {
        /** 정상 조회(빈 리스트면 실제 버스 없음). */
        OK,
        /** 공유 circuit breaker 차단(provider error code 7 등). */
        BLOCKED,
        /** 검색 몫 quota 소진 / 동시 슬롯 부족 / per-search 호출 상한 초과. */
        LIMITED,
        /** 호출 예외. */
        ERROR
    }
}
