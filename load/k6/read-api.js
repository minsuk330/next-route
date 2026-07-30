import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'https://api.refit-100.site').replace(/\/+$/, '');
const RATE = parsePositiveInteger(__ENV.RATE || '1', 'RATE');
const DURATION = __ENV.DURATION || '1m';
const RUN_ID = __ENV.RUN_ID || 'manual';

const LOCATIONS = [
  { name: 'seoul-station', lat: 37.5547, lng: 126.9706 },
  { name: 'city-hall', lat: 37.5663, lng: 126.9779 },
  { name: 'gangnam', lat: 37.4979, lng: 127.0276 },
  { name: 'hongdae', lat: 37.5572, lng: 126.9254 },
  { name: 'jamsil', lat: 37.5133, lng: 127.1002 },
];

const KEYWORDS = ['14', '143', '272', '서울', '강남'];

export const options = {
  scenarios: {
    public_read_mix: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(20, RATE * 2),
      gracefulStop: '30s',
      exec: 'publicReadMix',
      tags: {
        run_id: RUN_ID,
        workload: 'public-read-mix',
      },
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
    'http_req_duration{endpoint:nearby-bus-stops}': ['p(95)<1500'],
    'http_req_duration{endpoint:search-suggest}': ['p(95)<1500'],
    'http_req_duration{endpoint:subway-arrivals}': ['p(95)<1500'],
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

  return { startedAt: new Date().toISOString() };
}

export function publicReadMix() {
  const iteration = exec.scenario.iterationInTest;
  const location = LOCATIONS[iteration % LOCATIONS.length];
  const bucket = iteration % 10;

  if (bucket < 4) {
    requestJson(
      `${BASE_URL}/api/nearby/bus-stops?lat=${location.lat}&lng=${location.lng}&limit=20`,
      'nearby-bus-stops',
    );
    return;
  }

  if (bucket < 7) {
    const keyword = encodeURIComponent(KEYWORDS[iteration % KEYWORDS.length]);
    requestJson(
      `${BASE_URL}/api/search/suggest?keyword=${keyword}`,
      'search-suggest',
    );
    return;
  }

  requestJson(
    `${BASE_URL}/api/arrivals/subway?lat=${location.lat}&lon=${location.lng}`,
    'subway-arrivals',
  );
}

function requestJson(url, endpoint) {
  const response = http.get(url, {
    tags: { endpoint },
    timeout: '5s',
  });

  check(response, {
    [`${endpoint}: status is 200`]: (r) => r.status === 200,
    [`${endpoint}: response is JSON`]: (r) =>
      String(r.headers['Content-Type'] || '').includes('application/json'),
  });
}

function parsePositiveInteger(raw, name) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer: ${raw}`);
  }
  return value;
}
