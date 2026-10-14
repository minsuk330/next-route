-- Seoul API section speed (sectSpd, km/h) based implied time for a seq range (sanity check vs GTFS/measured)
SELECT route_id, min(seq), max(seq), sum(section_distance) dist_m,
       round((sum(section_distance / nullif(section_speed,0)) * 60 / 1000)::numeric, 1) implied_min_driving,
       round(avg(section_speed)::numeric,1) avg_spd, count(*) FILTER (WHERE section_speed IS NULL OR section_speed=0) zero_spd
FROM bus_route_stop WHERE route_id = '100100409' AND seq BETWEEN 13 AND 30 GROUP BY 1
UNION ALL
SELECT route_id, min(seq), max(seq), sum(section_distance), round((sum(section_distance / nullif(section_speed,0)) * 60 / 1000)::numeric, 1),
       round(avg(section_speed)::numeric,1), count(*) FILTER (WHERE section_speed IS NULL OR section_speed=0)
FROM bus_route_stop WHERE route_id = '100100437' AND seq BETWEEN 11 AND 40 GROUP BY 1;
SELECT max(updated_at) FROM bus_route_stop WHERE route_id IN ('100100409','100100437');
