package watoo.grd.nextroute.application.journey.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * 진행 링의 <b>내부</b> 상태. Redis 에 그대로 담긴다.
 *
 * <p>외부로 나가는 {@link SubwayGuidance} 와 <b>다른 값이다.</b> 여기에는 판정용 키가 더 들어
 * 있다 — 다음 역이 바뀌었는지를 <b>표시 이름으로 판정하지 않기</b> 위해서다. 역 이름은 표기가
 * 흔들려서({@code 성수}/{@code 성수종착}/{@code 성수지선}) 같은 역인데 링이 0 으로 리셋된다.
 *
 * <p>fencing 네 값({@code journeyId}·{@code routeRevision}·{@code legIndex}·
 * {@code bindingVersion})을 상태 안에도 들고 있는 이유는, 키에서 되읽은 값과 대조해 <b>옛
 * worker 가 쓴 상태</b>를 알아보기 위해서다.
 *
 * @param nextStationSequence     승차역을 0 으로 세는 계획 역 체인의 순번. 판정 축이다
 * @param canonicalNextStationKey 서울 실시간 {@code statnId}. 도착 조회의 인자로도 그대로 쓴다
 * @param segmentProgress         {@code progressBaseAt} <b>시점의</b> 확정 진행률. 지금 값이 아니다
 * @param progressTarget          앱이 보간해 갈 상한
 * @param estimatedNextStationAt  실시간 기반 다음 역 도착 예정. <b>없으면 null</b> — 앱이 보간을 멈춘다
 * @param progressValidUntil      앱이 로컬 보간을 이어가도 되는 마지막 시각
 * @param observedAt              원천이 말한 관측 시각. 도착 API 는 <b>그 행의</b> {@code recptnDt} 다
 * @param guidanceVersion         화면이 마지막으로 <b>눈에 띄게</b> 바뀐 시각(epoch millis, #307). 같은 화면이면
 *                                tick 이 돌아도 그대로다. 고정 세대가 바뀌어 키가 새로 생겨도 시각이라 단조다.
 *                                0 = 아직 부여 전(배포 전 Redis 값 포함)
 * @param estimatedAlightingAt    하차역 도착 예정(#310). 다음 역 도착 예정 + GTFS 계획 초(다음 역 도착 → 하차역 도착).
 *                                다음 역 예정이 없거나 계획을 모르면 null. 계산기가 아니라 갱신기가 붙인다
 * @param holdReason              링이 멈춘 이유. 멈춤이 아니거나(보간 중·도착·진입) STALE freeze 면 null —
 *                                STALE 은 {@code quality} 하나로만 말한다
 * @param holdStationName         {@code PREV_STATION_STOPPED} 일 때 열차가 서 있는 역(체인의 다음 역 바로 앞).
 *                                승차역 대기 구간은 체인에 그 역이 없어 null
 */
public record SubwayRingState(
        Long journeyId,
        int routeRevision,
        int legIndex,
        int bindingVersion,

        int nextStationSequence,
        String canonicalNextStationKey,
        String nextStationName,
        String alightingStationName,
        Integer remainingStations,

        BigDecimal segmentProgress,
        Instant progressBaseAt,
        BigDecimal progressTarget,
        Instant estimatedNextStationAt,
        Instant progressValidUntil,

        SubwayRingPhase phase,
        SubwayRingQuality quality,
        Instant observedAt,
        Instant updatedAt,
        long guidanceVersion,
        Instant estimatedAlightingAt,
        SubwayRingHoldReason holdReason,
        String holdStationName
) {

    /** 상한이 비었을 때 앱이 쓰는 값과 같다. */
    private static final BigDecimal DEFAULT_PROGRESS_TARGET = new BigDecimal("0.90");

    /** 멈춤 사유 없이 만든다(옛 모양 — Redis 에 남은 값·시험). */
    public SubwayRingState(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                           int nextStationSequence, String canonicalNextStationKey,
                           String nextStationName, String alightingStationName,
                           Integer remainingStations, BigDecimal segmentProgress,
                           Instant progressBaseAt, BigDecimal progressTarget,
                           Instant estimatedNextStationAt, Instant progressValidUntil,
                           SubwayRingPhase phase, SubwayRingQuality quality,
                           Instant observedAt, Instant updatedAt, long guidanceVersion,
                           Instant estimatedAlightingAt) {
        this(journeyId, routeRevision, legIndex, bindingVersion, nextStationSequence,
                canonicalNextStationKey, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt,
                progressValidUntil, phase, quality, observedAt, updatedAt, guidanceVersion,
                estimatedAlightingAt, null, null);
    }

    /** 하차 예정 없이 만든다(옛 모양 — Redis 에 남은 값·시험). */
    public SubwayRingState(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                           int nextStationSequence, String canonicalNextStationKey,
                           String nextStationName, String alightingStationName,
                           Integer remainingStations, BigDecimal segmentProgress,
                           Instant progressBaseAt, BigDecimal progressTarget,
                           Instant estimatedNextStationAt, Instant progressValidUntil,
                           SubwayRingPhase phase, SubwayRingQuality quality,
                           Instant observedAt, Instant updatedAt, long guidanceVersion) {
        this(journeyId, routeRevision, legIndex, bindingVersion, nextStationSequence,
                canonicalNextStationKey, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt,
                progressValidUntil, phase, quality, observedAt, updatedAt, guidanceVersion, null);
    }

    /** 버전 없이 만든다(계산기). 버전은 저장 직전에 갱신기가 붙인다. */
    public SubwayRingState(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                           int nextStationSequence, String canonicalNextStationKey,
                           String nextStationName, String alightingStationName,
                           Integer remainingStations, BigDecimal segmentProgress,
                           Instant progressBaseAt, BigDecimal progressTarget,
                           Instant estimatedNextStationAt, Instant progressValidUntil,
                           SubwayRingPhase phase, SubwayRingQuality quality,
                           Instant observedAt, Instant updatedAt) {
        this(journeyId, routeRevision, legIndex, bindingVersion, nextStationSequence,
                canonicalNextStationKey, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt,
                progressValidUntil, phase, quality, observedAt, updatedAt, 0L);
    }

    /** 앱이 보간해 갈 상한만 바꾼다. 상한 = 지금 값이면 링이 그 자리에 선다(정차·예정 경과). */
    public SubwayRingState withProgressTarget(BigDecimal target) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                target, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, estimatedAlightingAt, holdReason, holdStationName);
    }

    public SubwayRingState withProgressValidUntil(Instant validUntil) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, validUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, estimatedAlightingAt, holdReason, holdStationName);
    }

    /** 같은 앵커를 새 관측으로 확인만 했다. 화면 값은 그대로, 관측·갱신 시각만 바뀐다. */
    public SubwayRingState refreshed(Instant observedAt, Instant updatedAt) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, estimatedAlightingAt, holdReason, holdStationName);
    }

    public SubwayRingState withEstimatedAlightingAt(Instant alightingAt) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, alightingAt, holdReason, holdStationName);
    }

    public SubwayRingState withGuidanceVersion(long version) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, version, estimatedAlightingAt, holdReason, holdStationName);
    }

    /** 링이 왜 멈췄는지 붙인다. 진행률·예정은 건드리지 않는다 — 앱 문구("○○ 정차 중")용이다. */
    public SubwayRingState withHold(SubwayRingHoldReason reason, String stationName) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, estimatedAlightingAt, reason, stationName);
    }

    /**
     * {@code at} 시점에 앱 화면에 보이는 진행률(#357). 앱 {@code shownSubwayProgress} 와 같은 식이다 — NORMAL 이고
     * 예정이 앵커보다 뒤면 base 에서 상한까지 시간비로 보간하고, 아니면 base 그대로다.
     */
    public BigDecimal shownProgressAt(Instant at) {
        BigDecimal base = segmentProgress == null ? BigDecimal.ZERO : segmentProgress;
        BigDecimal cap = progressTarget == null ? DEFAULT_PROGRESS_TARGET : progressTarget;
        if (quality != SubwayRingQuality.NORMAL || progressBaseAt == null || estimatedNextStationAt == null
                || !estimatedNextStationAt.isAfter(progressBaseAt) || cap.compareTo(base) <= 0) {
            return base;
        }
        Instant clamped = at.isBefore(progressBaseAt) ? progressBaseAt
                : at.isAfter(estimatedNextStationAt) ? estimatedNextStationAt : at;
        BigDecimal elapsed = BigDecimal.valueOf(Duration.between(progressBaseAt, clamped).toMillis());
        BigDecimal span = BigDecimal.valueOf(Duration.between(progressBaseAt, estimatedNextStationAt).toMillis());
        return base.add(cap.subtract(base).multiply(elapsed).divide(span, 4, RoundingMode.DOWN));
    }

    /** 앵커만 {@code baseAt} 의 {@code progress} 로 옮긴다. 상한은 그 값 아래로 내려가지 않는다 — 보간이 뒤로 가면 안 된다. */
    public SubwayRingState withAnchor(BigDecimal progress, Instant baseAt) {
        BigDecimal target = progressTarget == null || progressTarget.compareTo(progress) < 0 ? progress : progressTarget;
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, progress, baseAt,
                target, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, guidanceVersion, estimatedAlightingAt, holdReason, holdStationName);
    }

    /** 이 상태가 그 고정 세대의 것인지. 아니면 옛 worker 가 쓴 값이다. */
    public boolean matches(int routeRevision, int legIndex, int bindingVersion) {
        return this.routeRevision == routeRevision
                && this.legIndex == legIndex
                && this.bindingVersion == bindingVersion;
    }

    /**
     * 다음 역에 <b>도착까지 마친</b> 상태인지.
     *
     * <p>따로 칸을 두지 않고 진행률 1.00 자체를 표시로 쓴다. 상한 0.90(이동 중)·0.95(진입)는
     * 실제 도착 사건 없이는 만들어지지 않으므로, 1.00 이면 도착 사건을 본 것이다. 다음 tick 이
     * 이 값을 보고 구간을 넘긴다.
     */
    /**
     * 직전 상태보다 <b>다음 역 순번이 앞으로 갔는가</b> — 역 하나를 지났다(#264 재탐색 보드 신호).
     *
     * <p>고정·개정본·단계가 다른 직전 상태와는 비교하지 않는다. 다른 링의 순번을 견주면 엉뚱한 "지남"이 된다.
     */
    public boolean advancedFrom(SubwayRingState previous) {
        return previous != null
                && previous.matches(routeRevision, legIndex, bindingVersion)
                && nextStationSequence > previous.nextStationSequence;
    }

    public boolean arrivedAtNextStation() {
        return segmentProgress != null && segmentProgress.compareTo(BigDecimal.ONE) >= 0;
    }
}
