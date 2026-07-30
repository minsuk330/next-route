import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'https://api.refit-100.site').replace(/\/+$/, '');
const MODE = __ENV.MODE || 'stampede';
const RUN_ID = __ENV.RUN_ID || 'manual';
const STOP_ID = __ENV.STOP_ID || '101000013';
const ROUTE_ID = __ENV.ROUTE_ID || '100100044';

const scenarios = {
  stampede: {
    portfolio_stampede: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '30s',
      exec: 'requestArrival',
      tags: {
        run_id: RUN_ID,
        workload: 'portfolio-stampede',
      },
    },
  },
  ttl: {
    portfolio_ttl: {
      executor: 'constant-arrival-rate',
      rate: 4,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 4,
      maxVUs: 10,
      gracefulStop: '5s',
      exec: 'requestArrival',
      tags: {
        run_id: RUN_ID,
        workload: 'portfolio-ttl',
      },
    },
  },
};

if (!scenarios[MODE]) {
  throw new Error(`MODE must be stampede or ttl: ${MODE}`);
}

export const options = {
  scenarios: scenarios[MODE],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    'http_req_duration{endpoint:bus-arrival}': ['p(95)<2000', 'p(99)<3000'],
    dropped_iterations: ['count==0'],
  },
  userAgent: `nextroute-k6/${RUN_ID}`,
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${BASE_URL}/api/health`, {
    tags: { endpoint: 'setup-health' },
    timeout: '5s',
  });

  if (response.status !== 200) {
    fail(`health check failed: status=${response.status}`);
  }
}

export function requestArrival() {
  const response = http.get(
    `${BASE_URL}/api/arrivals/bus/${STOP_ID}/${ROUTE_ID}`,
    {
      tags: { endpoint: 'bus-arrival' },
      timeout: '5s',
    },
  );

  check(response, {
    'bus-arrival: status is 200': (r) => r.status === 200,
    'bus-arrival: non-empty JSON array': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body) && body.length > 0 && body[0].routeId === ROUTE_ID;
      } catch (_) {
        return false;
      }
    },
  });
}
