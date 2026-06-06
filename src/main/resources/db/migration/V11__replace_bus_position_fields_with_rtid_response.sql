alter table bus_position_raw
    add column if not exists next_stop_time integer,
    add column if not exists route_distance double precision,
    add column if not exists last_stop_time integer,
    add column if not exists is_full_flag varchar(255),
    add column if not exists is_last_yn varchar(255),
    add column if not exists full_section_distance double precision,
    add column if not exists next_stop_id varchar(255),
    add column if not exists turn_stop_id varchar(255),
    add column if not exists gps_x double precision,
    add column if not exists gps_y double precision,
    add column if not exists is_run_yn varchar(255);

alter table bus_position_raw
    drop column if exists tm_x,
    drop column if exists tm_y,
    drop column if exists api_route_id;
