package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * "이 여정의 보드를 지금 다시 찾아라"(#264, reroute-periodic-board PR-2).
 *
 * <p>보드는 1분마다 돌지만, 정류장·역을 지나거나 구간이 바뀌면 판단이 바뀐다 — 그때는 기다리지 않는다.
 * 신호는 예정 시각만 당긴다. 실제 갱신은 worker 스케줄러가 선점해 돈다(신호를 보낸 api 요청이 경로
 * 검색을 직접 돌지 않는다).
 *
 * <p><b>플래그가 꺼져 있으면 아무것도 쓰지 않는다.</b> 부르는 자리(진행 보고·명령·수락)는 사용자 요청
 * 경로라, 꺼진 기능 때문에 그 응답에 쓰기 하나가 붙으면 안 된다.
 *
 * <p><b>예외를 삼킨다.</b> 신호를 못 남겨도 다음 주기에 돈다. 그 때문에 진행 보고나 수락이 실패하면
 * 안 된다.
 *
 * <p><b>트랜잭션은 합류한다({@code REQUIRED}).</b> 수락·명령 처리는 이미 여정 행을 잠근 트랜잭션 안에서
 * 부른다 — 새 트랜잭션({@code REQUIRES_NEW})으로 같은 행을 쓰면 자기 잠금을 기다리며 멈춘다. 방아쇠
 * 평가기·링 갱신처럼 트랜잭션 밖에서 부르면 새로 연다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerouteRefreshSignal {

    /** 예정이 없을 때 LEAST 의 상대. 실제 예정으로 쓰이지 않는다. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

    private final JourneySessionRepository sessionRepository;
    private final JourneyRerouteProperties properties;
    private final Clock clock;

    /** 정류장·역 통과, 구간 전이 — 최소 간격을 지킨다. */
    @Transactional
    public void requestNow(Long journeyId) {
        request(journeyId, false);
    }

    /** 환승 위험 하락 — 최소 간격을 우회한다. 위험 알림 합치기 창(PR-5) 안에 결과가 나와야 한다. */
    @Transactional
    public void requestUrgent(Long journeyId) {
        request(journeyId, true);
    }

    /** 여정 시작. 첫 갱신은 시작 + 초기 지연(60초)이다. */
    @Transactional
    public void scheduleInitial(Long journeyId) {
        if (!enabled()) {
            return;
        }
        try {
            sessionRepository.scheduleRerouteRefreshIfAbsent(journeyId, Instant.now(clock)
                    .plusSeconds(properties.getBoard().getRefresh().getInitialDelaySeconds()));
        } catch (RuntimeException e) {
            log.warn("[RerouteBoard] 첫 갱신 예약 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
    }

    /** 수락. 새 개정본의 보드를 바로 만들고, 수락 시각을 남긴다(수락 후 3분 push 차단의 기준). */
    @Transactional
    public void accepted(Long journeyId) {
        if (!enabled()) {
            return;
        }
        try {
            sessionRepository.markRerouteAccepted(journeyId, Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("[RerouteBoard] 수락 시각 기록 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
        request(journeyId, false);
    }

    private void request(Long journeyId, boolean bypassMinGap) {
        if (!enabled()) {
            return;
        }
        try {
            sessionRepository.requestRerouteRefresh(journeyId, Instant.now(clock),
                    properties.getBoard().getRefresh().getMinGapSeconds(), bypassMinGap, FAR_FUTURE);
        } catch (RuntimeException e) {
            log.warn("[RerouteBoard] 갱신 신호 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.getBoard().isEnabled();
    }
}
