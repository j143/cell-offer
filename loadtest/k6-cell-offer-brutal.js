import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const accepted = new Counter('cell_offers_accepted');
const rejected = new Counter('cell_offers_rejected');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const HOT_CELLS = Number(__ENV.HOT_CELLS || 10);
const COLD_CELLS = Number(__ENV.COLD_CELLS || 10000);
const HOT_TRAFFIC_SHARE = Number(__ENV.HOT_TRAFFIC_SHARE || 0.8);

export const options = {
  scenarios: {
    brutal_mixed_load: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RPS || 50),
      timeUnit: '1s',
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
  },
};

function pickCell(iteration) {
  const useHotCell = iteration % 100 < HOT_TRAFFIC_SHARE * 100;
  if (useHotCell) {
    return `hot-cell-${Math.floor(Math.random() * HOT_CELLS)}`;
  }
  return `cold-cell-${Math.floor(Math.random() * COLD_CELLS)}`;
}

export default function () {
  const iteration = __ITER;
  const priority = Math.random() < 0.1 ? 10 : Math.floor(Math.random() * 5);
  const driverId = Math.random() < 0.01
    ? `hot-driver-${Math.floor(Math.random() * 10)}`
    : `driver-${Math.floor(Math.random() * 100000)}`;

  const payload = JSON.stringify({
    offerId: `offer-${__VU}-${iteration}-${Date.now()}`,
    driverId,
    riderId: `rider-${Math.floor(Math.random() * 100000)}`,
    priority,
    ttlSeconds: 30,
  });

  const response = http.post(
    `${BASE_URL}/cells/${encodeURIComponent(pickCell(iteration))}/offers`,
    payload,
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'PostCellOffer' },
    },
  );

  const ok = check(response, {
    'status is accepted or intentionally rejected': (r) =>
      [200, 201, 202, 409, 422, 429].includes(r.status),
  });

  if (ok) {
    accepted.add(1);
  } else {
    rejected.add(1);
  }

  sleep(Math.random() * 0.05);
}
