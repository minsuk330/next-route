package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 서울 도착 응답의 운행종료 판정(#400). */
@DisplayName("버스 운행 종료 판정")
class BusServiceEndedTest {

    /** KST 23:35. */
    private static final Instant NIGHT = Instant.parse("2026-10-13T14:35:00Z");
    /** KST 04:00. */
    private static final Instant DAWN = Instant.parse("2026-10-13T19:00:00Z");

    @Test
    @DisplayName("1·2번째 모두 운행종료 → 끝남. 2번째가 비어도 끝남")
    void bothEnded() {
        assertThat(BusServiceEnded.isEnded(info("운행종료", "운행종료", "20261013043500"), NIGHT, NIGHT)).isTrue();
        assertThat(BusServiceEnded.isEnded(info("운행종료", null, "20261013043500"), NIGHT, NIGHT)).isTrue();
    }

    @Test
    @DisplayName("2번째에 차가 있거나 1번째가 운행종료가 아니면 끝나지 않음")
    void notEnded() {
        assertThat(BusServiceEnded.isEnded(info("운행종료", "5분49초후[2번째 전]", "20261013043500"), NIGHT, NIGHT)).isFalse();
        assertThat(BusServiceEnded.isEnded(info("곧 도착", "운행종료", "20261013043500"), NIGHT, NIGHT)).isFalse();
        assertThat(BusServiceEnded.isEnded(info("출발대기", "출발대기", "20261013043500"), NIGHT, NIGHT)).isFalse();
        assertThat(BusServiceEnded.isEnded(null, NIGHT, NIGHT)).isFalse();
    }

    @Test
    @DisplayName("첫차 전: 사용자가 첫차 뒤에 닿으면 곧 다닌다, 첫차 전에 닿으면 끝남(자정 넘은 검색)")
    void beforeFirstBus() {
        // 04:00 검색, 첫차 04:35, 사용자 04:40 도착 → 탈 수 있다
        assertThat(BusServiceEnded.isEnded(info("운행종료", "운행종료", "20261013043500"), DAWN,
                DAWN.plusSeconds(40 * 60))).isFalse();
        // 04:00 검색, 사용자 04:10 도착 → 첫차 전이라 그 시각엔 없다
        assertThat(BusServiceEnded.isEnded(info("운행종료", "운행종료", "20261013043500"), DAWN,
                DAWN.plusSeconds(10 * 60))).isTrue();
    }

    @Test
    @DisplayName("첫차 시각을 못 읽으면 메시지만 믿는다(기점의 10자리 값)")
    void brokenFirstTime() {
        assertThat(BusServiceEnded.isEnded(info("운행종료", "운행종료", "2026101300"), DAWN,
                DAWN.plusSeconds(40 * 60))).isTrue();
    }

    /** 필요한 칸만 채우고 나머지는 null. 생성자 칸이 많아 레코드 구성 요소 이름으로 채운다. */
    private static BusArrivalInfo info(String msg1, String msg2, String firstTm) {
        RecordComponent[] parts = BusArrivalInfo.class.getRecordComponents();
        Object[] args = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            switch (parts[i].getName()) {
                case "arrivalMsg1" -> args[i] = msg1;
                case "arrivalMsg2" -> args[i] = msg2;
                case "firstBusTime" -> args[i] = firstTm;
                default -> args[i] = null;
            }
        }
        try {
            Class<?>[] types = Arrays.stream(parts).map(RecordComponent::getType).toArray(Class<?>[]::new);
            return BusArrivalInfo.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
