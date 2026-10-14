-- polling interval per vehicle on one route/day (data_tm based)
WITH p AS (
  SELECT vehicle_id, to_timestamp(data_tm,'YYYYMMDDHH24MISS') t, collected_at
  FROM bus_position_raw
  WHERE route_id='100100437' AND collected_at >= '2026-10-08 07:00' AND collected_at < '2026-10-08 10:00'
), d AS (
  SELECT extract(epoch FROM t - lag(t) OVER (PARTITION BY vehicle_id ORDER BY t)) dt,
         extract(epoch FROM collected_at - lag(collected_at) OVER (PARTITION BY vehicle_id ORDER BY collected_at)) dc
  FROM p
)
SELECT count(*), percentile_cont(array[0.1,0.25,0.5,0.75,0.9]) WITHIN GROUP (ORDER BY dt) dt_q,
       percentile_cont(array[0.1,0.25,0.5,0.75,0.9]) WITHIN GROUP (ORDER BY dc) dc_q
FROM d WHERE dt IS NOT NULL;
SELECT min(collected_at), max(collected_at), count(DISTINCT route_id) FROM bus_position_raw WHERE collected_at >= '2026-10-01';
