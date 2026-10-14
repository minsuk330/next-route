SELECT min(service_date), max(service_date), count(*), count(distinct route_id) routes, count(distinct service_date) days FROM bus_arrival_label_event;
SELECT label_source, label_confidence, excluded_from_training, exclude_reason, count(*) FROM bus_arrival_label_event GROUP BY 1,2,3,4 ORDER BY 5 DESC;
SELECT vehicle_identity_type, count(*), count(trip_id) with_trip FROM bus_arrival_label_event GROUP BY 1;
