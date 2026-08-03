-- Phase 1·2를 GPS 기반 Journey 계약에 맞춘다.
-- 정밀 좌표는 저장하지 않고, 지하철 GPS 공백 보호와 이후 Phase 3 progress 요약에 필요한 값만 둔다.

ALTER TABLE journey_session
    ADD COLUMN subway_offline_protection_until timestamptz;

ALTER TABLE journey_leg
    ADD COLUMN route_id                        varchar(64),
    ADD COLUMN progress_geometry_segment_index integer
        check (progress_geometry_segment_index is null or progress_geometry_segment_index >= 0),
    ADD COLUMN progress_fraction               numeric(7, 6)
        check (progress_fraction is null or progress_fraction between 0 and 1),
    ADD COLUMN progress_leg_fraction           numeric(7, 6)
        check (progress_leg_fraction is null or progress_leg_fraction between 0 and 1),
    ADD COLUMN progress_observed_at             timestamptz,
    ADD COLUMN progress_section_id              varchar(64),
    ADD COLUMN remaining_distance_meters        integer
        check (remaining_distance_meters is null or remaining_distance_meters >= 0),
    ADD COLUMN remaining_duration_seconds       integer
        check (remaining_duration_seconds is null or remaining_duration_seconds >= 0),
    ADD COLUMN activated_at                     timestamptz,
    ADD COLUMN completed_at                     timestamptz;

UPDATE journey_leg l
SET activated_at = s.started_at
FROM journey_session s
WHERE l.journey_id = s.id AND l.state = 'ACTIVE' AND l.activated_at IS NULL;

ALTER TABLE journey_leg_route_option
    ADD COLUMN direction_key varchar(64);

-- 새 Journey 교체 종료 이유의 어휘를 JourneyRevisionStatus와 맞춘다.
ALTER TABLE journey_session DROP CONSTRAINT journey_end_reason_check;
UPDATE journey_session SET end_reason = 'SUPERSEDED' WHERE end_reason = 'REPLACED';
ALTER TABLE journey_session
    ADD CONSTRAINT journey_end_reason_check
        check (end_reason is null or end_reason in
               ('COMPLETED', 'USER_CANCELED', 'SUPERSEDED',
                'CLIENT_LEASE_EXPIRED', 'ADMIN_CANCELED'));

CREATE INDEX idx_journey_expiration
    ON journey_session (guidance_lease_until, subway_offline_protection_until)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
