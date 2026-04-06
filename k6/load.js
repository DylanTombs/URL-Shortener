import http from 'k6/http';
import { check, sleep } from 'k6';

// Override via: k6 run -e BASE_URL=https://your-alb -e KNOWN_CODE=aB3xK9mQ load.js
const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  stages: [
    { duration: '2m', target: 50 },  // ramp up to 50 VUs
    { duration: '8m', target: 50 },  // hold steady state — p99 must stay < 500ms here
    { duration: '1m', target: 0  },  // ramp down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.01'],           // < 1% errors
    http_req_duration: ['p(95)<300', 'p(99)<500'], // p95 < 300ms, p99 < 500ms
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, { 'status is 301': (r) => r.status === 301 });
  sleep(0.2);
}
