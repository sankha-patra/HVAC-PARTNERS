CREATE SCHEMA commerce;

CREATE TABLE commerce.products (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 sku VARCHAR(80) NOT NULL, name VARCHAR(180) NOT NULL, category VARCHAR(80) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 1,
 UNIQUE(merchant_id,sku), UNIQUE(id,merchant_id)
);
CREATE TABLE commerce.price_lists (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 name VARCHAR(120) NOT NULL, currency CHAR(3) NOT NULL CHECK(currency IN ('INR','USD')),
 UNIQUE(id,merchant_id)
);
CREATE TABLE commerce.account_price_lists (
 account_id UUID PRIMARY KEY, merchant_id UUID NOT NULL, price_list_id UUID NOT NULL,
 FOREIGN KEY(account_id,merchant_id) REFERENCES billing.accounts(id,merchant_id),
 FOREIGN KEY(price_list_id,merchant_id) REFERENCES commerce.price_lists(id,merchant_id)
);
CREATE TABLE commerce.prices (
 price_list_id UUID NOT NULL, product_id UUID NOT NULL, merchant_id UUID NOT NULL,
 unit_price_minor BIGINT NOT NULL CHECK(unit_price_minor>0 AND unit_price_minor<=99999999),
 version BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(price_list_id,product_id),
 FOREIGN KEY(price_list_id,merchant_id) REFERENCES commerce.price_lists(id,merchant_id),
 FOREIGN KEY(product_id,merchant_id) REFERENCES commerce.products(id,merchant_id)
);
CREATE TABLE commerce.stock (
 product_id UUID PRIMARY KEY REFERENCES commerce.products(id),
 saleable_qty INTEGER NOT NULL CHECK(saleable_qty>=0), reserved_qty INTEGER NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 1, CHECK(reserved_qty>=0 AND reserved_qty<=saleable_qty)
);
-- A committed generation in PostgreSQL prevents stale cache fills even when Redis or Kafka is down.
CREATE TABLE commerce.catalog_versions (
 revision BIGSERIAL PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id)
);
CREATE INDEX commerce_catalog_latest ON commerce.catalog_versions(merchant_id,revision DESC);
CREATE FUNCTION commerce.catalog_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE merchant UUID;
BEGIN
 IF TG_TABLE_NAME='stock' THEN
   SELECT merchant_id INTO merchant FROM commerce.products WHERE id=COALESCE(NEW.product_id,OLD.product_id);
 ELSE merchant:=COALESCE(NEW.merchant_id,OLD.merchant_id); END IF;
 IF merchant IS NOT NULL THEN
   -- Append-only commit visibility avoids a shared merchant-row lock between stock rows.
   INSERT INTO commerce.catalog_versions(merchant_id) VALUES(merchant);
 END IF;
 RETURN COALESCE(NEW,OLD);
END $$;
CREATE FUNCTION commerce.next_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.version:=OLD.version+1; RETURN NEW; END $$;
CREATE TRIGGER product_version BEFORE UPDATE ON commerce.products FOR EACH ROW EXECUTE FUNCTION commerce.next_version();
CREATE TRIGGER price_version BEFORE UPDATE ON commerce.prices FOR EACH ROW EXECUTE FUNCTION commerce.next_version();
CREATE TRIGGER stock_version BEFORE UPDATE ON commerce.stock FOR EACH ROW EXECUTE FUNCTION commerce.next_version();
CREATE TRIGGER product_catalog AFTER INSERT OR UPDATE OR DELETE ON commerce.products FOR EACH ROW EXECUTE FUNCTION commerce.catalog_changed();
CREATE TRIGGER price_catalog AFTER INSERT OR UPDATE OR DELETE ON commerce.prices FOR EACH ROW EXECUTE FUNCTION commerce.catalog_changed();
CREATE TRIGGER stock_catalog AFTER INSERT OR UPDATE OR DELETE ON commerce.stock FOR EACH ROW EXECUTE FUNCTION commerce.catalog_changed();
CREATE TABLE commerce.carts (
 account_id UUID NOT NULL REFERENCES billing.accounts(id), user_id VARCHAR(160) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, lines JSONB NOT NULL DEFAULT '[]', updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(account_id,user_id), CHECK(jsonb_typeof(lines)='array')
);
CREATE TABLE commerce.orders (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES billing.accounts(id), created_by VARCHAR(160) NOT NULL,
 checkout_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, cart_version BIGINT NOT NULL CHECK(cart_version>0), currency CHAR(3) NOT NULL,
 total_minor BIGINT NOT NULL CHECK(total_minor>0 AND total_minor<=99999999),
 status TEXT NOT NULL CHECK(status IN ('PAYMENT_PENDING','PAID','CANCELLED','EXPIRED','NEEDS_REVIEW')),
 payment_reference UUID, hold_until TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(account_id,checkout_key), UNIQUE(account_id,created_by,cart_version), UNIQUE(payment_reference)
);
CREATE INDEX commerce_hold_due ON commerce.orders(hold_until,id) WHERE status='PAYMENT_PENDING';
CREATE TABLE commerce.order_lines (
 order_id UUID NOT NULL REFERENCES commerce.orders(id), product_id UUID NOT NULL REFERENCES commerce.products(id),
 sku VARCHAR(80) NOT NULL, description VARCHAR(180) NOT NULL, quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 100),
 unit_price_minor BIGINT NOT NULL CHECK(unit_price_minor>0), line_total_minor BIGINT NOT NULL,
 price_version BIGINT NOT NULL, PRIMARY KEY(order_id,product_id), CHECK(line_total_minor=quantity*unit_price_minor)
);
CREATE FUNCTION commerce.immutable_order_line() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'An order price snapshot is immutable'; END $$;
CREATE TRIGGER immutable_order_line BEFORE UPDATE OR DELETE ON commerce.order_lines
 FOR EACH ROW EXECUTE FUNCTION commerce.immutable_order_line();
CREATE TABLE commerce.stock_reservations (
 order_id UUID NOT NULL REFERENCES commerce.orders(id), product_id UUID NOT NULL REFERENCES commerce.products(id),
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 100),
 state TEXT NOT NULL CHECK(state IN ('RESERVED','CONSUMED','RELEASED')), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(order_id,product_id)
);
CREATE TABLE commerce.order_bills (
 order_id UUID PRIMARY KEY REFERENCES commerce.orders(id), invoice_id UUID NOT NULL UNIQUE REFERENCES billing.invoices(id),
 payment_reference UUID NOT NULL UNIQUE REFERENCES billing.intents(id)
);
CREATE TABLE commerce.order_event_receipts (
 event_id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES commerce.orders(id), intent_id UUID NOT NULL REFERENCES billing.intents(id),
 effect TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(order_id,intent_id,effect)
);
-- This is the local fulfilment hand-off, not an assertion of an external shipment.
CREATE TABLE commerce.fulfilments (
 order_id UUID PRIMARY KEY REFERENCES commerce.orders(id), id UUID NOT NULL UNIQUE,
 state TEXT NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','SHIPPED')), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
