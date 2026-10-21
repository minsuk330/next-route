package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.List;

/**
 * 앱이 여정 화면을 그리는 데 필요한 최소 상태.
 *
 * <p>경로 원본(JSON) 은 넣지 않는다. 앱은 이미 검색 결과를 들고 있고, 서버가 매 응답마다
 * 같은 JSON 을 다시 실어 보내면 지하철 안에서 가장 무거운 응답이 된다.
 *
 * <p>{@code serverTime} 을 함께 준다. 기기 시계는 어긋나 있을 수 있어서, 앱이
 * {@code guidanceLeaseUntil} 까지 남은 시간을 계산하려면 서버 기준 지금이 필요하다.
 *
 * @param kind                   일반 여정({@code NAVIGATION})인가 단독 추적({@code TRACK})인가. 앱이 화면을
 *                               가른다 — 단독 추적이면 재탐색 UI(말풍선·제안)를 숨긴다
 * @param currentLeg             지금 지나는 단계. {@code legs} 에서 뽑은 값이라 중복이지만, 앱이
 *                               매번 순번으로 찾지 않아도 되게 함께 준다
 * @param recommendedPollSeconds 다음에 물어볼 때까지 기다릴 초. 0 이면 그만 물어도 된다
 * @param eta                    예측. 진행 중인 여정이면 진행 보고({@code PUT /progress})를 뺀 모든 성공 응답의
 *                               스냅샷에 실린다(#470). 진행 중인 여정에서 null 이면 "이 응답은 예측을 계산하지
 *                               않았다"는 뜻이라 앱은 직전 값을 유지한다. "모른다"는 이 안의 칸이 null 인
 *                               것으로 나타낸다. 끝난 여정은 늘 null 이다. 오류 응답의 details 에 실린
 *                               스냅샷(409 {@code ACTIVE_JOURNEY_EXISTS})에는 싣지 않는다 — 그때 도착 예정은
 *                               {@code GET /api/journeys/active} 로 받는다(#475)
 * @param subwayGuidance         지하철 진행 안내. 지금 지하철 구간이 아니면 {@code null}.
 *                               <b>단계 안이 아니라 최상위</b>에 둔다 — 모바일의 기존 계약이고,
 *                               앱은 이 값 하나만 보고 Dynamic Island 를 그린다
 */
public record JourneySnapshot(
        Long journeyId,
        JourneyStatus status,
        JourneyKind kind,
        long commandVersion,
        long evaluationVersion,
        int routeRevision,
        int currentLegIndex,
        Instant guidanceLeaseUntil,
        Instant subwayOfflineProtectionUntil,
        Instant serverTime,
        JourneyLegView currentLeg,
        int recommendedPollSeconds,
        List<JourneyLegView> legs,
        JourneyEtaView eta,
        SubwayGuidance subwayGuidance
) {

    /**
     * 폴링 간격. 단계 종류마다 상황이 바뀌는 속도가 달라서 하나로 정하지 않는다.
     *
     * <p>가장 짧은 것이 기다리는 단계다. "언제 오나"가 초 단위로 바뀌고 사용자가 화면을 가장
     * 자주 보는 구간이기 때문이다. 걷는 동안은 서버가 알려 줄 새 소식이 거의 없다.
     *
     * <p>Phase 3 에서 실시간 평가가 붙으면 남은 시간에 따라 더 좁혀야 한다. 지금은 단계 종류만
     * 보는 단순한 규칙이다.
     */
    private static final int POLL_WAITING = 10;
    private static final int POLL_RIDING = 15;
    private static final int POLL_WALKING = 30;
    private static final int POLL_STOPPED = 0;

    public JourneySnapshot {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    /** 저장된 상태에서 스냅샷을 만든다. 파생 값(현재 단계·폴링 간격)은 여기서 계산한다. */
    public static JourneySnapshot of(Long journeyId, JourneyStatus status, JourneyKind kind,
                                     long commandVersion, long evaluationVersion,
                                     int routeRevision, int currentLegIndex,
                                     Instant guidanceLeaseUntil,
                                     Instant subwayOfflineProtectionUntil,
                                     Instant serverTime,
                                     List<JourneyLegView> legs) {
        JourneyLegView current = legOf(legs, currentLegIndex);
        return new JourneySnapshot(journeyId, status, kind, commandVersion, evaluationVersion,
                routeRevision, currentLegIndex, guidanceLeaseUntil,
                subwayOfflineProtectionUntil, serverTime,
                current, pollSecondsOf(status, current), legs, null, null);
    }

    /**
     * 예측 값을 얹은 새 스냅샷.
     *
     * <p>예측은 실시간 조회가 필요해 트랜잭션 밖에서 따로 계산한다. 그래서 기본 스냅샷을 만든
     * 뒤에 붙이는 모양이 된다.
     *
     * <p><b>{@code subwayGuidance}·{@code kind} 를 반드시 함께 옮긴다.</b> 이 메서드는 레코드를 통째로 다시
     * 만들기 때문에 필드를 하나 더할 때마다 여기서 조용히 사라질 수 있다 — 사라지면 앱은
     * 지하철 구간에서만, 그것도 예측이 붙은 응답에서만 링을 잃는다. 시험으로 고정한다.
     *
     * @param pollSeconds 환승 위험을 반영해 좁힌 폴링 간격
     */
    public JourneySnapshot withEta(JourneyEtaView eta, int pollSeconds) {
        return new JourneySnapshot(journeyId, status, kind, commandVersion, evaluationVersion,
                routeRevision, currentLegIndex, guidanceLeaseUntil,
                subwayOfflineProtectionUntil, serverTime,
                currentLeg, pollSeconds, legs, eta, subwayGuidance);
    }

    /**
     * 지하철 안내를 얹은 새 스냅샷.
     *
     * <p>{@code null} 이면 그대로 돌려준다 — 지하철 구간이 아닌 여정에서 레코드를 한 번 더
     * 만들 이유가 없다.
     */
    public JourneySnapshot withSubwayGuidance(SubwayGuidance guidance) {
        if (guidance == null) {
            return this;
        }
        return new JourneySnapshot(journeyId, status, kind, commandVersion, evaluationVersion,
                routeRevision, currentLegIndex, guidanceLeaseUntil,
                subwayOfflineProtectionUntil, serverTime,
                currentLeg, recommendedPollSeconds, legs, eta, guidance);
    }

    /**
     * 지하철 링 버전(#307). 링이 화면을 바꿀 때만 오른다 — {@code commandVersion}·{@code evaluationVersion} 은 링 갱신에
     * 오르지 않아서 조건부 조회가 링 변화를 못 잡았다. 링이 없으면(지하철 아님·첫 링 전) 0.
     *
     * <p>레코드 칸이 아니라 {@code subwayGuidance.version} 에서 파생한다 — 칸을 더하면 {@link #withEta} 같은 재생성 경로에서
     * 조용히 떨어질 수 있다.
     */
    @JsonProperty("guidanceVersion")
    public long guidanceVersion() {
        return subwayGuidance == null || subwayGuidance.version() == null ? 0L : subwayGuidance.version();
    }

    private static JourneyLegView legOf(List<JourneyLegView> legs, int index) {
        if (legs == null || index < 0 || index >= legs.size()) {
            return null;
        }
        return legs.get(index);
    }

    private static int pollSecondsOf(JourneyStatus status, JourneyLegView currentLeg) {
        if (status != JourneyStatus.ACTIVE || currentLeg == null) {
            return POLL_STOPPED;
        }
        JourneyLegType type = currentLeg.type();
        if (type.isWaiting()) {
            return POLL_WAITING;
        }
        if (type.isRiding()) {
            return POLL_RIDING;
        }
        return POLL_WALKING;
    }
}
