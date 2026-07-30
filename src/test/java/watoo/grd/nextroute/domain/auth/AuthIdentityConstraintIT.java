package watoo.grd.nextroute.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V33 이 만든 제약을 실제 PostgreSQL 로 확인한다.
 *
 * <p>이 규칙들은 코드가 아니라 DB 가 소유한다. 나중에 연결 API 가 생겼을 때 코드 실수를
 * 마지막에 막아 주는 자리이므로, 인덱스가 실제로 걸려 있는지를 여기서 고정한다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
class AuthIdentityConstraintIT {

    @Autowired JdbcTemplate jdbcTemplate;

    private long newUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (created_at, updated_at) VALUES (now(), now()) RETURNING id",
                Long.class);
    }

    private void insertIdentity(long userId, String provider, String subject) {
        jdbcTemplate.update("""
                INSERT INTO auth_identity
                    (user_id, provider, provider_subject, linked_at, created_at, updated_at)
                VALUES (?, ?, ?, now(), now(), now())
                """, userId, provider, subject);
    }

    private void softDelete(long userId, String provider) {
        jdbcTemplate.update(
                "UPDATE auth_identity SET deleted_at = now() WHERE user_id = ? AND provider = ?",
                userId, provider);
    }

    // ── 앱 로그인 수단은 계정당 하나 ──────────────────────────────────────────

    @Test
    @DisplayName("한 계정에 애플과 카카오를 같이 붙일 수 없다")
    void twoAppProviders_rejected() {
        long userId = newUser();
        insertIdentity(userId, "APPLE", "apple-1");

        assertThatThrownBy(() -> insertIdentity(userId, "KAKAO", "kakao-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("토스는 예외라 앱 수단 하나와 공존할 수 있다")
    void tossPlusAppProvider_allowed() {
        long userId = newUser();
        insertIdentity(userId, "TOSS", "toss-1");

        assertThatCode(() -> insertIdentity(userId, "APPLE", "apple-1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("앱 수단을 지운 표시하면 다른 앱 수단을 붙일 수 있다")
    void softDeletedAppProvider_allowsAnother() {
        long userId = newUser();
        insertIdentity(userId, "APPLE", "apple-1");
        softDelete(userId, "APPLE");

        assertThatCode(() -> insertIdentity(userId, "KAKAO", "kakao-1")).doesNotThrowAnyException();
    }

    // ── 한 회사의 한 번호는 한 사용자에게만 ────────────────────────────────────

    @Test
    @DisplayName("같은 회사의 같은 번호를 두 사용자가 나눠 가질 수 없다")
    void sameProviderSubjectAcrossUsers_rejected() {
        insertIdentity(newUser(), "APPLE", "shared-subject");
        long second = newUser();

        assertThatThrownBy(() -> insertIdentity(second, "APPLE", "shared-subject"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("다른 회사면 번호가 같아도 상관없다 — 번호 체계가 회사마다 다르다")
    void sameSubjectDifferentProvider_allowed() {
        insertIdentity(newUser(), "APPLE", "12345");

        assertThatCode(() -> insertIdentity(newUser(), "KAKAO", "12345")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("지운 표시된 번호는 다시 쓸 수 있다 — 탈퇴 후 재가입 경로를 막지 않는다")
    void softDeletedSubject_reusable() {
        long first = newUser();
        insertIdentity(first, "APPLE", "apple-1");
        softDelete(first, "APPLE");

        assertThatCode(() -> insertIdentity(newUser(), "APPLE", "apple-1")).doesNotThrowAnyException();
    }

    // ── provider 값 제한 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("정의되지 않은 provider 는 저장할 수 없다")
    void unknownProvider_rejected() {
        long userId = newUser();

        assertThatThrownBy(() -> insertIdentity(userId, "GOOGLE", "g-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── users 완화 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토스 번호 없이 사용자를 만들 수 있다 — V33 이 NOT NULL 을 풀었다")
    void userWithoutTossKey_allowed() {
        assertThatCode(this::newUser).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토스 번호가 비어 있는 사용자는 여럿이어도 된다 — 중복 금지는 값이 있을 때만이다")
    void multipleUsersWithNullTossKey_allowed() {
        newUser();
        newUser();

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE toss_user_key IS NULL", Long.class);
        assertThat(count).isGreaterThanOrEqualTo(2);
    }
}
