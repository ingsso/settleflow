// 시나리오 ①-b — 관문 처리율 (W4D4 에서 추가)
//
// ★ 왜 ①(webhook-duplicate-burst.js)로는 이걸 못 재는가
//
// ①은 VU 1,000개가 각각 1회씩 쏜다. 그러면 <b>VU 1개당 커넥션 1개</b>라 재사용이
// 원천적으로 불가능하고, W4D4 실측에서 서버가 아니라 <b>부하 발생기가 먼저 꺾였다</b>:
//
//   [실측 2026-08-08] 1,000 VU × 2회
//     서버 도달 965 / 995  — 전부 200, 앱 예외 0
//     dial 실패  35 /   5  — dial tcp: connectex: The system detected an invalid pointer address
//
// connection refused(톰캣 backlog 넘침)도 cannot assign requested address(포트 고갈)도
// 아니었다 — Windows 소켓 계층이다. 그리고 <b>35 → 5 로 재현되지 않는다.</b> 서버 임계라면
// 같은 자리에서 같은 수가 나와야 한다. 그러므로 그 숫자는 서버에 대해 아무 말도 안 한다.
//
// ★ W4D4(8/9) 정정 — 결론은 유지되고 <b>근거가 바뀌었다</b>
//
//   [실측 2026-08-09] ①을 1,000 VU 로 2회
//     1회차(앱 기동 직후)  서버 도달 919 / 실패 81 = connection refused, p95 570ms, 883/s
//     2회차(가열 후)       서버 도달 1,000 / 실패  0,                  p95 132ms, 3,011/s
//
// 이번엔 <b>connection refused 가 맞았다</b>(accept 큐 넘침). 그런데 2회차에 0건이고, 원인이
// 지연에 있다 — 미가열이라 요청이 4배 느리게 빠지는 동안 커넥션이 큐에 쌓인 것이다.
// 즉 톰캣 backlog 는 맞지만 <b>정상 상태의 서버 용량이 아니라 가열 상태의 함수</b>다.
// "서버 임계가 아니다"는 유지되지만, 이유는 "소켓 계층이라서"가 아니라 "미가열이라서"다.
//
// ★ 그리고 아래 teardown 의 판별 기준이 틀렸었다
//
//   [실측 2026-08-09] 사다리 (달성 req/s, dropped, VU 최대)
//     1,000/VU300 →   999,      0,  11      3,000/VU300 → 2,994,      0,  29
//     6,000/VU300 → 5,630,  7,336, 288      6,000/VU800 → 5,852,  2,885, 679
//     9,000/VU800 → 6,145, 56,612, 800   ← 천장 ≈ 6,100 req/s
//
// VU 상한을 2.7배 풀었더니 dropped 는 7,336 → 2,885 로 줄었는데 <b>처리량은 안 늘었다</b>
// (5,630 → 5,852). 목표를 1.5배 올려도 +9%. 그러면 그 dropped 는 클라이언트 한계가 아니라
// <b>서버 포화의 파생 결과</b>다 — 서버가 느려져 VU 가 더 필요해진 것이다.
// <b>소켓 압력을 고정하려고 VU 를 묶은 이 장치가, 서버 신호를 클라이언트 신호로 보이게 했다.</b>
//
// 435,150건 전부 200, 5xx 0, 앱 예외 0 — 포화가 <b>에러가 아니라 지연</b>으로 나타났다
// (W4D3 §4 가 Redis 에 대해 한 예측이 관문 전체 수준에서 실현됐다).
//
// ★ 그래서 부하의 모양을 바꾼다
//
// VU 수를 묶어 커넥션을 재사용시키고, 대신 <b>초당 요청 수</b>를 올린다. 소켓 수가
// 상한에 묶이므로 클라이언트가 안 꺾이고, 서버를 실제로 밀 수 있다.
//
// ⚠️ 부하의 뜻이 바뀐다. ①은 "동시 N개"였고 이건 "지속 N req/s"다.
//    둘은 대체재가 아니다 — ①은 계획서 체크포인트에 대응하고, 이쪽은
//    "관문이 초당 몇 건까지 관문인가"에 답한다.
//
// ★ 재는 것은 <b>거절 경로</b>다
//
//   Boolean firstTime = redis.setIfAbsent(key, "1", TTL);   // ① 관문
//   if (!Boolean.TRUE.equals(firstTime)) return ok();       // ← 이 줄까지가 측정 대상
//
// 1,000건 폭주에서도 관문이 실제로 하는 일의 999/1000 이 이 경로다. 그래서 <b>이미 키가
// 있는 eventId</b>를 쓴다 — 그러면 100% 가 거절 경로를 탄다.
//
// 부수 효과로 <b>PG 를 한 번도 안 부른다</b>: setup 주문이 없으므로 pg-approve 창이
// 안 더러워지고, 단계를 몇 번 돌려도 회로에 흔적이 안 남는다(W4D3 §10① 이 물린 자리).
//
// 대신 이 수치는 <b>결제 처리량이 아니다.</b> 관문의 거절 경로 처리율이다.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// 이미 Redis 에 키가 있는 eventId 여야 한다. 없으면 첫 요청만 통과 경로를 타고
// 나머지는 거절 경로다 — 비율이 무시할 만하지만, 정확히 하려면 기존 키를 준다.
const EVENT_ID = __ENV.EVENT_ID;
const MPID = __ENV.MPID;
const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '20s';
// VU 상한이 이 실험의 핵심이다. 요청 수가 아니라 <b>커넥션 수</b>를 묶는 장치다.
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

const nonOk = new Counter('webhook_non_200');

if (!EVENT_ID || !MPID) {
  throw new Error('EVENT_ID 와 MPID 를 env 로 줄 것 (이미 Redis 에 키가 있는 eventId)');
}

export const options = {
  scenarios: {
    gate: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // preAllocated 를 상한과 같게 둔다 — 실행 중 VU 를 늘리면 그 순간 커넥션이
      // 새로 열려 측정 중에 소켓 압력이 변한다.
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
    },
  },
  // 임계값을 두지 않는다. <b>꺾이는 점을 찾는 게 목적</b>이라, 꺾인 실행을 "실패"로
  // 보고하면 그 실행이 바로 우리가 원한 데이터라는 걸 가린다.
  thresholds: {},
};

export default function () {
  const payload = JSON.stringify({
    eventId: EVENT_ID,       // ★ 고정. 모든 요청이 SETNX 에서 거절된다.
    eventType: 'APPROVED',
    merchantPaymentId: MPID,
    pgTransactionId: `pgtx-k6-${MPID}`,
    amount: 10222,
  });

  const res = http.post(`${BASE}/webhooks/pg`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'webhook' },
  });

  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  if (!ok) nonOk.add(1);
}

export function teardown() {
  console.log('');
  console.log('=== 꺾였는지 판별 ===');
  console.log('  ⚠️ dropped_iterations 하나로는 못 가른다 (W4D4 실측으로 뒤집힘 — 아래 주석)');
  console.log('  판별법: MAX_VUS 를 올려 같은 RATE 로 재실행한다');
  console.log('    → 달성 처리량이 따라 오르면  클라이언트 한계(VU 부족). 서버 값 아님');
  console.log('    → 처리량이 안 오르면        서버 포화. 이게 값이다');
  console.log('  dial 계열 에러 → 앱 가열 상태부터 볼 것. 미가열이면 accept 큐 넘침이라 서버 용량이 아니다');
  console.log('  대조: curl -s :8080/actuator/prometheus | grep http_server_requests_seconds_count');
  console.log('        (서버가 센 수. k6 자기 보고와 어긋나면 서버 쪽을 믿는다 — W4D4 §3)');
}
