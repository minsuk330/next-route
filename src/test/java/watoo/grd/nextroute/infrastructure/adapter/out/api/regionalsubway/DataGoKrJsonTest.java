package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalsubway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataGoKrJsonTest {

    @Test
    @DisplayName("예외 메시지의 요청 URL 에서 serviceKey 를 가린다 — 로그로 키가 새지 않는다")
    void redactsServiceKey() {
        String msg = "I/O error on GET request for \"https://apis.data.go.kr/B551542/trainTime/getTrainTime?serviceKey=AbC%2Fkey%3D%3D&act=json&scode=119\": Read timed out";
        assertThat(DataGoKrJson.redact(msg))
                .doesNotContain("AbC%2Fkey")
                .contains("serviceKey=***&act=json");
    }
}
