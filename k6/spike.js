import http from 'k6/http';
import { check, sleep } from 'k6';

// Override via: k6 run -e BASE_URL=https://your-alb -e KNOWN_CODE=aB3xK9mQ spike.js
const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const KNOWN_CODE = __ENV.KNOWN_CODE || 'aB3xK9mQ';

export const options = {
  stages: [
    { duration: '30s', target: 10  },  // baseline
    { duration: '30s', target: 500 },  // spike to 500 VUs
    { duration: '60s', target: 500 },  // hold spike
    { duration: '30s', target: 10  },  // recover
    { duration: '30s', target: 0   },  // ramp down
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],   // allow up to 5% errors during spike
    http_req_duration: ['p(99)<2000'],  // p99 < 2s during spike
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${KNOWN_CODE}`, { redirects: 0 });
  check(res, { 'not 5xx': (r) => r.status < 500 });
  sleep(0.1);
}
