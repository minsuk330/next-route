package watoo.grd.nextroute.application.subway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 급행 열차 추적 스위치(subway-express PR-S3·S4).
 *
 * <p><b>꺼져 있으면(기본) 급행 열차는 열차 고르기에서 막힌다</b> — 후보에 {@code trackable=false} 로 보이고 고르면 422
 * {@code TRAIN_NOT_TRACKABLE}. 진행 링이 지나치는 역을 정차로 다루는 문제(통과역 신호, PR-S4)를 실제 탑승 녹화로 검증하기
 * 전까지 급행을 일반처럼 추적하지 않는다. 일반 열차는 이 값과 무관하다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "subway.express.tracking")
public class SubwayExpressTrackingProperties {

    private boolean enabled = false;
}
