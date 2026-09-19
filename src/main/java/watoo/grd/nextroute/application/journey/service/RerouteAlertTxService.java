package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;

/**
 * "더 빠른 길이 있어요" 한 줄을 outbox 에 쌓고, 그 제안에 <b>알렸다</b>고 표시한다
 * (journey-reroute-nextstop PR-3).
 *
 * <p>둘이 한 트랜잭션인 이유: 표시만 남고 알림이 없으면 사용자는 받지도 못한 알림 때문에 무응답
 * 상한을 깎이고, 알림만 나가고 표시가 없으면 답이 없어도 영영 무응답으로 세지 않아 상한이 차지
 * 않는다.
 *
 * <p>{@link RerouteProposalTxService} 에 넣지 않은 것은 의도다 — 그쪽은 <b>제안의 수명</b>을
 * 소유하고, 알림 outbox 는 다른 표·다른 relay 의 일이다. 한 클래스가 둘을 다 들면 제안을 읽는
 * 모든 경로가 알림 배선을 끌고 다닌다.
 */
@Service
@RequiredArgsConstructor
public class RerouteAlertTxService {

    private final JourneyNotificationEnqueuer enqueuer;
    private final RerouteProposalRepository proposalRepository;

    /**
     * 채널마다 한 줄씩 쌓는다.
     *
     * <p><b>한 채널이라도 실제로 생겼을 때만</b> 표시한다. 전부 중복(같은 제안에 이미 알림이
     * 나갔다)이면 새로 알린 것이 아니므로 relay 를 깨울 이유도 없다.
     *
     * @return 알림 행이 새로 생겼으면 true
     */
    @Transactional
    public boolean alert(RerouteProposalView view, int gainSeconds,
                         List<NotificationChannel> channels, Instant now) {
        boolean enqueued = false;
        for (NotificationChannel channel : channels) {
            enqueued |= enqueuer.enqueueRerouteSuggested(view.journeyId(), view.routeRevision(),
                    view.proposalId(), gainSeconds, channel, now, view.expiresAt());
        }
        if (enqueued) {
            proposalRepository.markAlerted(view.proposalId());
        }
        return enqueued;
    }
}
