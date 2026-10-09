package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

/**
 * 부팅 직후 전 노선·방향의 지하철 패턴을 데운다(#291). 배포 직후 첫 사용자가 열차 안에서 콜드 적재를
 * 기다리지 않게 한다.
 *
 * <p><b>별도 스레드</b>에서 돈다. 노선·방향 38개를 순서대로 받으면 수십 초가 걸리는데, 이벤트 스레드에서
 * 돌리면 그만큼 준비 완료가 늦어져 배포 healthcheck 를 위협한다.
 *
 * <p>worker 는 이 API 를 서비스하지 않으므로 제외한다({@link watoo.grd.nextroute.application.map.service.BusStopSnapshotWarmup}
 * 와 같은 판단). 실패는 로그만 — 첫 요청에서 다시 받는다.
 */
@Slf4j
@Component
@Profile("!worker")
@RequiredArgsConstructor
public class SubwayLinePatternWarmup {

    private final SubwayLinePatternService patternService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread thread = new Thread(this::warmUp, "subway-pattern-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    void warmUp() {
        long started = System.currentTimeMillis();
        int keys = 0;
        int patterns = 0;
        int failed = 0;
        for (String lineId : new TreeSet<>(SubwayRoutePatternAssembler.LINE_IDS.values())) {
            for (String direction : directions(lineId)) {
                keys++;
                try {
                    patterns += patternService.patterns(lineId, direction).size();
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("[OTP] 지하철 패턴 워밍업 실패 line={} direction={}: {}", lineId, direction, e.getMessage());
                }
            }
        }
        log.info("지하철 패턴 워밍업 완료 keys={} patterns={} failed={} ms={}",
                keys, patterns, failed, System.currentTimeMillis() - started);
    }

    /** 순환선(2호선)만 내·외선이다. */
    public static List<String> directions(String lineId) {
        return "1002".equals(lineId) ? List.of("내선", "외선") : List.of("상행", "하행");
    }
}
