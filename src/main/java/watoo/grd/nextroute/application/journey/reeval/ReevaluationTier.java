package watoo.grd.nextroute.application.journey.reeval;

/**
 * 재평가가 얼마나 급한지. 감지 SLO 등급과 같다(#415).
 *
 * <ul>
 *   <li>{@link #URGENT} — 환승 위험·놓침이 있거나 환승 준비까지 임박 기준(기본 5분) 이내. SLO 10초</li>
 *   <li>{@link #CAUTION} — 한가함 기준(기본 15분) 이내. SLO 30초</li>
 *   <li>{@link #RELAXED} — 그보다 멀거나 남은 환승이 없음. SLO 2분</li>
 * </ul>
 *
 * <p>경계는 평가 간격 정책과 같은 설정({@code near-transfer}·{@code far-transfer})을 쓴다. 두 벌로
 * 두면 "급하다"의 뜻이 둘로 갈라진다.
 */
public enum ReevaluationTier {
    URGENT,
    CAUTION,
    RELAXED
}
