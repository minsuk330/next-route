package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지하철 안내 — 앱이 진행 링과 Dynamic Island 를 그리는 데 필요한 전부.
 *
 * <h2>서버는 앵커를 주고 앱이 보간한다</h2>
 *
 * <p>서버가 진행 숫자를 자주 내려보내는 구조가 아니다. {@code progressBaseAt} 시점의 확정
 * 진행률과 다음 역 도착 예정 시각을 주면, 앱이 그 사이를 시간으로 채운다.
 * {@code progressValidUntil} 을 넘으면 앱은 보간을 멈춘다.
 *
 * <p><b>서버가 보장하는 것은 "앵커가 뒤로 가지 않는다"까지다.</b> 앱은 새 앵커를 비동기로
 * 받으므로 새 도착 예정이 짧으면 화면이 앞으로 튀고 길면 뒤로 튈 수 있다 — 앵커 자체는 단조여도
 * 그 사이를 보간하던 값이 새 직선으로 갈아타기 때문이다. <b>화면 역행 금지는 앱 책임</b>이다.
 *
 * <h2>상태와 신선도는 다른 축이다</h2>
 *
 * <p>{@code bindingStatus} 는 판정 상태고 {@code quality} 는 실시간 신호의 신선도다. 섞으면
 * "판정이 취소된 것"과 "신호가 늦은 것"을 구분할 수 없다 — {@code CONFIRMED} 이면서
 * {@code STALE} 인 상태는 정상이다.
 *
 * <p>{@code trainNo}·역 식별자·사용자 위치는 넣지 않는다. 앱이 화면을 그리는 데 필요 없고,
 * 잠금화면 payload 로도 나가는 값이다.
 *
 * @param nextStationName        미확정({@code PENDING}·{@code PROVISIONAL})이면 null
 * @param remainingStations      하차역 <b>포함</b>. 다음 역이 하차역이면 1
 * @param segmentProgress        {@code progressBaseAt} 시점의 확정 진행률
 * @param progressTarget         이동 중 상한(기본 0.90). 앱이 여기까지만 보간한다
 * @param estimatedNextStationAt 실시간 기반. <b>없으면 null</b> — 앱은 보간을 멈춘다
 * @param observedAt             원천 실시간 데이터의 관측 시각
 * @param version                링 화면이 마지막으로 바뀐 시각(epoch millis, #307). 여정 안에서 단조라 앱이 늦게 온
 *                               옛 스냅샷을 버리는 데 쓴다. 링이 없으면 null
 * @param estimatedAlightingAt   하차역 도착 예정(#310). 다음 역 예정 + GTFS 계획 초. 모르면 null — 앱이 자체 추정
 * @param holdReason             링이 느리거나 멈춘 이유({@code PREV_STATION_STOPPED}: 전역 정차 — 링은 느리게 진행할 수 있다,
 *                               {@code LATE}: 예정 경과). 운행 직선·STALE 이면 null
 * @param holdStationName        {@code PREV_STATION_STOPPED} 일 때 열차가 서 있는 역. 모르면(승차역 대기 구간) null
 * @param etaSource              {@code estimatedNextStationAt} 의 출처(LIVE·MEASURED·TYPICAL). 예정이 없으면 null.
 *                               앱은 TYPICAL 이면 도착 시각을 숨긴다
 * @param alightingEtaSource     {@code estimatedAlightingAt} 의 출처. 다음 역이 하차역이 아니면 남은 구간이 계획값이라 TYPICAL
 */
public record SubwayGuidance(
        SubwayBindingStatus bindingStatus,
        SubwayRingPhase phase,

        String lineName,
        String nextStationName,
        String alightingStationName,
        Integer remainingStations,

        BigDecimal segmentProgress,
        Instant progressBaseAt,
        BigDecimal progressTarget,
        Instant estimatedNextStationAt,
        Instant progressValidUntil,

        SubwayRingQuality quality,
        Instant observedAt,
        Long version,
        Instant estimatedAlightingAt,
        SubwayRingHoldReason holdReason,
        String holdStationName,
        SubwayRingEtaSource etaSource,
        SubwayRingEtaSource alightingEtaSource
) {

    public SubwayGuidance(SubwayBindingStatus bindingStatus, SubwayRingPhase phase, String lineName,
                          String nextStationName, String alightingStationName, Integer remainingStations,
                          BigDecimal segmentProgress, Instant progressBaseAt, BigDecimal progressTarget,
                          Instant estimatedNextStationAt, Instant progressValidUntil,
                          SubwayRingQuality quality, Instant observedAt, Long version,
                          Instant estimatedAlightingAt, SubwayRingHoldReason holdReason, String holdStationName) {
        this(bindingStatus, phase, lineName, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt, progressValidUntil,
                quality, observedAt, version, estimatedAlightingAt, holdReason, holdStationName, null, null);
    }

    /** 멈춤 사유 없이(옛 모양). */
    public SubwayGuidance(SubwayBindingStatus bindingStatus, SubwayRingPhase phase, String lineName,
                          String nextStationName, String alightingStationName, Integer remainingStations,
                          BigDecimal segmentProgress, Instant progressBaseAt, BigDecimal progressTarget,
                          Instant estimatedNextStationAt, Instant progressValidUntil,
                          SubwayRingQuality quality, Instant observedAt, Long version,
                          Instant estimatedAlightingAt) {
        this(bindingStatus, phase, lineName, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt, progressValidUntil,
                quality, observedAt, version, estimatedAlightingAt, null, null);
    }

    /** 하차 예정 없이(옛 모양). */
    public SubwayGuidance(SubwayBindingStatus bindingStatus, SubwayRingPhase phase, String lineName,
                          String nextStationName, String alightingStationName, Integer remainingStations,
                          BigDecimal segmentProgress, Instant progressBaseAt, BigDecimal progressTarget,
                          Instant estimatedNextStationAt, Instant progressValidUntil,
                          SubwayRingQuality quality, Instant observedAt, Long version) {
        this(bindingStatus, phase, lineName, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt, progressValidUntil,
                quality, observedAt, version, null);
    }

    /**
     * 아직 어느 열차인지 모른다. 앱은 "탑승 열차 확인 중" 화면을 그린다.
     *
     * <p>진행률·다음 역을 <b>비운다.</b> 모르는 것을 0 이나 계획값으로 채우면 앱은 그것을
     * 실시간으로 읽는다.
     */
    public static SubwayGuidance pending(SubwayBindingStatus status, String lineName,
                                         String alightingStationName) {
        return new SubwayGuidance(status, SubwayRingPhase.RIDE_SUBWAY, lineName,
                null, alightingStationName, null,
                null, null, null, null, null,
                SubwayRingQuality.STALE, null, null);
    }

    /** 확정된 고정에 링 상태를 얹는다. */
    public static SubwayGuidance of(SubwayBindingStatus status, String lineName,
                                    SubwayRingState ring) {
        return new SubwayGuidance(status, ring.phase(), lineName,
                ring.nextStationName(), ring.alightingStationName(), ring.remainingStations(),
                ring.segmentProgress(), ring.progressBaseAt(), ring.progressTarget(),
                ring.estimatedNextStationAt(), ring.progressValidUntil(),
                ring.quality(), ring.observedAt(),
                ring.guidanceVersion() > 0 ? ring.guidanceVersion() : null,
                ring.estimatedAlightingAt(), ring.holdReason(), ring.holdStationName(),
                ring.etaSource(), ring.alightingEtaSource());
    }
}
