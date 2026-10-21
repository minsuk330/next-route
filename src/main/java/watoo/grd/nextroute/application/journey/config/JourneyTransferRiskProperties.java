package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 환승 위험도 판정의 초기 운영값.
 *
 * <p>임계값을 코드에 박지 않는 이유는 이 숫자들이 <b>운영 데이터로 튜닝할 값</b>이기 때문이다.
 * 처음 값은 추정이고, 실제 놓침·헛알림 비율을 보고 조정한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.transfer-risk")
public class JourneyTransferRiskProperties {

    /**
     * 하차에 걸리는 시간.
     *
     * <p>버스가 정류장에 선 시각과 사용자가 실제로 내려서 걷기 시작하는 시각은 다르다. 이 값을
     * 0으로 두면 환승 여유가 실제보다 넉넉해 보인다.
     */
    private long alightingBufferSeconds = 30;

    /** 이 여유 이상이면 편안하다. */
    private long comfortableSeconds = 300;

    /** 이 여유 이상이면 탈 수 있다. 미만이면 위험하다. */
    private long catchableSeconds = 120;

    /**
     * 좋은 상태로 되돌아갈 때 더 요구하는 여유.
     *
     * <p>임계값 하나만 쓰면 여유가 임계 근처에서 흔들릴 때 상태가 오르내리며 알림이 반복된다.
     * 나빠질 때는 곧바로 바꾸고 좋아질 때만 이만큼 더 요구해 그 떨림을 막는다.
     */
    private long hysteresisSeconds = 60;

    /**
     * 환승 판정 밖 대기(첫 승차, 아직 시작 전인 환승의 대기)도 평가기가 예측한다(#480). 기본 켬. 끄면 예전처럼 환승 대기만
     * 묻는다 — 배포 뒤 공급자 슬롯·호출량이 버거우면 env {@code JOURNEY_PREDICT_ALL_WAITS=false} 로 되돌리는 스위치다.
     */
    private boolean predictAllWaits = true;

    @PostConstruct
    void validate() {
        if (alightingBufferSeconds < 0 || hysteresisSeconds < 0 || catchableSeconds < 0) {
            throw new IllegalStateException("journey.transfer-risk 설정은 0 이상이어야 한다");
        }
        if (catchableSeconds >= comfortableSeconds) {
            throw new IllegalStateException(
                    "catchable 임계는 comfortable 보다 작아야 한다. 아니면 구간이 뒤집힌다");
        }
    }
}
