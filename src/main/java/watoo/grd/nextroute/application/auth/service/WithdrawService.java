package watoo.grd.nextroute.application.auth.service;

import org.springframework.context.ApplicationEventPublisher;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import watoo.grd.nextroute.application.auth.event.TokenGenerationChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.auth.port.in.WithdrawUseCase;
import watoo.grd.nextroute.domain.auth.repository.AppleUserTokenRepository;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.auth.repository.TossUserTokenRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyTelemetryEventRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLiveActivityTokenRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayRingPushOutboxRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.repository.BusAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;
import watoo.grd.nextroute.domain.route.favorite.repository.FavoriteRouteRepository;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchHistoryRepository;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchLogRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserDailyActivityRepository;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 계정 탈퇴. 되돌릴 수 없다.
 *
 * <h2>삭제 경계</h2>
 *
 * <p>발송 주소(push·Live Activity 토큰)·로그인 수단(신원·토스 토큰)·개인 이력(검색·즐겨찾기)은
 * <b>물리 삭제</b>한다 — 남으면 지운 계정으로 알림이 나가거나, 재가입을 막거나, 개인정보가
 * 남는다. 여정 기록은 <b>소프트 삭제</b>한다 — 조회·발송은 여정의 {@code deleted_at IS NULL} 로
 * 이미 막히고, 기록 자체는 분석·재현을 위해 남긴다. 경계 근거는
 * {@code docs/plan/account-withdrawal/ADR-deletion-boundary.md}.
 *
 * <p>{@code journey_notification}·{@code subway_leg_binding} 은 <b>손대지 않는다</b>(V40·V44 삭제 금지
 * 계약). 둘 다 due 쿼리가 {@code journey_session} 을 조인하며 {@code deleted_at IS NULL} 을 보므로,
 * 여정 소프트 삭제만으로 읽기·발송이 멈춘다. 지우면 delivery_key·version 이 재열려 오작동한다.
 *
 * <h2>왜 한 트랜잭션·전부 벌크인가</h2>
 *
 * <p>외부 호출 없이 DB 한 트랜잭션으로 끝낸다. 물리 삭제는 자식→부모 역순으로 FK 를 지킨다.
 * 모든 삭제·변경을 <b>벌크 문장</b>으로 하고 {@code users} 최종 변경도 벌크 update 한 방으로 한다
 * — 관리 엔티티를 dirty-checking 으로 고치면 다른 벌크가 {@code clearAutomatically} 로 컨텍스트를
 * 비울 때 그 변경이 유실되기 때문이다({@link UserRepository#withdraw} 주석 참조).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService implements WithdrawUseCase {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthIdentityRepository authIdentityRepository;
    private final TossUserTokenRepository tossUserTokenRepository;
    private final AppleUserTokenRepository appleUserTokenRepository;

    private final RouteArrivalAlertRepository routeArrivalAlertRepository;
    private final RouteArrivalAlertNotificationRepository routeArrivalAlertNotificationRepository;
    private final BusArrivalAlertRepository busArrivalAlertRepository;
    private final BusAlertNotificationRepository busAlertNotificationRepository;
    private final RouteAlertScheduleRepository routeAlertScheduleRepository;

    private final UserPushTokenRepository userPushTokenRepository;
    private final SubwayLiveActivityTokenRepository subwayLiveActivityTokenRepository;
    private final SubwayRingPushOutboxRepository subwayRingPushOutboxRepository;

    private final RouteSearchHistoryRepository routeSearchHistoryRepository;
    private final RouteSearchLogRepository routeSearchLogRepository;
    private final RouteSearchEventRepository routeSearchEventRepository;
    private final FavoriteRouteRepository favoriteRouteRepository;
    private final UserDailyActivityRepository userDailyActivityRepository;

    private final JourneySessionRepository journeySessionRepository;
    private final JourneyLegRepository journeyLegRepository;
    private final JourneyLegRouteOptionRepository journeyLegRouteOptionRepository;
    private final TransferConnectionRepository transferConnectionRepository;
    private final JourneyEventRepository journeyEventRepository;
    private final JourneyRevisionRepository journeyRevisionRepository;
    private final JourneyTelemetryEventRepository journeyTelemetryEventRepository;
    private final RerouteProposalRepository rerouteProposalRepository;
    private final SubwayBindingEventRepository subwayBindingEventRepository;

    @Override
    @Transactional
    public void withdraw(long userId) {
        Optional<User> active = userRepository.findByIdAndDeletedAtIsNull(userId);
        if (active.isEmpty()) {
            // 미존재 또는 이미 탈퇴. 재호출은 조용히 성공한다(멱등).
            log.debug("[Withdraw] 활성 사용자 없음, 멱등 성공: userId={}", userId);
            return;
        }
        // 벌크 삭제가 컨텍스트를 비우기 전에 값만 읽어 둔다.
        Long tossUserKey = active.get().getTossUserKey();
        LocalDateTime now = LocalDateTime.now();

        // ── 물리 삭제 (자식 → 부모 역순) ──────────────────────────────────────
        // 알림 자식(발송 이력)을 부모 알림보다 먼저.
        routeArrivalAlertNotificationRepository.deleteAllByUserId(userId);
        busAlertNotificationRepository.deleteAllByUserId(userId);
        // 알림 부모·스케줄.
        routeArrivalAlertRepository.deleteAllByUserId(userId);
        busArrivalAlertRepository.deleteAllByUserId(userId);
        routeAlertScheduleRepository.deleteAllByUserId(userId);
        // 여정에 매달린 진행 링 대기열(여정은 소프트 삭제라 여기서만 실제로 없앤다).
        subwayRingPushOutboxRepository.deleteAllByUserJourneys(userId);
        // 발송 주소.
        subwayLiveActivityTokenRepository.deleteAllByUserId(userId);
        userPushTokenRepository.deleteAllByUserId(userId);
        // 개인 이력. (벌크 DML — 파생 delete 는 뒤이은 벌크의 clearAutomatically 에 유실된다)
        routeSearchHistoryRepository.deleteAllByUserId(userId);
        favoriteRouteRepository.deleteAllByUserId(userId);
        routeSearchLogRepository.deleteAllByUserId(userId);
        routeSearchEventRepository.deleteAllByUserId(userId);
        userDailyActivityRepository.deleteAllByUserId(userId);
        // 로그인 수단.
        authIdentityRepository.deleteAllByUserId(userId);
        appleUserTokenRepository.deleteAllByUserId(userId);
        if (tossUserKey != null) {
            tossUserTokenRepository.deleteAllByUserKey(tossUserKey);
        }

        // 버전 캐시를 "지워짐"으로 덮을 여정들. 소프트 삭제 뒤에는 deleted_at 으로 가려 못 찾는다.
        List<Long> journeyIds = journeySessionRepository.findLiveIdsByUserId(userId);

        // ── 소프트 삭제 (여정 계열 9개, 순서 무관) ────────────────────────────
        journeyLegRouteOptionRepository.softDeleteByUserId(userId, now);
        journeyLegRepository.softDeleteByUserId(userId, now);
        transferConnectionRepository.softDeleteByUserId(userId, now);
        journeyEventRepository.softDeleteByUserId(userId, now);
        journeyRevisionRepository.softDeleteByUserId(userId, now);
        subwayBindingEventRepository.softDeleteByUserId(userId, now);
        journeyTelemetryEventRepository.softDeleteByUserId(userId, now);
        rerouteProposalRepository.softDeleteByUserId(userId, now);
        journeySessionRepository.softDeleteByUserId(userId, now);

        // ── users 최종 변경 (벌크 한 방: soft delete + 세대++ + toss NULL) ─────
        int updated = userRepository.withdraw(userId, now);
        // 세대 캐시가 커밋 직후 "없는 사용자"로 덮는다 — 안 하면 캐시 수명(1분)만큼 탈퇴한 계정의 토큰이 통한다.
        eventPublisher.publishEvent(new TokenGenerationChangedEvent(userId));
        if (!journeyIds.isEmpty()) {
            // 여정 조건부 조회 캐시도 커밋 직후 "지워짐"으로 덮는다(처방 4차).
            eventPublisher.publishEvent(JourneyVersionChangedEvent.of(journeyIds));
        }
        log.info("[Withdraw] 탈퇴 완료: userId={}, usersUpdated={}", userId, updated);
    }
}
