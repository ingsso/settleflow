// 시나리오 ③ — 동시 재시도 레이스: 구독 시딩 (W5 마무리)
//
// ★ 이 스크립트는 부하 발생기가 아니다. 부하원은 앱 인스턴스의 폴러 자신이고
//   (k6/dunning-race.md §1), k6 가 맡은 일은 셋이다:
//     ① 구독 500개를 POST /subscriptions 로 만든다 (프로덕션 경로를 탄다)
//     ② 만들어진 수를 세어 시딩이 실제로 성립했는지 확인한다
//     ③ 그 뒤로는 아무것도 안 한다 — 청구는 폴러가 한다
//
// ⚠️ 시딩 중에는 폴러가 돌면 안 된다. 만드는 도중에 집혀버리면 "500개가 동시에 도래한
//   순간"이 안 만들어지고 경합이 시딩 속도에 흩어진다. 그래서 시딩 인스턴스는
//   settleflow.dunning.scheduler.enabled=false 로 띄운다(README 절차 참조).
//
// 금액이 결과를 정한다 — StubPgClient 의 매직 금액:
//   30,000 → APPROVED / 30,222 → PgTimeoutException(UNKNOWN)
// 10개 중 3개를 타임아웃으로 둔다. 난수가 아니라 인덱스로 고정하는 이유는 시나리오 ②와
// 같다(W4D3): 난수면 창마다 비율이 흔들려 회로가 열렸다 말았다 한다. 인덱스로 박으면
// 어느 10개 창에서도 정확히 3개다.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SELLER_FROM = Number(__ENV.SELLER_FROM);       // seed-subscription-race.sql 이 찍어준다
const COUNT = Number(__ENV.COUNT || 500);
const TIMEOUT_PER_10 = Number(__ENV.TIMEOUT_PER_10 || 3);

const AMOUNT_OK = 30000;
const AMOUNT_TIMEOUT = 30222;
const INTERVAL_DAYS = 30;

const created = new Counter('subscriptions_created');
const conflicted = new Counter('subscriptions_conflict');
const failed = new Counter('subscriptions_failed');

export const options = {
    scenarios: {
        seed: {
            executor: 'shared-iterations',
            vus: 20,
            iterations: COUNT,
            maxDuration: '2m',
        },
    },
    // 시딩이 조용히 절반만 성공하면 그 뒤 수치가 전부 무의미해진다.
    thresholds: {
        'subscriptions_created': [`count==${COUNT}`],
        'subscriptions_failed': ['count==0'],
    },
};

export function setup() {
    if (!Number.isFinite(SELLER_FROM)) {
        throw new Error('SELLER_FROM 을 넘겨야 한다 — seed-subscription-race.sql 출력의 seller_from');
    }
    console.log(`시딩 시작: sellerId ${SELLER_FROM} ~ ${SELLER_FROM + COUNT - 1} (${COUNT}건), ` +
        `타임아웃 비율 ${TIMEOUT_PER_10}/10`);
}

export default function () {
    // 전역 이터레이션 번호. __ITER 는 VU 별 카운터라 그대로 쓰면 셀러가 겹친다 —
    // iterationInTest 는 시나리오 전체에서 0..COUNT-1 이 정확히 한 번씩 나온다.
    const n = exec.scenario.iterationInTest;
    const sellerId = SELLER_FROM + n;
    const amount = (n % 10) < TIMEOUT_PER_10 ? AMOUNT_TIMEOUT : AMOUNT_OK;

    const res = http.post(`${BASE}/subscriptions`, JSON.stringify({
        sellerId: sellerId,
        amount: amount,
        intervalDays: INTERVAL_DAYS,
    }), { headers: { 'Content-Type': 'application/json' } });

    if (res.status === 201) created.add(1);
    else if (res.status === 409) conflicted.add(1);
    else failed.add(1);

    check(res, { '구독 생성 201': (r) => r.status === 201 });
}

export function teardown() {
    console.log('시딩 끝. 이제 폴러가 도는 인스턴스를 띄우면 500개가 동시에 도래한다.');
}
