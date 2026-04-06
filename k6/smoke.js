import http from 'k6/http';
import { check, sleep } from 'k6';

// Override via: k6 run -e BASE_URL=https://your-alb -e KNOWN_CODE=aB3xK9mQ smoke.js
const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  vus:      5,
  duration: '1m',
  thresholds: {
    http_req_failed:   ['rate<0.01'],  // < 1% errors
    http_req_duration: ['p(99)<500'],  // p99 < 500ms
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, {
    'status is 301':           (r) => r.status === 301,
    'Location header present': (r) => r.headers['Location'] !== undefined,
  });
  sleep(0.5);
}
