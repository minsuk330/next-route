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
        Boolean conditional,
        /**
         * 이 lane 의 버스({@code predictedArrivalAt})를 타면 다음 버스 구간에서 lane 별로 얼마나 기다리는가. 다음 구간이 버스가
         * 아니거나, 이 lane 을 탈 수 없거나({@code AVAILABLE}·{@code ARRIVAL_TIME_APPROXIMATE} 가 아님), 다음 구간 실시간을 모르면
         * null. 옛 응답·스냅샷에는 없다(null).
         */
        java.util.List<OnwardWait> onwardWaits
) {
    /** 다음 구간 대기를 싣지 않는 호출부용. */
    public TransferArrival(String routeId, int laneIndex, Source source, Status status, Instant calculatedAt,
                           Instant userArrivalAt, Instant predictedArrivalAt, Long waitSeconds, String vehicleId,
                           String modelVersion, Integer basisLaneIndex, Boolean conditional) {
        this(routeId, laneIndex, source, status, calculatedAt, userArrivalAt, predictedArrivalAt, waitSeconds,
                vehicleId, modelVersion, basisLaneIndex, conditional, null);
    }

    /** lane 순번과 기준 lane 순번을 다시 매긴 복사본(구간에서 노선이 빠져 순번이 당겨질 때). */
    public TransferArrival reindexed(int newLaneIndex, Integer newBasisLaneIndex) {
        return new TransferArrival(routeId, newLaneIndex, source, status, calculatedAt, userArrivalAt,
                predictedArrivalAt, waitSeconds, vehicleId, modelVersion, newBasisLaneIndex, conditional, onwardWaits);
    }

    /** 탈 수 있는 차인가 — 예측 시각이 있고 상태가 {@code AVAILABLE}·{@code ARRIVAL_TIME_APPROXIMATE}. 검색 bestLane 과 여정 기본 선택(#614)의 같은 기준. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBoardable() {
        return predictedArrivalAt != null
                && (status == Status.AVAILABLE || status == Status.ARRIVAL_TIME_APPROXIMATE);
    }

    /**
     * 앞 버스 가정 없이 잰 값인가(첫 버스 구간). 두 번째 이후 버스 구간 값은 앞 구간에서 가장 먼저 오는 lane 을 탔다고 본
     * 조건부 값이라({@code basisLaneIndex}) 여정 기본 선택에 쓰지 않는다(#614).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isUnconditional() {
        return basisLaneIndex == null && !Boolean.TRUE.equals(conditional);
    }

    /**
     * 검색 뒤 {@code startedAt} 에 출발해도 걸어서 정류장에 닿은 뒤 오는 차인가(#614). 정류장 도착은 검색이 잰
     * {@code userArrivalAt} 에 늦게 시작한 만큼({@code startedAt − calculatedAt}, 음수는 0)을 더한 시각이다.
     * {@code userArrivalAt} 을 모르면 {@code startedAt} 이후인지만 본다.
     */
    public boolean reachableIfStartedAt(Instant startedAt) {
        if (!isBoardable() || startedAt == null) {
            return false;
        }
        Instant atStop = startedAt;
        if (userArrivalAt != null) {
            java.time.Duration late = calculatedAt == null || startedAt.isBefore(calculatedAt)
                    ? java.time.Duration.ZERO
                    : java.time.Duration.between(calculatedAt, startedAt);
            atStop = userArrivalAt.plus(late);
        }
        return !predictedArrivalAt.isBefore(atStop);
    }

    public TransferArrival withOnwardWaits(java.util.List<OnwardWait> waits) {
        return new TransferArrival(routeId, laneIndex, source, status, calculatedAt, userArrivalAt,
                predictedArrivalAt, waitSeconds, vehicleId, modelVersion, basisLaneIndex, conditional,
                waits == null || waits.isEmpty() ? null : java.util.List.copyOf(waits));
    }

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
