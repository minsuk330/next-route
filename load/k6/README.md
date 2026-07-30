# VPS public read load test

> The general mixed workload below is a smoke/baseline tool. It is not used as
> evidence for the current portfolio performance claims.

This workload sends real HTTPS requests from the local machine to the VPS API.
It excludes route search, notification writes, and fault injection so it does
not consume external provider quotas or create production records.

Request mix:

- 40% nearby bus stops
- 30% search suggestions
- 30% subway arrivals

Run the approved sequence:

```bash
./load/k6/run-read-api.sh 1 1m smoke
./load/k6/run-read-api.sh 10 10m baseline
./load/k6/run-read-api.sh 30 10m step-30
./load/k6/run-read-api.sh 60 10m step-60
```

The runner uses the pinned `grafana/k6:2.1.0` Docker image and writes console,
summary, and metadata files under `load/k6/results/`.

Override the target only when intentionally testing another environment:

```bash
BASE_URL=https://staging.example.com ./load/k6/run-read-api.sh 1 1m staging-smoke
```

Stop after any failed threshold or if VPS health, error rate, CPU, memory, or
database connection pressure becomes abnormal.

## Portfolio replacement measurements

These two scenarios target the portfolio claims directly. Both use one real
public bus-arrival key on the VPS:

- `stampede`: 100 simultaneous requests for the same key
- `ttl`: about 80 requests over 20 seconds at 250ms intervals

```bash
./load/k6/run-portfolio-bus-cache.sh stampede
./load/k6/run-portfolio-bus-cache.sh ttl
```

Compare each run with the API Prometheus counters:

- `singleflight_requests_total{provider="bus",type="arrival",role=...}`
- `bus_realtime_cache_requests_total{type="arrival",result=...}`

The target key defaults to Seoul Station West / bus 262 and can be overridden
with `STOP_ID` and `ROUTE_ID`.
