package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** 버스 도착 라벨을 날짜 단위로 생성하는 공용 업무 서비스. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusArrivalLabelBatchService {

    @Value("${batch.bus-label.enabled:true}")
    private boolean enabled;

    private final BusArrivalLabelGenerationService labelGenerationService;

    public int runForDate(LocalDate serviceDate) {
        if (!enabled) {
            log.info("[BusLabelBatch] Disabled, skipping serviceDate={}", serviceDate);
            return 0;
        }
        log.info("[BusLabelBatch] Starting for serviceDate={}", serviceDate);
        long start = System.currentTimeMillis();
        try {
            int count = labelGenerationService.generateForDate(serviceDate);
            log.info("[BusLabelBatch] Completed serviceDate={} rows={} elapsed={}ms",
                    serviceDate, count, System.currentTimeMillis() - start);
            return count;
        } catch (Exception e) {
            log.error("[BusLabelBatch] Failed for serviceDate={}", serviceDate, e);
            return 0;
        }
    }
}
