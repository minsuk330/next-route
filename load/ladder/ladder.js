// 부하 사다리(#415-4): 이동 중 사용자 + 조회 부하.
// riders — 여정 하나에 VU 하나. 앱처럼 10초마다 여정을 조회하고(바뀐 것 없으면 204), 약 100초 뒤 한 번 단계 전이.
// reads  — 정해진 RPS 로 주변 정류장 조회(경로 검색은 OTP 단일 엔진이라 제외, 읽기 부하로 대신).
import http from 'k6/http';
import { sleep, check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const RIDERS = new SharedArray('riders', () => JSON.parse(open(__ENV.TOKENS || '/work/tokens.json')));
const N = parseInt(__ENV.RIDERS || String(RIDERS.length), 10);
const DURATION = __ENV.DURATION || '10m';
const READ_RPS = parseInt(__ENV.READ_RPS || '20', 10);
const POLL_S = parseFloat(__ENV.POLL_S || '10');

export const options = {
  scenarios: {
    riders: { executor: 'constant-vus', vus: N, duration: DURATION, exec: 'ride', gracefulStop: '15s' },
    reads: {
      executor: 'constant-arrival-rate', rate: READ_RPS, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(10, READ_RPS), maxVUs: READ_RPS * 4, exec: 'read',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{endpoint:journey}': ['p(95)<1000'],
    'http_req_duration{endpoint:nearby}': ['p(95)<1000'],
  },
};

const state = {};

export function ride() {
  const me = RIDERS[(exec.vu.idInTest - 1) % RIDERS.length];
  const s = state[me.journeyId] || (state[me.journeyId] = { ev: null, iter: 0, moved: false });
  // 시작을 흩뜨린다(모두 같은 순간에 조회하면 실제보다 뾰족하다)
  if (s.iter === 0) sleep(Math.random() * POLL_S);
  const params = { headers: { Authorization: `Bearer ${me.token}` }, tags: { endpoint: 'journey' } };
  const q = s.ev === null ? '' : `?afterEvaluationVersion=${s.ev}&afterCommandVersion=${s.cv}`;
  const res = http.get(`${BASE}/api/journeys/${me.journeyId}${q}`, params);
  check(res, { 'journey 200/204': (r) => r.status === 200 || r.status === 204 });
  if (res.status === 200) {
    const body = res.json();
    s.ev = body.evaluationVersion;
    s.cv = body.commandVersion;
    s.rev = body.routeRevision;
  }
  s.iter++;
  if (!s.moved && s.iter === 10 && s.cv) {
    const ev = {
      eventId: crypto.randomUUID(), type: 'CLIENT_LEG_TRANSITION_DETECTED',
      baseCommandVersion: s.cv, routeRevision: s.rev || 1, fromLegIndex: 1, toLegIndex: 2,
      clientOccurredAt: new Date().toISOString(),
    };
    http.post(`${BASE}/api/journeys/${me.journeyId}/events`, JSON.stringify(ev),
      { headers: { ...params.headers, 'Content-Type': 'application/json' }, tags: { endpoint: 'event' } });
    s.moved = true;
  }
  sleep(POLL_S);
}

const SPOTS = [[37.5547, 126.9706], [37.5663, 126.9779], [37.4979, 127.0276], [37.5572, 126.9254], [37.5133, 127.1002]];

export function read() {
  const [lat, lng] = SPOTS[Math.floor(Math.random() * SPOTS.length)];
  const res = http.get(`${BASE}/api/nearby/bus-stops?lat=${lat}&lng=${lng}`, { tags: { endpoint: 'nearby' } });
  check(res, { 'nearby 200': (r) => r.status === 200 });
}
