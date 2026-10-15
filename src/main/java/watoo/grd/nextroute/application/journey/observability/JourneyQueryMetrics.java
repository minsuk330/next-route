package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase.JourneyVersions;

import java.time.Duration;

/**
 * 여정 조건부 조회의 판단 재료(#427 처방 4차, SSE·long-poll 결정용).
 *
 * <ul>
 *   <li>{@link #CONDITIONAL} — 204(바뀐 것 없음)·200(바뀜)·버전 없이 물음의 비율. 폴링이 헛걸음하는 몫이다</li>
 *   <li>{@link #DELIVERY_LAG} — 서버 커밋 → 앱이 받은 시각. 폴링 간격이 만드는 지연이 SSE 로 줄일 수 있는 몫이다</li>
 * </ul>
 *
 * <p><b>전달 지연은 "마지막 변경" 기준이다.</b> 앱이 폴링 사이에 버전을 여럿 건너뛰었으면 앞선 변경들의 지연은 더
 * 길었지만 재지 않는다 — 캐시가 마지막 변경 시각만 들고 있다. 그래서 이 값은 실제 지연의 <b>하한</b>이다.
 * 변경 시각을 모르면(캐시가 비어 DB 에서 채움, 캐시 꺼짐) 재지 않는다 — 지어내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JourneyQueryMetrics {

    public static final String CONDITIONAL = "journey.query.conditional";
    public static final String DELIVERY_LAG = "journey.query.delivery.lag";

    /** 앱 조회 간격이 10·15·30초라 그 언저리를 촘촘히 둔다. */
    private static final Duration[] LAG_SLO = {
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(8), Duration.ofSeconds(10), Duration.ofSeconds(12), Duration.ofSeconds(15),
            Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofSeconds(60),
            Duration.ofSeconds(120)};

    private final MeterRegistry registry;

    /** 버전 없이 물었다(앱 첫 조회·재시작). */
    public void first() {
        registry.counter(CONDITIONAL, "result", "first").increment();
    }

    public void notModified() {
        registry.counter(CONDITIONAL, "result", "not_modified").increment();
    }

    /**
     * 200 을 돌려줬다. 앱 번호가 지금 번호보다 낮고, 캐시가 본 번호가 응답 번호와 같고(같은 변경을 가리킴), 변경 시각을
     * 알 때만 지연을 남긴다.
     *
     * @param versions        조회 앞단이 본 버전(캐시). 없으면 null
     * @param commandVersion  응답에 실린 명령 번호
     * @param evaluationVersion 응답에 실린 평가 번호
     */
    public void modified(JourneyVersions versions, long commandVersion, long evaluationVersion,
                         Long afterCommandVersion, Long afterEvaluationVersion, long nowEpochMs) {
        registry.counter(CONDITIONAL, "result", "modified").increment();
        if (versions == null || versions.changedAtEpochMs() == null) {
            return;
        }
        boolean clientBehind = (afterCommandVersion != null && commandVersion > afterCommandVersion)
                || (afterEvaluationVersion != null && evaluationVersion > afterEvaluationVersion);
        boolean sameChange = versions.commandVersion() == commandVersion
                && versions.evaluationVersion() == evaluationVersion;
        if (!clientBehind || !sameChange) {
            // 링(guidance)만 바뀐 200 이거나, 캐시가 아직 이번 변경을 못 받았다 — 기준 시각이 이 변경의 것이 아니다.
            return;
        }
        long lagMs = Math.max(0, nowEpochMs - versions.changedAtEpochMs());
        Timer.builder(DELIVERY_LAG).tag("leg", legKind(versions.currentLegType()))
                .serviceLevelObjectives(LAG_SLO)
                .register(registry)
                .record(Duration.ofMillis(lagMs));
    }

    /** 앱 조회 간격이 단계마다 다르다(대기 10초·탑승 15초·도보 30초). */
    static String legKind(String legType) {
        if (legType == null) {
            return "NONE";
        }
        if (legType.startsWith("WAIT")) {
            return "WAIT";
        }
        if (legType.startsWith("RIDE")) {
            return "RIDE";
        }
        return legType.endsWith("WALK") ? "WALK" : "NONE";
    }
}
