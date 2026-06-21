package watoo.grd.nextroute.infrastructure.adapter.in.api.transfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;

import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
@Tag(name = "[공용] 환승 예측")
public class TransferPredictController {

    private final PredictTransferUseCase predictTransferUseCase;
    private final TransferPredictProperties props;
    private final Clock clock;

    @GetMapping("/predict")
    @Operation(summary = "단일 버스 환승(탑승) 예측 — 사용자 도착시각 기준")
    public ResponseEntity<TransferPredictionResult> predict(
            @RequestParam String stopId,
            @RequestParam String routeId,
            @RequestParam(required = false) Integer seq,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant userArrivalAt) {

        if (stopId.isBlank()) throw badRequest("stopId must not be blank");
        if (routeId.isBlank()) throw badRequest("routeId must not be blank");
        if (seq != null && seq <= 0) throw badRequest("seq must be positive");
        validateArrival(userArrivalAt);

        return ResponseEntity.ok(predictTransferUseCase.predict(stopId, routeId, seq, userArrivalAt));
    }

    private void validateArrival(Instant userArrivalAt) {
        Instant now = clock.instant();
        Instant min = now.minusSeconds(props.getPastGraceSeconds());
        Instant max = now.plusSeconds(props.getMaxFutureMinutes() * 60);
        if (userArrivalAt.isBefore(min)) {
            throw badRequest("userArrivalAt too far in the past");
        }
        if (userArrivalAt.isAfter(max)) {
            throw badRequest("userArrivalAt exceeds max horizon (" + props.getMaxFutureMinutes() + "min)");
        }
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
