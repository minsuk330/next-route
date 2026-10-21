package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("열차 선택 감사 payload(subway-express PR-S4)")
class SubwayChosenTrainAuditTest {

    @Test
    @DisplayName("선택 감사 행 payload 에 종류·서비스를 싣는다, 모르면 null 칸")
    void auditPayloadCarriesKind() {
        SubwayTrainSelectionTxService.ChosenTrain express = new SubwayTrainSelectionTxService.ChosenTrain("9600",
                LocalDate.parse("2026-10-21"), "1009", "상행", false, SubwayBindingTrainKind.EXPRESS, "1009-express",
                "2026-10-21");
        SubwayTrainSelectionTxService.ChosenTrain legacy = new SubwayTrainSelectionTxService.ChosenTrain("2390",
                LocalDate.parse("2026-10-21"), "1002", "내선");

        assertThat(express.auditPayload()).isEqualTo("{\"trainKind\":\"EXPRESS\",\"serviceId\":\"1009-express\"}");
        assertThat(legacy.auditPayload()).isEqualTo("{\"trainKind\":null,\"serviceId\":null}");
    }
}
