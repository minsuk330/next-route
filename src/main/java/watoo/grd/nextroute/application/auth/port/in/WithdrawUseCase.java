package watoo.grd.nextroute.application.auth.port.in;

/**
 * 계정 탈퇴.
 *
 * <p>되돌릴 수 없다. 발송 주소·로그인 수단·개인 이력은 물리 삭제하고, 여정 기록은 소프트 삭제한다
 * (경계는 docs/plan/account-withdrawal/ADR-deletion-boundary.md). {@code users} 행은 소프트 삭제하며
 * 세대 번호를 올려 이전 토큰을 끊고 토스 신원을 지운다.
 */
public interface WithdrawUseCase {

    /**
     * 계정 탈퇴. 이미 삭제된 사용자면 조용히 성공한다(멱등).
     *
     * @param userId 탈퇴할 사용자 id
     */
    void withdraw(long userId);
}
