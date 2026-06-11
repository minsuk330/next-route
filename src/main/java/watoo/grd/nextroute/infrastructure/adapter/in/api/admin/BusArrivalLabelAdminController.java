package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.BusArrivalLabelBatchScheduler;

@RestController
@RequestMapping("/api/admin/bus/batch")
@RequiredArgsConstructor
@Tag(name = "[어드민] 버스 라벨 배치")
public class BusArrivalLabelAdminController {

    private final BusArrivalLabelBatchScheduler labelBatchScheduler;

    @PostMapping("/label/trigger")
    @Operation(summary = "버스 라벨 배치 수동 트리거 (개발용)",
            description = "date 미지정 시 어제(KST) 날짜로 실행. bus_arrival_label_event를 service_date 단위로 재생성한다.")
    public ResponseEntity<Map<String, Object>> triggerLabelBatch(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate serviceDate = (date != null)
                ? date
                : LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);

        int rows = labelBatchScheduler.runForDate(serviceDate);
        return ResponseEntity.ok(Map.of("serviceDate", serviceDate.toString(), "rows", rows));
    }
}
