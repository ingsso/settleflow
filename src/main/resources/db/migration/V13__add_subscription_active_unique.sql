-- 셀러당 활성 구독은 하나다 (W5D5 — 구독 생성 경로).
--
-- 왜 필요한가: 주문 중복은 한 번의 손해지만 구독 중복은 매달 반복되는 손해다.
-- POST /orders 에 멱등 장치를 안 둔 것과 여기서 갈리는 이유가 그것이다.
--
-- 왜 CHECK 이 아니라 부분 유니크 인덱스인가: 조건이 "다른 행과의 관계"라 CHECK 으로는
-- 표현할 수 없다. W1D4 가 이중청구를 막은 (order_id) WHERE status='PAID' 와 같은 계열이고,
-- 해지된 구독은 담기지 않으므로 "해지 후 재구독"이 자연히 열린다.
--
-- 앱에도 같은 판정이 있다(SubscriptionService.create 의 사전 확인). 그건 친절한 409 를
-- 위한 것이고 정합성은 이 한 문장이 지킨다 — 읽고 판단하고 쓰는 사이의 틈에서 둘 다
-- 통과하는 경로를, 앱 코드로는 못 막는다(W1D2 reserveRefund 와 같은 분업).
CREATE UNIQUE INDEX uq_subscription_active_seller
    ON subscription (seller_id)
    WHERE status = 'ACTIVE';
