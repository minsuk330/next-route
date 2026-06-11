create table bus_arrival_label_event
(
    id                        bigserial primary key,
    service_date              date         not null,
    route_id                  varchar      not null,
    vehicle_identity_type     varchar      not null,
    vehicle_identity          varchar      not null,
    trip_id                   varchar,
    stop_id                   varchar      not null,
    seq                       int          not null,
    section_id                varchar,
    api_estimated_arrival_at  timestamp,
    corrected_arrival_at      timestamp,
    label_arrival_at          timestamp,
    departed_at               timestamp,
    dwell_seconds             int,
    label_source              varchar      not null,
    label_confidence          varchar      not null,
    correction_source         varchar,
    correction_confidence     varchar,
    excluded_from_training    boolean      not null default false,
    exclude_reason            varchar,
    arrival_raw_id            bigint       not null,
    arrival_lifecycle_id      varchar      not null,
    position_raw_ids          jsonb,
    created_at                timestamp,
    updated_at                timestamp,
    deleted_at                timestamp
);

create unique index uk_bus_label_event_lifecycle
    on bus_arrival_label_event (arrival_lifecycle_id);

create index idx_bus_label_event_service_date
    on bus_arrival_label_event (service_date);

create index idx_bus_label_event_scope
    on bus_arrival_label_event (service_date, route_id, stop_id, seq);
