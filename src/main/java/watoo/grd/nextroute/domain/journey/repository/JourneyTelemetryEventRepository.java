package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneyTelemetryEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public interface JourneyTelemetryEventRepository extends JpaRepository<JourneyTelemetryEvent, Long> {

    /**
     * 계측 한 건 저장. <b>중복은 예외가 아니라 0행이다.</b>
     *
     * <p>{@code save()} 를 쓰지 않는 이유가 여기 있다. 유일성 위반이 예외로 올라오면 batch 하나가
     * 통째로 죽는데, 지하철에서 응답을 못 받은 앱이 같은 batch 를 다시 보내는 것은 <b>정상</b>
     * 동작이다. 그때마다 500 을 돌려주면 앱은 영원히 재시도한다.
     *
     * <p>중재자는 {@code (user_id, telemetry_id)} 다. 전역 유일로 두면 기기를 물려받아 계정이
     * 바뀔 때 앞선 사용자의 키가 새 사용자의 이벤트를 조용히 삼킨다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * @return 생성된 행 id(1건). 중복이면 0건
     */
    @Query(value = """
            INSERT INTO journey_telemetry_event
                (telemetry_id, user_id, journey_id, route_revision, event_type,
                 occurred_at, received_at, alert_id, alert_checkpoint, alert_sent_at, detail,
                 created_at, updated_at)
            VALUES (:telemetryId, :userId, :journeyId, :routeRevision, :eventType,
                    :occurredAt, :receivedAt, :alertId, :alertCheckpoint, :alertSentAt,
                    cast(:detail as jsonb), :auditNow, :auditNow)
            ON CONFLICT (user_id, telemetry_id) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    List<Long> insertIgnoringDuplicate(
            @Param("telemetryId") String telemetryId, @Param("userId") Long userId,
            @Param("journeyId") Long journeyId, @Param("routeRevision") Integer routeRevision,
            @Param("eventType") String eventType, @Param("occurredAt") Instant occurredAt,
            @Param("receivedAt") Instant receivedAt, @Param("alertId") Long alertId,
            @Param("alertCheckpoint") String alertCheckpoint,
            @Param("alertSentAt") Instant alertSentAt, @Param("detail") String detail,
            @Param("auditNow") LocalDateTime auditNow);

    /**
     * 탈퇴: 그 사용자의 계측 이벤트를 소프트 삭제한다(ADR 0001). user_id 로 직접 매달린다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update JourneyTelemetryEvent e set e.deletedAt = :now where e.userId = :userId and e.deletedAt is null")
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
