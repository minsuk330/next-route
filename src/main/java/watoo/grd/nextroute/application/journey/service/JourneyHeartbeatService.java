package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.HeartbeatJourneyUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;

/**
 * 진행도를 만들 수 없을 때 안내 lease 만 연장하는 경로.
 *
 * <p>앱은 보통 progress 로 lease 를 함께 연장한다(15초, 하차 임박 5초). 그런데 GPS 정확도가
 * 낮거나 터널 안이거나 아직 첫 위치 sample 을 못 받은 동안에는 보낼 진행도가 없다. 그때도 앱이
 * 살아 있다는 사실은 알려야 하므로 최대 30초 간격으로 이 경로를 쓴다(SC-7 5항).
 *
 * <p>{@code commandVersion} 도 {@code evaluationVersion} 도 올리지 않는다. 여정 상태가 바뀐 것이
 * 없고, 번호를 올리면 앱이 손에 든 번호가 이유 없이 낡는다.
 *
 * <p>실제 쓰기는 여정별 최대 60초에 한 번으로 합친다. 30초마다 이 행을 쓰면 여정 하나가 계속
 * 갱신되는 hot row 가 된다. lease 는 3분이라 60초 간격으로 늘려도 만료되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JourneyHeartbeatService implements HeartbeatJourneyUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyQueryService queryService;
    private final JourneyNavigationProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public JourneySnapshot heartbeat(Long userId, Long journeyId) {
        // 소유자를 쿼리 조건에 넣는다. 꺼낸 뒤에 비교하는 방식은 언젠가 빠뜨린다.
        JourneySession journey = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            // 끝난 여정은 되살리지 않는다. 만료된 여정을 heartbeat 로 복원하면 만료의 의미가 없다.
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }

        Instant now = Instant.now(clock);
        sessionRepository.extendLeaseIfStale(
                journeyId,
                now,
                now.plus(properties.guidanceLease()),
                now.minus(properties.leaseWriteCoalesce()));

        // extendLeaseIfStale 이 영속성 컨텍스트를 비우므로(clearAutomatically) 다시 읽어야
        // 연장된 lease 가 응답에 실린다.
        JourneySession refreshed = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        return queryService.snapshotOf(refreshed);
    }
}
