-- Independent check (no stop_flag/label logic): per vehicle, first data_tm at which section_order >= A and >= B
-- on one weekday for route 771 (100100437). Trips split when section_order drops by >=5.
WITH p AS (
  SELECT vehicle_id, to_timestamp(data_tm,'YYYYMMDDHH24MISS')::timestamp t, section_order so
  FROM bus_position_raw
  WHERE route_id = :'rid' AND collected_at >= :'d0' AND collected_at < :'d1' AND is_run_yn='1'
), q AS (
  SELECT *, sum(CASE WHEN lag_so - so >= 5 THEN 1 ELSE 0 END) OVER (PARTITION BY vehicle_id ORDER BY t) trip
  FROM (SELECT *, lag(so) OVER (PARTITION BY vehicle_id ORDER BY t) lag_so FROM p) x
), r AS (
  SELECT vehicle_id, trip, min(t) FILTER (WHERE so >= :A) ta, min(t) FILTER (WHERE so >= :B) tb,
         min(so) FILTER (WHERE so >= :A) soa, min(so) FILTER (WHERE so >= :B) sob
  FROM q GROUP BY 1,2
)
SELECT to_char(ta,'HH24') hh, count(*) n,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM tb-ta))::numeric/60,1) med_min,
       round(percentile_cont(0.25) WITHIN GROUP (ORDER BY extract(epoch FROM tb-ta))::numeric/60,1) p25,
       round(percentile_cont(0.75) WITHIN GROUP (ORDER BY extract(epoch FROM tb-ta))::numeric/60,1) p75
FROM r WHERE ta IS NOT NULL AND tb IS NOT NULL AND tb > ta AND soa <= :A + 1 AND sob <= :B + 1
GROUP BY 1 ORDER BY 1;
