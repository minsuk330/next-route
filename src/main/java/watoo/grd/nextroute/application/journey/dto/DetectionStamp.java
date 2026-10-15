package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.time.Instant;

/**
 * 알림의 "처음 본 시각"(#446). 처음부터 끝까지 지표 {@code journey.notification.e2e} = FCM 접수 − 이 값.
 *
 * <p>두 출처가 있다.
 * <ul>
 *   <li>{@link #FEED} — 변화 피드(지하철 수집기)가 그 여정이 지켜보는 키의 변화를 <b>처음 받은 시각</b>. 수집기가 전 노선
 *       응답을 받은 순간이다. 평가기가 그 여정을 다시 평가하기 전까지 가장 이른 값 하나만 남긴다(미뤄진 깨우기도 처음
 *       본 시각을 지킨다). 등급은 그 순간 색인이 매긴 등급 = 감지 SLO 등급</li>
 *   <li>{@link #EVALUATION} — 표시가 없을 때(간격 폴링이 먼저 잡았거나 Redis 가 비었을 때). 평가를 시작한 시각이라
 *       <b>하한</b>이다 — 원천 변화부터 평가 시작까지의 몫이 빠진다. 등급은 평가 직전 판정으로 같은 규칙을 다시 매긴다</li>
 * </ul>
 *
 * @param firstSeenAt 처음 본 시각
 * @param tier        그때의 등급(긴급·주의·여유)
 * @param source      {@link #FEED} 또는 {@link #EVALUATION}
 */
public record DetectionStamp(Instant firstSeenAt, ReevaluationTier tier, String source) {

    public static final String FEED = "feed";
    public static final String EVALUATION = "evaluation";

    public static DetectionStamp feed(Instant firstSeenAt, ReevaluationTier tier) {
        return new DetectionStamp(firstSeenAt, tier, FEED);
    }

    public static DetectionStamp evaluation(Instant startedAt, ReevaluationTier tier) {
        return new DetectionStamp(startedAt, tier, EVALUATION);
    }
}
