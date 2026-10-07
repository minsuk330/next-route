package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 보드를 갱신한 뒤 <b>사용자를 깨울지</b> 정한다(#264 PR-5). DB·시계·외부 호출이 없다.
 *
 * <p>말풍선은 조용히 갱신되고 push 는 확실할 때만 간다(정리본 §6).
 * <ul>
 *   <li><b>위험 + 대안</b>: 다음 환승이 위험(AT_RISK·MISSED)이고 300초 이상 빠른 후보가 있다</li>
 *   <li><b>새 기회</b>: 300초 이상 빠른 후보가 새로 생겼다 — 직전 push 와 자리·경로가 다르거나 이득이
 *       120초 이상 커졌다. 여정 시작·수락 뒤 3분 동안은 보내지 않는다</li>
 *   <li>공통: 하차형 자리는 도착까지 120초 이상 남아야 한다(읽고 문 앞으로 갈 시간)</li>
 * </ul>
 * cooldown(5분)·무시 상한(2회)·채널은 부르는 쪽이 본다 — 그것들은 저장소를 읽는다.
 */
public final class RerouteBoardPushPolicy {

    private RerouteBoardPushPolicy() {
    }

    public static Optional<Decision> decide(List<RerouteBoardSlot> slots, LastPush last, boolean transferAtRisk,
                                            Instant startedAt, Instant acceptedAt, Instant now,
                                            JourneyRerouteProperties properties) {
        JourneyRerouteProperties.Board board = properties.getBoard();
        int minimumGain = properties.getAlert().getMinimumGainSeconds();
        Optional<RerouteBoardSlot> best = slots.stream()
                .filter(RerouteBoardSlot::isVisible)
                .filter(slot -> slot.lastGainSec() != null && slot.lastGainSec() >= minimumGain)
                .filter(slot -> leadAllows(slot, now, board.getPushLeadTimeSec()))
                .max(Comparator.comparingInt(RerouteBoardSlot::lastGainSec));
        if (best.isEmpty()) {
            return Optional.empty();
        }
        if (transferAtRisk) {
            return Optional.of(new Decision(best.get(), Kind.RISK_WITH_ALTERNATIVE));
        }
        if (quiet(startedAt, now, board.getPushQuietAfterStart())
                || quiet(acceptedAt, now, board.getPushQuietAfterStart())) {
            return Optional.empty();
        }
        return isNovel(best.get(), last, board.getPushNoveltyGainSec())
                ? Optional.of(new Decision(best.get(), Kind.NEW_OPPORTUNITY))
                : Optional.empty();
    }

    /** 하차형 자리만 여유를 본다. 현재 위치·대기 자리는 사용자가 이미 그 자리에 있다. */
    private static boolean leadAllows(RerouteBoardSlot slot, Instant now, int leadSec) {
        return slot.alightAt() == null
                || Duration.between(now, slot.alightAt()).getSeconds() >= leadSec;
    }

    private static boolean quiet(Instant since, Instant now, Duration quiet) {
        return since != null && now.isBefore(since.plus(quiet));
    }

    private static boolean isNovel(RerouteBoardSlot slot, LastPush last, int noveltyGainSec) {
        if (last == null || last.slotKey() == null) {
            return true;
        }
        return !slot.slotKey().equals(last.slotKey())
                || !Objects.equals(slot.routeFingerprint(), last.routeFingerprint())
                || last.gainSec() == null
                || slot.lastGainSec() >= last.gainSec() + noveltyGainSec;
    }

    public record LastPush(String slotKey, String routeFingerprint, Integer gainSec) {
    }

    public record Decision(RerouteBoardSlot slot, Kind kind) {
    }

    public enum Kind {
        RISK_WITH_ALTERNATIVE,
        NEW_OPPORTUNITY;

        public String tag() {
            return this == RISK_WITH_ALTERNATIVE ? "risk" : "opportunity";
        }
    }
}
