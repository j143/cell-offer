import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const accepted = new Counter('cell_offers_accepted');
const rejected = new Counter('cell_offers_rejected');
const unexpected = new Counter('cell_offers_unexpected');
const rejectionRate = new Rate('cell_offer_admission_rejection_rate');
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const HOT_CELLS = Number(__ENV.HOT_CELLS || 10);
const COLD_CELLS = Number(__ENV.COLD_CELLS || 10000);
const HOT_TRAFFIC_SHARE = Number(__ENV.HOT_TRAFFIC_SHARE || 0.8);
const TTL_MS = Number(__ENV.TTL_MS || 30000);
const MAX_REJECTION_RATE = Number(__ENV.MAX_REJECTION_RATE || 0.01);
if (![HOT_CELLS, COLD_CELLS, TTL_MS].every(v => Number.isInteger(v) && v > 0)
    || !Number.isFinite(HOT_TRAFFIC_SHARE) || HOT_TRAFFIC_SHARE < 0 || HOT_TRAFFIC_SHARE > 1
    || !Number.isFinite(MAX_REJECTION_RATE) || MAX_REJECTION_RATE < 0 || MAX_REJECTION_RATE > 1) {
  throw new Error('Invalid cell counts, TTL, traffic share or rejection budget');
}

// An intentional 429 is not a transport/server fault, but has its own SLO below.
http.setResponseCallback(http.expectedStatuses(202, 429));
export const options = {
  scenarios: {
    brutal_mixed_load: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RPS || 50), timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 500),
      maxVUs: Number(__ENV.MAX_VUS || 5000),
      stages: [
        { target: Number(__ENV.RAMP_RPS || 500), duration: __ENV.RAMP_DURATION || '2m' },
        { target: Number(__ENV.PEAK_RPS || 2000), duration: __ENV.PEAK_DURATION || '5m' },
        { target: Number(__ENV.SOAK_RPS || 500), duration: __ENV.SOAK_DURATION || '5m' },
        { target: 0, duration: '2m' },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100', 'p(99)<250'],
    http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count<1000'],
    checks: ['rate>0.999'],
    cell_offer_admission_rejection_rate: [`rate<=${MAX_REJECTION_RATE}`],
  },
};

export default function () {
  const hot = Math.random() < HOT_TRAFFIC_SHARE;
  const cell = hot ? `hot-cell-${Math.floor(Math.random() * HOT_CELLS)}`
    : `cold-cell-${Math.floor(Math.random() * COLD_CELLS)}`;
  const driverId = Math.random() < 0.01
    ? `hot-driver-${Math.floor(Math.random() * 10)}`
    : `driver-${Math.floor(Math.random() * 100000)}`;
  const response = http.post(`${BASE_URL}/cells/${encodeURIComponent(cell)}/offers`,
    JSON.stringify({ driverId, riderId: `rider-${Math.floor(Math.random() * 100000)}`,
      priority: Math.random() < 0.1 ? 10 : Math.floor(Math.random() * 5), ttlMillis: TTL_MS }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'PostCellOffer' } });
  if (response.status === 202) accepted.add(1);
  else if (response.status === 429) rejected.add(1);
  else unexpected.add(1);
  rejectionRate.add(response.status === 429);
  check(response, { 'explicit admission outcome': r => r.status === 202 || r.status === 429 });
}
