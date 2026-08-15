package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.port.in.ManageLiveActivityTokenUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLiveActivityTokenRepository;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Live Activity 수신 주소를 등록·해제한다.
 *
 * <h2>토큰을 어디에도 남기지 않는다</h2>
 *
 * <p>응답도 없고 로그에도 싣지 않는다. 실패했을 때도 토큰 대신 여정 번호만 적는다 — 장애 조사에
 * 필요한 것은 "누구의 어느 여정이 실패했나"이지 토큰 문자열이 아니다.
 *
 * <h2>여정이 살아 있을 때만 받는다</h2>
 *
 * <p>끝난 여정의 주소를 받아 두면 아무도 보내지 않는 행이 쌓이고, 그 행은 다음 여정의 발송
 * 대상 조회에 섞일 수 있다(같은 기기가 Activity 를 재사용하기 때문이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayLiveActivityTokenService implements ManageLiveActivityTokenUseCase {

    private final SubwayLiveActivityTokenRepository tokenRepository;
    private final UserPushTokenRepository pushTokenRepository;
    private final JourneySessionRepository sessionRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void register(Long userId, Long journeyId, String activityToken, String pushToken,
                         PushEnvironment environment) {
        ownedJourney(userId, journeyId);
        UserPushToken device = pushTokenRepository
                .findByUserIdAndTokenAndDeletedAtIsNull(userId, pushToken)
                // 기기 주소가 먼저다. 없는 채로 받아 두면 조용히 못 보내는 행이 쌓인다.
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.PUSH_TOKEN_NOT_FOUND));

        Instant now = Instant.now(clock);
        tokenRepository.register(userId, journeyId, activityToken, device.getId(),
                environment.name(), now, LocalDateTime.now(clock));
        log.debug("[SubwayRingPush] Live Activity 토큰 등록 journeyId={}", journeyId);
    }

    @Override
    @Transactional
    public void unregister(Long userId, Long journeyId, String activityToken) {
        // 여정 소유만 확인한다. 상태는 보지 않는다 — 끝난 여정의 주소를 정리하는 것이 이 API 의
        // 정상 사용이다.
        sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        int released = tokenRepository.unregister(userId, activityToken, LocalDateTime.now(clock));
        log.debug("[SubwayRingPush] Live Activity 토큰 해제 journeyId={} rows={}",
                journeyId, released);
    }

    private JourneySession ownedJourney(Long userId, Long journeyId) {
        JourneySession journey = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        return journey;
    }
}
