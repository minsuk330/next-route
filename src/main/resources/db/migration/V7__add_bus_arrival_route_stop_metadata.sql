alter table bus_arrival_raw
    add column route_abrv varchar(255),
    add column route_name varchar(255),
    add column stop_name varchar(255),
    add column first_bus_time varchar(255),
    add column last_bus_time varchar(255);
