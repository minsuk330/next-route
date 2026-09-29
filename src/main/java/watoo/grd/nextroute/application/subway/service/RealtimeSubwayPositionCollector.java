package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 설정된 노선의 실시간 열차 위치를 주기적으로 받아 전이 추적기에 넘긴다.
 *
 * <h2>왜 전 노선을 상시로 받나</h2>
 *
 * <p>전이는 <b>직전 관측이 있어야</b> 보인다. "필요할 때만 그 노선을 켜는" 방식이면 사용자가
 * 승강장에 선 뒤에야 첫 관측을 시작하고, 그 첫 장은 기준점으로만 쓰이므로 원천 갱신 한두 주기
 * (실측 60~90초)가 지나야 판정이 가능해진다. 승강장 도착 2분 안에 타는 일은 흔하다.
 *
 * <p>상시로 받는 비용은 작다 — 원천이 60~90초마다만 바뀌어 15초 주기면 충분하고,
 * 노선 20여 개면 초당 1~2회다. 서울 지하철 API 는 호출수 제한이 없다.
 *
 * <h2>순차로 도는 이유</h2>
 *
 * <p>노선을 병렬로 부르면 한 회차 안에서 노선별 관측 시각이 벌어진다. 전이 판정이 시각 비교라
 * 그 벌어짐이 그대로 잡음이 된다. 순차로 돌아도 한 회차가 주기 안에 끝난다.
 *
 * <p>도착정보 수집기({@code RealtimeSubwayCollector})와 <b>별도 빈·별도 주기</b>다. 축이 다르다 —
 * 저쪽은 전 노선을 한 번에 받고, 이쪽은 노선마다 한 번씩 받는다.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class RealtimeSubwayPositionCollector {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayApiPort subwayApiPort;
    private final SubwayTrainTransitionTracker transitionTracker;
    private final SubwayPositionProperties properties;

    @Scheduled(fixedDelayString = "${realtime.subway.position.poll-interval-ms:15000}")
    public void collect() {
        if (!properties.isEnabled() || properties.getLines().isEmpty()) {
            return;
        }
        if (!isActiveHours()) {
            return;
        }

        int events = 0;
        int failed = 0;
        for (String lineId : properties.getLines()) {
            try {
                SubwayPositionFetchResult result = subwayApiPort.getRealtimePosition(lineId);
                if (result.isBlocked()) {
                    // 차단은 걸리는 순간 [ApiBlocker] 가 한 번 남긴다. 회차마다 또 세면 같은 사건이
                    // 자정까지 15초마다 "실패"로 되풀이된다.
                    continue;
                }
                if (!result.isComplete()) {
                    failed++;
                    continue;
                }
                events += transitionTracker.observe(result);
            } catch (Exception e) {
                // 한 노선의 실패가 나머지 노선을 막지 않는다. 수집이 1차 기능이다.
                failed++;
                log.warn("[PositionCollector] 노선 처리 실패 — lineId={}, {}", lineId, e.getMessage());
            }
        }

        if (failed > 0) {
            log.warn("[PositionCollector] {}/{} 노선 실패, 사건 {}건",
                    failed, properties.getLines().size(), events);
        }
    }

    /**
     * 운행시간 안인지. 도착정보 수집기와 같은 규칙이다(자정을 넘기는 구간).
     *
     * <p>끝시각과 시작시각 사이가 <b>쉬는 시간</b>이다 — 예를 들어 01:00~04:30.
     */
    private boolean isActiveHours() {
        LocalTime now = LocalTime.now(SEOUL);
        LocalTime start = LocalTime.parse(properties.getActiveHoursStart());
        LocalTime end = LocalTime.parse(properties.getActiveHoursEnd());
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
