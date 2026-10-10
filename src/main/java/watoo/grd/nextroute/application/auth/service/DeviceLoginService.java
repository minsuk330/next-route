package watoo.grd.nextroute.application.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.exception.DeviceAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkLimitExceededException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkNotAllowedException;
import watoo.grd.nextroute.application.auth.exception.DeviceSignupRateLimitedException;
import watoo.grd.nextroute.application.auth.exception.InvalidDeviceSecretException;
import watoo.grd.nextroute.application.auth.port.in.DeviceLoginUseCase;
import watoo.grd.nextroute.application.auth.port.out.DeviceSignupLimitPort;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 기기 익명 계정. 독립 앱은 로그인 화면 없이 앱이 만든 비밀값 하나로 계정을 받는다.
 *
 * <p>비밀값은 256비트 난수라 그 자체가 비밀번호다. 서버는 sha256 해시만 {@code provider_subject} 로 저장한다 —
 * 난수라 사전 공격이 성립하지 않으므로 salt 는 쓰지 않는다. 원문은 로그·예외 메시지 어디에도 남기지 않는다.
 *
 * <p>분기({@code docs/plan/device-anonymous-auth/PLAN.md} §2):
 * <ul>
 *   <li>Bearer 없음: 등록된 해시면 그 계정, 처음 보는 해시면 새 계정(가입 한도 적용)</li>
 *   <li>Bearer 있음: 같은 계정 해시면 그대로, 다른 계정 해시면 409, 처음 보는 해시면 현재 계정에 연결</li>
 * </ul>
 *
 * <p><b>연결은 구버전 앱 이행용 한시 통로다.</b> 훔친 access 토큰(1시간)으로 공격자 비밀값을 영구히 붙이는 경로가
 * 되므로, 애플·카카오 신원이 있는 계정에만, 기기 3대까지만 허용한다. 애플·카카오 로그인 코드를 지울 때 같이 없앤다.
 */
@Slf4j
@Service
public class DeviceLoginService implements DeviceLoginUseCase {

    /** base64url 32바이트 = 43자. 여유를 두되 상한을 둬서 해시 전에 거대한 값을 거른다. */
    private static final Pattern SECRET_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");
    static final int MAX_DEVICES_PER_USER = 3;

    private final AuthIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final DeviceSignupLimitPort signupLimit;
    private final JwtProvider jwtProvider;
    private final Clock clock;
    /**
     * 메서드 전체에 {@code @Transactional} 을 걸지 않는다. 가입 경합으로 유니크 위반이 나면 그 트랜잭션은
     * rollback-only 가 되어, 같은 트랜잭션 안에서 재조회에 성공해도 커밋에서 터진다. 그래서 본 작업과 경합 재조회를
     * 각각 독립된 트랜잭션으로 연다.
     */
    private final TransactionTemplate tx;

    public DeviceLoginService(AuthIdentityRepository identityRepository,
                              UserRepository userRepository,
                              DeviceSignupLimitPort signupLimit,
                              JwtProvider jwtProvider,
                              Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.signupLimit = signupLimit;
        this.jwtProvider = jwtProvider;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public OAuthLoginResult login(String deviceSecret, Long currentUserId, String clientIp) {
        if (deviceSecret == null || !SECRET_FORMAT.matcher(deviceSecret).matches()) {
            throw new InvalidDeviceSecretException();
        }
        String subject = hash(deviceSecret);
        try {
            return tx.execute(status -> currentUserId == null
                    ? enterAnonymously(subject, clientIp)
                    : enterWithAccount(subject, currentUserId));
        } catch (DataIntegrityViolationException e) {
            // 같은 비밀값으로 동시에 첫 호출이 두 번 들어온 정상 경합이다. 상대가 만든 것을 새 트랜잭션에서 읽는다.
            log.debug("[DeviceLogin] 가입·연결 경합 — 재조회한다");
            return tx.execute(status -> resolveRace(subject, currentUserId));
        }
    }

    private OAuthLoginResult enterAnonymously(String subject, String clientIp) {
        AuthIdentity existing = findDevice(subject);
        if (existing != null) {
            existing.touchLogin(now());
            return issueTokens(existing.getUserId());
        }
        if (!signupLimit.tryAcquire(clientIp)) {
            throw new DeviceSignupRateLimitedException();
        }
        User user = userRepository.saveAndFlush(User.builder().build());
        saveDevice(user.getId(), subject);
        return issueTokens(user.getId());
    }

    private OAuthLoginResult enterWithAccount(String subject, long currentUserId) {
        AuthIdentity existing = findDevice(subject);
        if (existing != null) {
            if (!existing.getUserId().equals(currentUserId)) {
                throw new DeviceAlreadyLinkedException();
            }
            existing.touchLogin(now());
            return issueTokens(currentUserId);
        }
        requireLinkable(currentUserId);
        saveDevice(currentUserId, subject);
        return issueTokens(currentUserId);
    }

    /**
     * 연결 조건. 애플·카카오 신원이 있어야 하고(토스 전용 계정이 독립 앱 계정과 섞이지 않게), 기기는 3대까지다.
     *
     * <p>상한은 세고 나서 넣으므로 동시 연결 두 건이 3대를 살짝 넘길 수 있다. 한시 통로의 남용 상한이라 감수한다.
     */
    private void requireLinkable(long userId) {
        boolean hasAppIdentity =
                identityRepository.existsByUserIdAndProviderAndDeletedAtIsNull(userId, AuthProvider.APPLE)
                        || identityRepository.existsByUserIdAndProviderAndDeletedAtIsNull(userId, AuthProvider.KAKAO);
        if (!hasAppIdentity) {
            throw new DeviceLinkNotAllowedException();
        }
        long devices = identityRepository.countByUserIdAndProviderAndDeletedAtIsNull(userId, AuthProvider.DEVICE);
        if (devices >= MAX_DEVICES_PER_USER) {
            throw new DeviceLinkLimitExceededException();
        }
    }

    private OAuthLoginResult resolveRace(String subject, Long currentUserId) {
        AuthIdentity winner = findDevice(subject);
        if (winner == null) {
            throw new IllegalStateException("기기 신원 경합 뒤 재조회에서도 신원이 없다");
        }
        if (currentUserId != null && !winner.getUserId().equals(currentUserId)) {
            throw new DeviceAlreadyLinkedException();
        }
        return issueTokens(winner.getUserId());
    }

    private AuthIdentity findDevice(String subject) {
        return identityRepository
                .findByProviderAndProviderSubjectAndDeletedAtIsNull(AuthProvider.DEVICE, subject)
                .orElse(null);
    }

    private void saveDevice(long userId, String subject) {
        identityRepository.saveAndFlush(AuthIdentity.builder()
                .userId(userId)
                .provider(AuthProvider.DEVICE)
                .providerSubject(subject)
                .linkedAt(now())
                .build());
    }

    /** 토큰에는 현재 세대 번호를 담는다. 로그아웃·탈퇴가 이 번호를 올려 토큰을 끊는다. */
    private OAuthLoginResult issueTokens(long userId) {
        int generation = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("신원에 연결된 사용자가 없다: " + userId))
                .getTokenGeneration();
        return new OAuthLoginResult(
                jwtProvider.createAccessToken(userId, generation),
                jwtProvider.createRefreshToken(userId, generation),
                userId,
                AuthProvider.DEVICE);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    static String hash(String deviceSecret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(deviceSecret.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
