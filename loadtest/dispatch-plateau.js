import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const admitted = new Counter('offers_http_admitted');
const rejected = new Counter('offers_http_rejected');
const unexpected = new Counter('offers_http_unexpected');
const base = __ENV.BASE_URL || 'http://localhost:8080';
const rate = Number(__ENV.RPS || 500);
const cells = Number(__ENV.CELLS || 100);
if (!Number.isInteger(rate) || rate < 1 || !Number.isInteger(cells) || cells < 1) {
  throw new Error('RPS and CELLS must be positive integers');
}
export const options = {
  scenarios: {
    plateau: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: Number(__ENV.VUS || 500),
      maxVUs: Number(__ENV.MAX_VUS || 2000),
    },
  },
  thresholds: {
    checks: ['rate>0.999'],
    http_req_duration: ['p(95)<100', 'p(99)<250'],
    dropped_iterations: ['count==0'],
  },
};
export default function () {
  const cell = Math.floor(Math.random() * cells);
  const response = http.post(`${base}/cells/cell-${cell}/offers`, JSON.stringify({
    driverId: `driver-${__VU}-${__ITER}`,
    riderId: 'rider-test', priority: __ITER % 10, ttlMillis: 30000,
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'enqueue_offer' } });
  if (response.status === 202) admitted.add(1);
  else if (response.status === 429) rejected.add(1);
  else unexpected.add(1);
  check(response, { 'explicit admission outcome': r => r.status === 202 || r.status === 429 });
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY || 'dispatch-summary.json']: JSON.stringify(data, null, 2) };
}
