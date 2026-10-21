// 웹훅 서명 헤더 — WebhookSignatureVerifier 와 같은 규칙.
//
//   X-Timestamp = 지금(epoch 초)
//   X-Signature = hex( HMAC-SHA256(secret, timestamp + "." + 바디) )
//
// 서명 검증이 SETNX 앞에 생긴 뒤로 헤더 없는 웹훅은 전부 401 이다. 시크릿은 앱과 같은
// WEBHOOK_SECRET env 로 받는다 — 다르면 모든 요청이 401 이고 k6 는 그걸 "non-200" 으로만 센다.
//
// ⚠️ 서명 이전에 잰 수치(k6/README, W4D4)와 바로 비교하지 말 것. 서버는 요청마다 HMAC 을
//    한 번 더 계산하고(관문 거절 경로에도 얹힌다), 클라이언트도 반복마다 서명한다.

import { hmac } from 'k6/crypto';

const SECRET = __ENV.WEBHOOK_SECRET;

if (!SECRET) {
  throw new Error('WEBHOOK_SECRET 을 env 로 줄 것 (앱 기동에 쓴 값과 같아야 한다)');
}

export function signedWebhookHeaders(payload) {
  const ts = String(Math.floor(Date.now() / 1000));
  return {
    'Content-Type': 'application/json',
    'X-Timestamp': ts,
    'X-Signature': hmac('sha256', SECRET, `${ts}.${payload}`, 'hex'),
  };
}
