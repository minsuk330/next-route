package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * bus-boarding-flow S1: 띠 이름은 도착 조회의 트랜잭션 <b>밖에서</b> 읽는다. 안에서 읽다 실패하면 그 트랜잭션이 rollback-only 가
 * 되어, 예외를 삼켜도 커밋에서 도착 응답이 500 이 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
class BusApproachStopNamesTransactionIT {

    @Autowired BusApproachStopNames names;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean BusRouteStopRepository routeStops;

    @Test
    void readsOutsideTheCallersTransaction_andAFailureLeavesItCommittable() {
        AtomicReference<Boolean> inTransaction = new AtomicReference<>();
        given(routeStops.findByRouteIdOrderBySeq(anyString())).willAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("db down");
        });
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.setReadOnly(true);

        List<String> result = caller.execute(status -> names.seoul("tx-it-route", 10, 2, null));

        assertThat(result).isNull();
        // 계약은 이것 하나다: 표를 읽는 순간 부른 쪽 트랜잭션이 걸려 있지 않다(NOT_SUPPORTED 를 떼면 true 가 된다).
        assertThat(inTransaction.get()).isFalse();
    }
}
