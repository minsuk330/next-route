package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;

/**
 * 보드의 한 자리 — 말풍선 하나(#264, reroute-periodic-board).
 *
 * <p>자리는 <b>하차(또는 출발) 정류장</b>이다. 보드는 1분 안팎마다 다시 찾지만 말풍선은 자리마다
 * 이어져야 한다. 그래서 후보를 매번 갈아끼우지 않고, 자리마다 "지금 보이는가·마지막 이득·언제부터
 * 보였나"를 기억한다. 숨김 뒤 재표시 문턱, 같은 경로면 candidateId 유지 같은 규칙은 이 기억 없이는
 * 판단할 수 없다.
 *
 * @param slotKey          자리 키. stopId 중심이라 계획 하차점(S)이 하차 후 대기 정류장(S)이 돼도 이어진다.
 *                         걷기 중 현재 위치는 {@link #HERE}
 * @param originKind       기점 종류. 앱이 말풍선을 어떻게 그릴지 고르는 값의 원천
 * @param state            보이는가
 * @param candidate        보이는 자리의 후보. 숨긴 자리는 null — 다시 보일 때는 새 후보로 시작한다
 * @param routeFingerprint 후보 경로의 지문(첫 노선·하차점·환승 노선). 같은 경로인지 가를 때만 쓴다
 * @param lastGainSec      마지막으로 평가한 이득(초). 숨긴 자리도 남긴다
 * @param firstShownAt     지금 보이는 상태가 시작된 시각
 * @param hiddenAt         숨긴 시각. 보이는 자리는 null
 * @param evaluatedAt      마지막으로 이 자리를 평가한 시각
 * @param alightAt         이 자리에 닿는 시각(하차형). 현재 위치형은 null — 시간으로 사라지지 않는다
 */
public record RerouteBoardSlot(
        String slotKey,
        RerouteOriginKind originKind,
        State state,
        RerouteCandidate candidate,
        String routeFingerprint,
        Integer lastGainSec,
        Instant firstShownAt,
        Instant hiddenAt,
        Instant evaluatedAt,
        Instant alightAt) {

    /** 걷기 중 현재 위치 자리. 구간이 바뀌면 그 기억은 버린다. */
    public static final String HERE = "HERE";

    public RerouteBoardSlot {
        Objects.requireNonNull(slotKey, "slotKey");
        Objects.requireNonNull(state, "state");
        if (state == State.VISIBLE) {
            Objects.requireNonNull(candidate, "보이는 자리에는 후보가 있어야 한다");
        }
    }

    /** 정류장 자리 키. provider 를 앞에 붙여 서울·경기·TAGO 의 같은 숫자 id 가 섞이지 않게 한다. */
    public static String stopKey(String provider, String stopId) {
        return "S:" + (provider == null ? "SEOUL" : provider) + ":" + stopId;
    }

    @JsonIgnore
    public boolean isVisible() {
        return state == State.VISIBLE;
    }

    public enum State {
        VISIBLE,
        HIDDEN
    }
}
