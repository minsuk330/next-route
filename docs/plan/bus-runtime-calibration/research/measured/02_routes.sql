-- per-route coverage of high-confidence (position-corrected) labels
SELECT l.route_id, r.route_name, r.route_type, r.term,
       count(*) FILTER (WHERE l.label_source='POSITION_STOP_FLAG_CORRECTED') pos_rows,
       count(*) FILTER (WHERE l.label_source='ARRIVAL_API_ETA') api_rows,
       count(DISTINCT l.trip_id) trips,
       count(DISTINCT l.service_date) days,
       (SELECT max(seq) FROM bus_route_stop s WHERE s.route_id=l.route_id) nstops
FROM bus_arrival_label_event l LEFT JOIN bus_route r ON r.route_id=l.route_id
GROUP BY 1,2,3,4 ORDER BY r.route_type, r.route_name;
