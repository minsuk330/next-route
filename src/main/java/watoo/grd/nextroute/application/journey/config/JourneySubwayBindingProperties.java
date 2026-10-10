package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 지하철 열차 고정 설정.
 *
 * <p>모션 증거 기반 자동 판정기는 2026-10 에 제거했다(앱이 모션 자동 탑승을 없앴다). 고정은 사용자 선택만 만든다.
 * 남은 값은 선택 경로의 잠금 대기와 감사행 표기뿐이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.binding")
public class JourneySubwayBindingProperties {

    /** 감사행에 박는 알고리즘 판. 기존 행과 이어 읽히도록 판정기 시절 값을 그대로 쓴다. */
    private String algorithmVersion = "m7-v1";

    /**
     * 사용자 요청 경로가 고정 행을 잠글 때 기다리는 상한. 넘기면 재시도 가능한 실패로 답한다.
     *
     * <p><b>JPA 힌트로는 이걸 못 한다.</b> PostgreSQL 에는 {@code FOR UPDATE WAIT n} 이 없어
     * Hibernate 가 {@code jakarta.persistence.lock.timeout} 의 임의 값을 그냥 {@code for update}
     * 로 떨어뜨린다(0=NOWAIT, -2=SKIP LOCKED 만 문장으로 나간다). 그래서 트랜잭션 안에서
     * {@code SET LOCAL lock_timeout} 을 직접 건다.
     */
    private Duration lockTimeout = Duration.ofSeconds(1);
}
