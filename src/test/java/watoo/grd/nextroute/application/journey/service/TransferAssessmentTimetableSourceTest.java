package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** 지방 지하철 TIMETABLE 이 환승 판정 저장(transfer_connection.source varchar(20))에서 NONE 으로 뭉개지지 않는다. */
class TransferAssessmentTimetableSourceTest {

    @Test
    @DisplayName("TIMETABLE → \"TIMETABLE\" 저장 → 다시 TIMETABLE 로 읽힌다")
    void roundTrip() throws Exception {
        Method writerSourceOf = TransferAssessmentWriter.class.getDeclaredMethod("sourceOf", CurrentLegArrival.Source.class);
        writerSourceOf.setAccessible(true);
        String stored = (String) writerSourceOf.invoke(null, CurrentLegArrival.Source.TIMETABLE);

        assertThat(stored).isEqualTo("TIMETABLE").hasSizeLessThanOrEqualTo(20);
        assertThat(TransferAssessmentMapper.sourceOf(stored)).isEqualTo(CurrentLegArrival.Source.TIMETABLE);
        assertThat(writerSourceOf.invoke(null, CurrentLegArrival.Source.REALTIME_ARRIVAL)).isEqualTo("REALTIME");
    }
}
