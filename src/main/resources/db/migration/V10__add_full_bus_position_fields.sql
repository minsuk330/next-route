alter table bus_position_raw
    add column if not exists tm_x double precision,
    add column if not exists tm_y double precision,
    add column if not exists section_distance double precision,
    add column if not exists section_id varchar(255),
    add column if not exists pos_x double precision,
    add column if not exists pos_y double precision,
    add column if not exists api_route_id varchar(255),
    add column if not exists congestion integer;

alter table bus_position_raw
    drop column if exists latitude,
    drop column if exists longitude,
    drop column if exists stop_seq,
    drop column if exists section_speed,
    drop column if exists is_running;
