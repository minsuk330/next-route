package watoo.grd.nextroute.application.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.IdentityAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.exception.ProviderNotAllowedException;
import watoo.grd.nextroute.application.auth.port.in.OAuthLoginUseCase;
import watoo.grd.nextroute.application.auth.port.out.OAuthChallengePort;
import watoo.grd.nextroute.application.auth.port.out.OAuthIdentityVerifier;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 앱 OAuth 로그인. 처음 보는 신원이면 가입까지 겸한다.
 *
 * <p>이메일이 같아도 다른 회사면 별개 계정을 만든다. 서버는 그 이메일이 그 사람 것인지 확인한 적이
 * 없고, 애플은 가려진 이메일을 준다. 이메일로 자동 병합하면 남의 이메일을 아는 사람이 계정에 들어간다.
 */
@Slf4j
@Service
public class OAuthLoginService implements OAuthLoginUseCase {

    private final Map<AuthProvider, OAuthIdentityVerifier> verifiers;
    private final AuthIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final OAuthChallengePort challengePort;
    private final JwtProvider jwtProvider;
    private final Clock clock;
    /** 가입 경합 재조회 전용. 충돌한 트랜잭션은 롤백 대상이라 반드시 새 트랜잭션에서 읽어야 한다. */
    private final TransactionTemplate newTransaction;

    public OAuthLoginService(List<OAuthIdentityVerifier> verifierList,
                             AuthIdentityRepository identityRepository,
                             UserRepository userRepository,
                             OAuthChallengePort challengePort,
                             JwtProvider jwtProvider,
                             Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.verifiers = new EnumMap<>(AuthProvider.class);
        verifierList.forEach(v -> this.verifiers.put(v.provider(), v));
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.challengePort = challengePort;
        this.jwtProvider = jwtProvider;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String issueChallenge() {
        return challengePort.issue();
    }

    @Override
    @Transactional
    public OAuthLoginResult login(OAuthCredential credential) {
        AuthProvider provider = credential.provider();
        if (provider == null || !provider.isAppLoginAllowed()) {
            throw new ProviderNotAllowedException(provider);
        }

        VerifiedIdentity verified = verifier(provider).verify(credential);
        requireSubject(verified);

        LocalDateTime now = LocalDateTime.now(clock);
        AuthIdentity identity = identityRepository
                .findByProviderAndProviderSubjectAndDeletedAtIsNull(provider, verified.subject())
                .orElse(null);

        if (identity == null) {
            return signUp(verified, credential.displayName(), now);
        }

        identity.touchLogin(now);
        identity.fillProfileIfAbsent(verified.email(), credential.displayName());
        return issueTokens(identity.getUserId(), provider);
    }

    /**
     * 처음 보는 신원이면 <b>새 사용자</b>를 만든다. 기존 계정에 붙이지 않으므로
     * "앱 로그인 수단 하나만" 규칙과 부딪히지 않는다.
     *
     * <p>같은 사용자가 로그인을 두 번 빠르게 누르면 두 요청이 동시에 "없음"을 보고 둘 다 만들려 한다.
     * 이때 하나는 중복 금지에 걸리는데 이건 오류가 아니라 정상 경합이다. 409 로 돌려주면 멀쩡한
     * 로그인이 실패하므로, 새 트랜잭션에서 재조회해 상대가 만든 것을 쓴다.
     */
    private OAuthLoginResult signUp(VerifiedIdentity verified, String displayName, LocalDateTime now) {
        try {
            User user = userRepository.saveAndFlush(User.builder().build());
            AuthIdentity created = identityRepository.saveAndFlush(AuthIdentity.builder()
                    .userId(user.getId())
                    .provider(verified.provider())
                    .providerSubject(verified.subject())
                    .email(blankToNull(verified.email()))
                    .displayName(blankToNull(displayName))
                    .linkedAt(now)
                    .build());
            return issueTokens(created.getUserId(), created.getProvider());
        } catch (DataIntegrityViolationException e) {
            log.debug("[OAuth] 가입 경합 — 재조회한다: provider={}", verified.provider());
            return resolveRace(verified);
        }
    }

    /**
     * 충돌한 트랜잭션은 롤백 대상이라 같은 안에서 읽으면 안 된다. 새 트랜잭션에서 확인한다.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)} 를 같은 클래스 메서드에 붙이면 자기호출이라
     * 프록시를 거치지 않아 새 트랜잭션이 열리지 않는다. 그래서 TransactionTemplate 을 직접 쓴다.
     */
    private OAuthLoginResult resolveRace(VerifiedIdentity verified) {
        return newTransaction.execute(status -> identityRepository
                .findByProviderAndProviderSubjectAndDeletedAtIsNull(
                        verified.provider(), verified.subject())
                .map(existing -> issueTokens(existing.getUserId(), existing.getProvider()))
                // 재조회해도 없으면 "앱 수단 하나만" 규칙 위반이다(연결 API 가 생긴 뒤에나 발생).
                .orElseThrow(() -> new IdentityAlreadyLinkedException(verified.provider())));
    }

    /**
     * 토큰에는 그 사용자의 현재 세대 번호를 함께 담는다. 나중에 로그아웃이 이 번호를 올려
     * 여기서 발급한 토큰을 끊는다.
     *
     * <p>가입 직후 경로에서는 방금 저장한 사용자가 영속성 컨텍스트에 있어 추가 조회가 되지 않는다.
     */
    private OAuthLoginResult issueTokens(Long userId, AuthProvider provider) {
        int generation = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("신원에 연결된 사용자가 없다: " + userId))
                .getTokenGeneration();
        return new OAuthLoginResult(
                jwtProvider.createAccessToken(userId, generation),
                jwtProvider.createRefreshToken(userId, generation),
                userId,
                provider);
    }

    private OAuthIdentityVerifier verifier(AuthProvider provider) {
        OAuthIdentityVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            throw new ProviderNotAllowedException(provider);
        }
        return verifier;
    }

    /**
     * 사용자 번호가 비었으면 거부한다. 이 값이 신원의 전부라, 빈 값이 통과하면 서로 다른 사람이
     * 한 계정으로 합쳐진다.
     */
    private static void requireSubject(VerifiedIdentity verified) {
        String subject = verified.subject();
        if (subject == null || subject.isBlank() || "0".equals(subject.trim())) {
            throw new OAuthVerificationException("provider 가 유효한 사용자 번호를 주지 않았다");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
