package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

import java.time.Instant;

public record TransferArrival(
        String routeId,
        int laneIndex,
        Source source,
        Status status,
        Instant calculatedAt,
        Instant userArrivalAt,
        Instant predictedArrivalAt,
        Long waitSeconds,
        String vehicleId,
        String modelVersion,
        Integer basisLaneIndex,
        Boolean conditional
) {
    /**
     * 대기의 근거. 모르는 값(다른 버전 서버가 저장한 스냅샷 — 2026-11-09 잠깐 배포된 SCHEDULE 포함)은 NONE 으로 읽는다.
     * spring.jackson.deserialization 설정과 짝.
     */
    public enum Source { REALTIME, MODEL, @JsonEnumDefaultValue NONE }

    public enum Status {
        AVAILABLE,
        ARRIVAL_TIME_APPROXIMATE,
        /**
         * 다음 버스 도착 시각만 안다 — 앞 버스 결과가 없어 이 정류장에 언제 닿을지 몰라 환승 대기는 계산하지 않는다
         * ({@code predictedArrivalAt} = 지금부터 가장 이른 도착, {@code waitSeconds}·{@code userArrivalAt} = null).
         * 다음 버스 계산에도 쓰지 않는다(search-regional-bus-wait PR-2).
         */
        ARRIVAL_ONLY,
        DISABLED,
        UNSUPPORTED_ROUTE,
        STOP_MAPPING_FAILED,
        NO_VEHICLE,
        MODEL_UNAVAILABLE,
        UPSTREAM_UNAVAILABLE,
        /** 공유 circuit breaker 차단(provider error code 7 등)으로 조회 못 함. */
        BLOCKED,
        /** quota 소진·동시성·per-search 호출 상한으로 조회 생략. */
        LIMITED,
        /** 모르는 값(다른 버전 서버가 저장한 스냅샷)도 여기로 읽는다. */
        @JsonEnumDefaultValue
        ERROR
    }
}
