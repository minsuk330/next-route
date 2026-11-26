package watoo.grd.nextroute.application.route.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class BusRealtimeStopIdsTest {

    private final TagoCityCodeLookup tago = mock(TagoCityCodeLookup.class);
    private final BusRealtimeStopIds ids = new BusRealtimeStopIds(tago);

    @Test
    void realtimeIdAcceptsBothGtfsAndAlreadyStrippedIds() {
        given(tago.isRealtimePrefix("BSB")).willReturn(true);

        assertThat(ids.realtimeId("1:BS_TAGO_GGB233001674")).contains("233001674");
        assertThat(ids.realtimeId("1:BS_TAGO_SEB121000974")).contains("121000974");
        assertThat(ids.realtimeId("1:BS_TAGO_BSB167850202")).contains("BSB167850202");
        assertThat(ids.realtimeId("233001674")).contains("233001674");
        assertThat(ids.realtimeId(" 121000974 ")).contains("121000974");
        assertThat(ids.realtimeId("BSB167850202")).contains("BSB167850202");
    }

    @Test
    void realtimeIdRejectsUnknownShapesAndNonRealtimeTagoPrefixes() {
        assertThat(ids.realtimeId("CJB283000123")).isEmpty();
        assertThat(ids.realtimeId("1:BS_TAGO_CJB283000123")).isEmpty();
        assertThat(ids.realtimeId("ODSAY-A")).isEmpty();
        assertThat(ids.realtimeId("12345678")).isEmpty();
        assertThat(ids.realtimeId("1:BS_ACC3_11000011")).isEmpty();
        assertThat(ids.realtimeId("")).isEmpty();
        assertThat(ids.realtimeId(null)).isEmpty();
    }
}
