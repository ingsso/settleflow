-- k6 부하용 마스터 데이터(W4D3). 여러 번 돌려도 안전하다.
--
-- 가격이 10,222 인 게 판단이다: StubPgClient 의 매직 금액 규칙(amount % 1000 == 222)이
-- PgTimeoutException 을 던져 결제를 UNKNOWN 으로 만든다. 그래야 뒤이어 오는 웹훅이
-- UNKNOWN → PAID 를 <b>실제로</b> 밟는다. 승인되는 금액으로 주문하면 결제가 이미 PAID 라
-- 웹훅은 "뒤늦은 승인 → IGNORED" 로 흘러가고, 그러면 부하가 아무것도 안 재게 된다.
--
-- 재고 100만: 시나리오 ②가 주문을 매 이터레이션 만든다. 재고가 마르면 OutOfStockException
-- 이 나면서 부하가 웹훅에 닿지 못한다 — 재는 대상이 조용히 바뀐다.

INSERT INTO seller (name, fee_rate)
SELECT 'k6 부하용 셀러', 500
WHERE NOT EXISTS (SELECT 1 FROM seller WHERE name = 'k6 부하용 셀러');

-- 존재 검사를 <b>셀러로 한정</b>한다. 가격만 보면, 다른 셀러가 우연히 같은 가격의 상품을
-- 가진 순간 이 INSERT 가 조용히 건너뛰고 부하가 남의 상품을 쓰게 된다(실제로 겪었다).
-- 부하 테스트는 자기 마스터 데이터를 소유해야 한다.
INSERT INTO product (seller_id, name, price, stock)
SELECT s.id, 'k6 부하용(타임아웃 매직금액)', 10222, 1000000
  FROM seller s
 WHERE s.name = 'k6 부하용 셀러'
   AND NOT EXISTS (SELECT 1 FROM product p JOIN seller s2 ON s2.id = p.seller_id
                    WHERE p.price = 10222 AND s2.name = 'k6 부하용 셀러');

-- ★ 승인용 상품이 따로 필요하다(W4D3 실측으로 드러남).
-- 모든 주문을 타임아웃 금액으로 내면 PG 호출이 100% 실패라 pg-approve 회로가 10건 만에
-- 열리고(minimum-number-of-calls=10, failure-rate-threshold=50), 그 뒤로는 호출이 PG 에
-- 닿지도 않고 바로 FAILED 가 된다 — 셋업 경로가 처음 10건에서만 성립한다.
-- 부하가 타임아웃을 30% 로 섞어 회로를 닫아둔다. 회로는 프로덕션 동작의 일부이므로
-- 끄지 않고, 부하 쪽을 맞춘다.
INSERT INTO product (seller_id, name, price, stock)
SELECT s.id, 'k6 부하용(승인)', 10000, 1000000
  FROM seller s
 WHERE s.name = 'k6 부하용 셀러'
   AND NOT EXISTS (SELECT 1 FROM product p JOIN seller s2 ON s2.id = p.seller_id
                    WHERE p.price = 10000 AND s2.name = 'k6 부하용 셀러');

-- 재고가 이전 회차에 줄었으면 되돌린다.
UPDATE product p SET stock = 1000000
  FROM seller s
 WHERE s.id = p.seller_id AND s.name = 'k6 부하용 셀러' AND p.stock < 1000000;

SELECT p.id AS product_id, p.name, p.price, p.stock
  FROM product p JOIN seller s ON s.id = p.seller_id
 WHERE s.name = 'k6 부하용 셀러' ORDER BY p.price;
