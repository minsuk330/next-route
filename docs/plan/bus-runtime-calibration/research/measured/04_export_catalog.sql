\copy (SELECT route_id, route_name, route_type, term, start_station, end_station, total_distance FROM bus_route) TO STDOUT WITH CSV HEADER
