// 시나리오 ② — 부하 중 강제 종료로 고아 멱등키를 만든다
//
// 겨누는 명제: WebhookController javadoc 이 인정한 구멍의 <b>크기</b>.
//
//   Boolean firstTime = redis.setIfAbsent(key, ...);   // ① 관문 — 커밋됨
//   if (!TRUE) return ok();
//   try { dispatch(req); }                             // ② DB 전이 — 여기서 프로세스가 죽으면
//   catch (RuntimeException e) { redis.delete(key); }  // ③ 이 줄이 <b>실행 자체가 안 된다</b>
//
// 결과: 키는 남고 전이는 없다. PG 가 재전송하면 ①에 막혀 200 을 받고 <b>재전송을 멈춘다</b>.
// 그 뒤로는 어디에도 에러가 안 난다 — 조용한 유실이다.
// 회수는 TTL(24h) 만료 + 미결제 쓸이 배치(W1D3)로 넘어간다.
//
// ★ Redis 를 내리는 것으로는 이 구멍에 도달하지 못한다. ①이 맨 앞이라 먼저 막히고,
//   그러면 키가 애초에 안 생겨 <b>안전한 쪽으로 닫힌다</b>. 열려면 ①과 ② 사이에서
//   프로세스가 사라져야 하고, 그게 강제 종료다.
//
// ⚠️ 반드시 SIGKILL 이어야 한다. graceful shutdown 이면 in-flight 가 끝날 때까지
//   기다려주므로 ③이 정상적으로 돌고 구멍이 안 열린다.

// ★ 타임아웃을 30% 로 섞는 이유 (W4D3 실측으로 드러남)
//
// 처음엔 모든 주문을 타임아웃 금액으로 냈다. 그랬더니 PG 호출이 100% 실패라
// pg-approve 회로가 10건 만에 열리고(W4D1), 그 뒤 44건이 PG 에 닿지도 않고 FAILED 가 됐다.
// 셋업 경로가 <b>처음 10건에서만</b> 성립한 것이다.
//
// 회로를 끄지 않는다 — 그건 프로덕션 동작의 일부이고, 끄고 잰 수치는 "우리 시스템이
// 이렇다"가 아니게 된다(W4D1 이 회로를 테스트가 갈아끼우는 자리 밖에 둔 것과 같은 이유).
// 대신 <b>부하 쪽을 맞춘다</b>.
//
// 난수가 아니라 전역 이터레이션 번호로 고정한다: 난수로 30% 를 뽑으면 20건 창에서 우연히
// 10건을 넘길 확률이 약 5% 라 실행마다 회로가 열렸다 말았다 한다. 10건 중 정확히 3건이면
// 어느 창에서도 6건이라 임계(10건)에 여유가 남는다.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// 상품 id 는 seed.sql 이 마지막에 찍어준다. 기본값은 깨끗한 환경 기준이고, 다르면 env 로 준다.
const PRODUCT_TIMEOUT = Number(__ENV.PRODUCT_TIMEOUT || 5);  // 10,222 → PgTimeoutException
const PRODUCT_APPROVE = Number(__ENV.PRODUCT_APPROVE || 4);  // 10,000 → APPROVED
const TIMEOUT_PER_10 = Number(__ENV.TIMEOUT_PER_10 || 3);    // 10건 중 몇 건을 타임아웃으로
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';

const orderFailed = new Counter('order_failed');
const webhookFailed = new Counter('webhook_failed');
const webhookSent = new Counter('webhook_sent');

export const options = {
  scenarios: {
    steady: {
      // 도착률 고정. VU 기반이 아니라 <b>초당 요청 수</b>를 고정해야, 앱이 죽는 순간의
      // in-flight 수가 예측 가능해진다 — 고아 수가 그 값에 비례하기 때문이다.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: RATE * 2,
      maxVUs: RATE * 6,
    },
  },
  // ⚠️ 임계값을 두지 않는다. 앱을 죽이는 게 <b>시나리오의 일부</b>라 대량 실패가 정상이다.
  // 여기에 http_req_failed 기준을 걸면 의도한 실험이 "실패"로 보고된다.
  thresholds: {},
};

export default function () {
  // 10건 중 앞 TIMEOUT_PER_10 건만 타임아웃 금액. 나머지는 승인되어 회로를 닫아둔다.
  // 승인된 결제는 이미 PAID 라 뒤이은 웹훅이 "뒤늦은 승인 → IGNORED" 로 흐른다 —
  // 전이는 없지만 웹훅 엔드포인트에는 실제 부하가 걸리고, 고아 후보에서는 빠진다.
  const isTimeout = exec.scenario.iterationInTest % 10 < TIMEOUT_PER_10;
  const productId = isTimeout ? PRODUCT_TIMEOUT : PRODUCT_APPROVE;

  // ① 주문 — 타임아웃 금액이면 payment=UNKNOWN, 승인 금액이면 payment=PAID
  const order = http.post(
    `${BASE}/orders`,
    JSON.stringify({ items: [{ productId, quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'order' } },
  );

  if (order.status !== 201) {
    orderFailed.add(1);
    return; // 앱이 죽은 뒤엔 여기로 떨어진다. 정상이다.
  }

  const body = order.json();
  const mpid = body.merchantPaymentId;

  // ② 웹훅 — eventId 에 mpid 를 넣는 게 사후 검증의 전부다.
  //    성공한 웹훅의 키도 24시간 남으므로(설계상 TTL) 키 개수만으로는 고아를 못 센다.
  //    "키는 있는데 그 결제가 아직 UNKNOWN" 이라야 고아이고, 그 대조를 하려면
  //    키에서 결제로 가는 길이 있어야 한다.
  const payload = JSON.stringify({
    eventId: `k6-orphan-${mpid}`,
    eventType: 'APPROVED',
    merchantPaymentId: mpid,
    pgTransactionId: `pgtx-k6-${mpid}`,
    amount: body.totalAmount,
  });

  webhookSent.add(1);
  const res = http.post(`${BASE}/webhooks/pg`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'webhook' },
  });

  const ok = check(res, { 'webhook 200': (r) => r.status === 200 });
  if (!ok) webhookFailed.add(1);
}

export function teardown() {
  console.log('');
  console.log('=== 고아 키 검증 (앱이 죽어 있으므로 DB·Redis 를 직접 본다) ===');
  console.log('  bash k6/verify.sh orphan');
  console.log('  고아 = webhook:dedup:k6-orphan-<mpid> 키가 있는데 그 payment 가 PAID 가 아님');
}
