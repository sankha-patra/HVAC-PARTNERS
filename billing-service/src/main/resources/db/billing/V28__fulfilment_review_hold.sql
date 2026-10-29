-- Financial history remains immutable. A refund before shipment pauses an unshipped hand-off.
ALTER TABLE commerce.fulfilments DROP CONSTRAINT fulfilments_state_check;
ALTER TABLE commerce.fulfilments ADD CONSTRAINT fulfilments_state_check CHECK(state IN ('READY','ON_HOLD','SHIPPED'));
