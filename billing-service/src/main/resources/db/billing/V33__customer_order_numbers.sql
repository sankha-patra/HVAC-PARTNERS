-- Public display references are independent of internal UUIDs and checkout identities.
ALTER TABLE commerce.orders ADD COLUMN order_number BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 10001);
ALTER TABLE commerce.orders ADD CONSTRAINT orders_public_number_unique UNIQUE(order_number);
ALTER TABLE commerce.orders ADD CONSTRAINT orders_public_number_positive CHECK(order_number > 0);
CREATE FUNCTION commerce.keep_order_number() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.order_number IS DISTINCT FROM OLD.order_number THEN
        RAISE EXCEPTION 'Order number is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER orders_keep_number BEFORE UPDATE OF order_number ON commerce.orders
    FOR EACH ROW EXECUTE FUNCTION commerce.keep_order_number();
