package watoo.grd.nextroute.common.lock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 슬라이스 IT 용 리더 자격 — 늘 리더. 운영 플래그 꺼짐({@code leader-lease.enabled=false})과 같은 동작이라
 * 리더 잠금이 없던 때의 시험 조건을 그대로 유지한다.
 */
@TestConfiguration
public class AlwaysLeaderLeaseTestConfig {

    @Bean
    LeaderLease alwaysLeaderLease() {
        return FixedLeaderLease.leader();
    }
}
