// 시나리오 ① — 웹훅 중복 폭주 (계획서 Week 4)
//
// 같은 eventId 를 N 개 VU 가 동시에 쏜다. 겨누는 명제:
//   "N 번 와도 장부는 1줄"
//
// ★ HTTP 로는 아무것도 안 보인다. 우리 웹훅은 중복에도 200 을 준다
// (WebhookController javadoc: non-2xx 를 주면 PG 가 몇 시간 재전송한다).
// 그래서 이 스크립트가 내는 건 "요청이 몇 개 갔고 몇 개가 200 이었나"까지이고,
// 명제의 검증은 실행 뒤 verify.sh 가 DB 에서 한다.
// 마지막에 찍는 mpid 를 그쪽에 넘겨야 한다.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { signedWebhookHeaders } from './lib/signed-webhook.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
// 타임아웃 매직금액 상품(10,222). seed.sql 이 id 를 찍어준다.
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 5);
const REQUESTS = Number(__ENV.REQUESTS || 1000);

const nonOk = new Counter('webhook_non_200');

export const options = {
  scenarios: {
    burst: {
      // shared-iterations: 총 REQUESTS 개를 VU 들이 나눠 갖는다. VU 수를 요청 수와
      // 같게 두면 VU 당 1회라, 초기화가 끝난 뒤 거의 같은 시각에 출발한다.
      // ⚠️ k6 에는 배리어가 없어 "완전 동시"가 아니다 — PaymentFlowTest 의
      //    CyclicBarrier(20개)와는 성질이 다르다. 이쪽은 규모를 본다.
      executor: 'shared-iterations',
      vus: REQUESTS,
      iterations: REQUESTS,
      maxDuration: '3m',
    },
  },
  thresholds: {
    // 중복이 200 인 게 정상이므로 실패율 기준은 "요청이 실제로 닿았는가"에 건다.
    http_req_failed: ['rate<0.01'],
  },
};

// 부하 대상 결제를 하나 만든다. 10,222 원이라 PG 가 타임아웃 → payment=UNKNOWN.
// 이 상태여야 뒤이어 오는 웹훅이 실제 전이를 밟는다.
export function setup() {
  const res = http.post(
    `${BASE}/orders`,
    JSON.stringify({ items: [{ productId: PRODUCT_ID, quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status !== 201) {
    throw new Error(`셋업 실패: POST /orders → ${res.status} ${res.body}`);
  }

  const body = res.json();
  console.log(`[setup] merchantPaymentId=${body.merchantPaymentId} amount=${body.totalAmount}`);
  return { mpid: body.merchantPaymentId, amount: body.totalAmount };
}

export default function (data) {
  // ★ 모든 VU 가 같은 eventId 를 쓴다 — 이게 이 시나리오의 전부다.
  const payload = JSON.stringify({
    eventId: `k6-dup-${data.mpid}`,
    eventType: 'APPROVED',
    merchantPaymentId: data.mpid,
    pgTransactionId: `pgtx-k6-${data.mpid}`,
    amount: data.amount,
  });

  const res = http.post(`${BASE}/webhooks/pg`, payload, {
    headers: signedWebhookHeaders(payload),
    tags: { name: 'webhook' },
  });

  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  if (!ok) nonOk.add(1);
}

export function teardown(data) {
  console.log('');
  console.log('=== 검증은 DB 에서 한다 (HTTP 로는 안 보인다) ===');
  console.log(`  bash k6/verify.sh duplicate ${data.mpid}`);
  console.log('  기대: journal 1줄 / payment.version=2 / outbox 1건');
}
