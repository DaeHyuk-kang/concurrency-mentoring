import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8099';
const MODE = __ENV.MODE || 'buggy'; // buggy | fixed
const SEAT_COUNT = 8;

export const reserveSuccess = new Counter('reserve_success');
export const reserveFail = new Counter('reserve_fail');

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations',
      vus: 80,
      iterations: 1,
      maxDuration: '20s',
    },
  },
};

export function setup() {
  http.post(`${BASE}/api/reset`);
}

export default function () {
  const seatId = ((__VU - 1) % SEAT_COUNT) + 1; // 좌석 8개에 VU를 고르게 분산
  const user = `vu${__VU}`;
  const payload = JSON.stringify({ seatId, user, mode: MODE });
  const res = http.post(`${BASE}/api/reserve`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, { 'HTTP 200': (r) => r.status === 200 });

  const body = JSON.parse(res.body);
  if (body.success) {
    reserveSuccess.add(1);
  } else {
    reserveFail.add(1);
  }
}

export function teardown() {
  const seats = JSON.parse(http.get(`${BASE}/api/seats`).body);
  const occupied = seats.filter((s) => s.status === 'OCCUPIED').length;
  console.log(`[${MODE}] 실제 점유된 좌석 수(중복 제외): ${occupied} / 좌석 총 ${SEAT_COUNT}개`);
}
