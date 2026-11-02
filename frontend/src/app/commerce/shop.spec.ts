import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ShopComponent } from './shop';
import { RazorpayCheckout } from './razorpay-checkout';
import { AuthService } from '../shared/auth.service';
import { environment } from '../../environments/environment';
import { Order, Quote } from './commerce-api';

describe('Commerce checkout safety', () => {
  const base = environment.apiUrl;
  const account = { id: 'company-a', name: 'Company A', merchantId: 'merchant-a', role: 'PAYER' };
  const second = { ...account, id: 'company-b', name: 'Company B' };
  const product = { id: 'product-a', sku: 'A', name: 'Filter', category: 'Parts', unitPriceMinor: 100, priceVersion: 1, availableQty: 3 };
  const quote: Quote = { currency: 'INR', totalMinor: 100, quoteToken: 'quote-company-a-INR-v1', lines: [{ productId: product.id, quantity: 1, priceVersion: 1, sku: 'A', description: 'Filter', unitPriceMinor: 100, lineTotalMinor: 100 }] };
  const order: Order = { id: 'order-a', status: 'PAYMENT_PENDING', totalMinor: 100, currency: 'INR', lines: quote.lines,
    payment: { id: 'intent-a', currency: 'INR', amountMinor: 100, status: 'CREATED', provider: 'RAZORPAY' } };
  let component: ShopComponent;
  let http: HttpTestingController;
  let open: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    sessionStorage.clear(); open = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: AuthService, useValue: { user: signal({ userId: 'payer-a' }) } },
      { provide: RazorpayCheckout, useValue: { open } }] });
    component = TestBed.runInInjectionContext(() => new ShopComponent());
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { component.ngOnDestroy(); http.verify(); sessionStorage.clear(); });
  function load(id = account.id) {
    http.expectOne(`${base}/commerce/accounts/${id}/catalog`).flush({ currency: 'INR', items: [product], page: 0, pageSize: 20 });
    http.expectOne(`${base}/commerce/accounts/${id}/cart`).flush({ version: 4, lines: [{ productId: product.id, quantity: 1 }] });
    http.expectOne(`${base}/commerce/accounts/${id}/orders`).flush([]);
  }
  function start() {
    component.ngOnInit(); http.expectOne(`${base}/billing/accounts`).flush([account, second]); load();
  }
  function review() { component.review(); http.expectOne(`${base}/commerce/accounts/${account.id}/quote`).flush(quote); }

  it('retains the exact checkout identity and price snapshot after a lost reply', () => {
    start(); review(); component.placeOrder();
    const first = http.expectOne(`${base}/commerce/accounts/${account.id}/checkout`);
    const key = first.request.headers.get('Checkout-Key'); const body = first.request.body;
    expect(body.cartVersion).toBe(4); expect(body.lines[0].priceVersion).toBe(1);
    first.flush({}, { status: 504, statusText: 'Timeout' });
    expect(component.canEdit()).toBe(false); expect(component.pending()?.key).toBe(key);
    component.placeOrder();
    const replay = http.expectOne(`${base}/commerce/accounts/${account.id}/checkout`);
    expect(replay.request.headers.get('Checkout-Key')).toBe(key); expect(replay.request.body).toEqual(body);
    replay.flush(order);
    http.expectOne(`${base}/commerce/accounts/${account.id}/orders`).flush([order]);
    http.expectOne(`${base}/commerce/accounts/${account.id}/cart`).flush({ version: 5, lines: [] });
    expect(component.pending()).toBeNull(); expect(component.selected()?.id).toBe(order.id);
  });
  it('requires a fresh review after a price rejection and sends no payment request', () => {
    start(); review(); component.placeOrder();
    http.expectOne(`${base}/commerce/accounts/${account.id}/checkout`).flush({ code: 'PRICE_CHANGED' }, { status: 409, statusText: 'Conflict' });
    expect(component.quote()).toBeNull(); expect(component.pending()).toBeNull();
    component.placeOrder(); http.expectNone(`${base}/commerce/accounts/${account.id}/checkout`);
    http.expectNone(`${base}/billing/intents/intent-a/submit`);
  });
  it('reloads a basket changed by another tab instead of overwriting it', () => {
    start(); component.changeQuantity(product.id, 2);
    const save = http.expectOne(`${base}/commerce/accounts/${account.id}/cart`);
    expect(save.request.body.version).toBe(4);
    save.flush({ code: 'CART_CHANGED' }, { status: 409, statusText: 'Conflict' });
    http.expectOne(`${base}/commerce/accounts/${account.id}/cart`).flush({ version: 6, lines: [{ productId: product.id, quantity: 3 }] });
    expect(component.count(product.id)).toBe(3); expect(component.quote()).toBeNull();
  });
  it('drops delayed responses after the buyer switches company', () => {
    component.ngOnInit(); http.expectOne(`${base}/billing/accounts`).flush([account, second]);
    const oldCatalog = http.expectOne(`${base}/commerce/accounts/${account.id}/catalog`);
    const oldCart = http.expectOne(`${base}/commerce/accounts/${account.id}/cart`);
    const oldOrders = http.expectOne(`${base}/commerce/accounts/${account.id}/orders`);
    component.selectAccount(second.id); load(second.id);
    oldCatalog.flush({ currency: 'USD', items: [{ ...product, name: 'Private old product' }], page: 0, pageSize: 20 });
    oldCart.flush({ version: 99, lines: [] }); oldOrders.flush([order]);
    expect(component.currency()).toBe('INR'); expect(component.cart().version).toBe(4);
    expect(component.products()[0].name).toBe('Filter'); expect(component.orders()).toEqual([]);
  });
  it('blocks another Pay click after an uncertain submit reply', () => {
    start(); component.selected.set(order); component.pay();
    http.expectOne(`${base}/billing/intents/intent-a/submit`).flush({}, { status: 504, statusText: 'Timeout' });
    expect(component.selected()?.payment.status).toBe('UNKNOWN');
    component.pay(); http.expectNone(`${base}/billing/intents/intent-a/submit`);
    expect(open).not.toHaveBeenCalled();
  });
  it('treats a browser success callback as a request for server confirmation', async () => {
    start(); const payment = { ...order.payment, status: 'REQUIRES_ACTION', checkout: { provider: 'RAZORPAY' as const, keyId: 'rzp_test_public', orderId: 'order_test1', amountMinor: 100, currency: 'INR' } };
    component.selected.set({ ...order, payment }); component.pay(); await Promise.resolve();
    expect(open).toHaveBeenCalled(); open.mock.calls[0][1]();
    expect(component.selected()?.status).toBe('PAYMENT_PENDING');
    http.expectOne(`${base}/billing/intents/intent-a/reconcile`).flush({ ...payment, status: 'CAPTURED' });
    http.expectOne(`${base}/commerce/orders/order-a`).flush({ ...order, payment: { ...payment, status: 'CAPTURED' } });
    expect(component.selected()?.status).toBe('PAYMENT_PENDING'); expect(component.explanation(component.selected()!)).toContain('finishing');
  });
  it('does not allow cancelling a submitted payment', () => {
    start(); component.selected.set({ ...order, payment: { ...order.payment, status: 'UNKNOWN' } });
    component.cancel(); http.expectNone(`${base}/commerce/orders/order-a/cancel`);
  });
  it('checks an order summary before making a payment action available', () => {
    start(); const summary = { id: order.id, status: order.status, currency: 'INR', totalMinor: 100 } as Order;
    component.openOrder(summary); expect(component.canPay(summary)).toBe(false);
    http.expectOne(`${base}/commerce/orders/order-a`).flush(order);
    expect(component.canPay(component.selected()!)).toBe(true);
  });
  it('ignores an older status read after a newer Pay result', () => {
    start(); component.selected.set(order); component.refreshOrder();
    const old = http.expectOne(`${base}/commerce/orders/order-a`);
    component.pay();
    http.expectOne(`${base}/billing/intents/intent-a/submit`).flush({ ...order.payment, status: 'UNKNOWN' });
    old.flush(order);
    expect(component.selected()?.payment.status).toBe('UNKNOWN'); expect(component.canPay(component.selected()!)).toBe(false);
  });
  it('ignores an older status read after cancellation', () => {
    start(); component.selected.set(order); component.refreshOrder();
    const old = http.expectOne(`${base}/commerce/orders/order-a`);
    component.cancel();
    http.expectOne(`${base}/commerce/orders/order-a/cancel`).flush({ ...order, status: 'CANCELLED', payment: { ...order.payment, status: 'CANCELLED' } });
    http.expectOne(`${base}/commerce/accounts/${account.id}/orders`).flush([]);
    old.flush(order); expect(component.selected()?.status).toBe('CANCELLED');
  });
  it('reloads saved orders and the basket after a definitive checkout conflict', () => {
    start(); review(); component.placeOrder();
    http.expectOne(`${base}/commerce/accounts/${account.id}/checkout`).flush({ code: 'IDEMPOTENCY_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    http.expectOne(`${base}/commerce/accounts/${account.id}/cart`).flush({ version: 5, lines: [] });
    http.expectOne(`${base}/commerce/accounts/${account.id}/orders`).flush([order]);
    expect(component.pending()).toBeNull(); expect(component.cart().lines).toEqual([]); expect(component.orders()[0].id).toBe(order.id);
  });
  it('reuses the retry key after a lost reply instead of creating another attempt', () => {
    start(); const failed = { ...order, payment: { ...order.payment, status: 'FAILED' } };
    component.selected.set(failed); component.retry();
    const first = http.expectOne(`${base}/billing/intents/intent-a/retry`); const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 504, statusText: 'Timeout' });
    http.expectOne(`${base}/commerce/orders/order-a`).flush(failed);
    component.retry(); const next = http.expectOne(`${base}/billing/intents/intent-a/retry`);
    expect(next.request.headers.get('Idempotency-Key')).toBe(key);
    next.flush({ ...order.payment, id: 'intent-b' });
    http.expectOne(`${base}/commerce/orders/order-a`).flush({ ...order, payment: { ...order.payment, id: 'intent-b' } });
    expect(component.selected()?.payment.id).toBe('intent-b');
  });
  it('uses a new retry identity when a later attempt has failed', () => {
    start(); component.selected.set({ ...order, payment: { ...order.payment, status: 'FAILED' } }); component.retry();
    const first = http.expectOne(`${base}/billing/intents/intent-a/retry`); const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 504, statusText: 'Timeout' });
    http.expectOne(`${base}/commerce/orders/order-a`).flush({ ...order, payment: { ...order.payment, id: 'intent-b', status: 'FAILED' } });
    component.retry(); const later = http.expectOne(`${base}/billing/intents/intent-b/retry`);
    expect(later.request.headers.get('Idempotency-Key')).not.toBe(key);
    later.flush({ ...order.payment, id: 'intent-c' }); http.expectOne(`${base}/commerce/orders/order-a`).flush({ ...order, payment: { ...order.payment, id: 'intent-c' } });
  });
  it('keeps the selected order stable while a payment request is in flight', () => {
    start(); component.selected.set(order); component.pay(); component.openOrder({ ...order, id: 'order-b' });
    expect(component.selected()?.id).toBe(order.id); http.expectNone(`${base}/commerce/orders/order-b`);
    http.expectOne(`${base}/billing/intents/intent-a/submit`).flush({ ...order.payment, status: 'UNKNOWN' });
  });
  it('clears an invalid saved checkout only after a definitive rejection', () => {
    start(); review(); component.placeOrder(); http.expectOne(`${base}/commerce/accounts/${account.id}/checkout`).flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Invalid' });
    http.expectOne(`${base}/commerce/accounts/${account.id}/cart`).flush({ version: 5, lines: [] });
    http.expectOne(`${base}/commerce/accounts/${account.id}/orders`).flush([]); expect(component.pending()).toBeNull();
  });
  it('shows payment uncertainty without inviting the buyer to pay again', () => {
    const uncertain = { ...order, payment: { ...order.payment, status: 'UNKNOWN' } };
    expect(component.statusLabel(uncertain)).toBe('Confirming payment');
    expect(component.canPay(uncertain)).toBe(false);
    expect(component.explanation(uncertain)).toContain('do not pay again');
  });
  it('shows a confirmed refund separately from an order handling review', () => {
    const refunded: Order = { ...order, status: 'NEEDS_REVIEW', paymentSummary: { status: 'PARTIALLY_REFUNDED', capturedMinor: 100, refundedMinor: 40, currency: 'INR' } };
    expect(component.statusLabel(refunded)).toBe('Partially refunded');
    expect(component.explanation(refunded)).toContain('partial refund');
    expect(component.canPay(refunded)).toBe(false);
    expect(component.statusLabel({ ...refunded, paymentSummary: { ...refunded.paymentSummary!, status: 'REFUNDED', refundedMinor: 100 } })).toBe('Refund issued');
  });
  it('does not expose an internal order UUID as a customer reference', () => {
    expect(component.orderLabel(order)).toBe('Order');
    expect(component.orderLabel({ ...order, orderNumber: 10001 })).toBe('Order #10001');
  });
  it('does not invite repayment when an older attempt has already captured', () => {
    const captured: Order = { ...order, paymentSummary: { status: 'PAID', capturedMinor: 100, refundedMinor: 0, currency: 'INR' } };
    component.selected.set(captured);
    expect(component.canPay(captured)).toBe(false);
    expect(component.statusLabel(captured)).toBe('Payment received');
    component.pay(); component.cancel();
    component.selected.set({ ...captured, payment: { ...order.payment, status: 'FAILED' } }); component.retry();
    http.expectNone(request => request.url.includes('/billing/intents/') || request.url.includes('/cancel'));
  });
  it('does not deny or invite replacement of a late payment on a closed order', () => {
    for (const status of ['CANCELLED','EXPIRED']) {
      const late: Order = { ...order, status, paymentSummary: { status: 'PAID', capturedMinor: 100, refundedMinor: 0, currency: 'INR' } };
      expect(component.explanation(late)).toContain('received a payment');
      expect(component.explanation(late)).toContain('do not pay again');
      expect(component.explanation(late)).not.toMatch(/before payment|start a new order/);
      expect(component.statusLabel(late)).toBe('Payment received · under review');
      expect(component.canPay(late)).toBe(false);
    }
  });
  it('does not invite a new order when the hold expired but the first payment is still unresolved', () => {
    for (const status of ['UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION']) {
      const expired: Order = { ...order, orderNumber: 10082, status: 'EXPIRED', payment: { ...order.payment, status } };
      expect(component.explanation(expired)).toContain('still checking your original payment');
      expect(component.explanation(expired)).toContain('do not pay again');
      expect(component.explanation(expired)).toContain('order #10082');
      expect(component.explanation(expired)).not.toMatch(/start a new order/);
      expect(component.statusLabel(expired)).toBe('Reservation expired · checking payment');
      expect(component.canPay(expired)).toBe(false);
    }
    const listRow = { id: order.id, status: 'EXPIRED', totalMinor: 100, currency: 'INR', paymentSummary: { status: 'PENDING', capturedMinor: 0, refundedMinor: 0, currency: 'INR' } } as Order;
    expect(component.explanation(listRow)).toContain('this order');
    const settled: Order = { ...order, status: 'EXPIRED', payment: { ...order.payment, status: 'FAILED' } };
    expect(component.explanation(settled)).toContain('start a new order');
  });
  it('keeps checking an expired order while its payment is unresolved', () => {
    const expired: Order = { ...order, status: 'EXPIRED', payment: { ...order.payment, status: 'UNKNOWN' } };
    component.openOrder(order);
    http.expectOne(request => request.url.endsWith(`/orders/${order.id}`)).flush(expired);
    expect((component as unknown as { timer?: unknown }).timer).toBeDefined();
    component.ngOnDestroy();
  });
  it('asks for checkout completion when action is required despite a pending summary', () => {
    const action: Order = { ...order, payment: { ...order.payment, status: 'REQUIRES_ACTION' }, paymentSummary: { status: 'PENDING', capturedMinor: 0, refundedMinor: 0, currency: 'INR' } };
    expect(component.statusLabel(action)).toBe('Complete payment');
    expect(component.explanation(action)).toContain('checkout window');
    expect(component.canPay(action)).toBe(true);
    const listRow = { id: order.id, status: order.status, totalMinor: 100, currency: 'INR', paymentSummary: { status: 'ACTION_REQUIRED', capturedMinor: 0, refundedMinor: 0, returnedMinor: 0, currency: 'INR' } } as Order;
    expect(component.statusLabel(listRow)).toBe('Complete payment');
    expect(component.canPay(listRow)).toBe(false);
  });
  it('lets the buyer remove a saved product that is no longer in the catalog', () => {
    const fixture = TestBed.createComponent(ShopComponent); component = fixture.componentInstance;
    fixture.detectChanges(); http.expectOne(`${base}/billing/accounts`).flush([account]);
    http.expectOne(`${base}/commerce/accounts/${account.id}/catalog`).flush({ currency: 'INR', items: [], page: 0, pageSize: 20 });
    http.expectOne(`${base}/commerce/accounts/${account.id}/cart`).flush({ version: 4, lines: [{ productId: 'removed-product', quantity: 2 }] });
    http.expectOne(`${base}/commerce/accounts/${account.id}/orders`).flush([]); fixture.detectChanges();
    const remove = (fixture.nativeElement as HTMLElement).querySelector('button[aria-label="Remove Saved product"]') as HTMLButtonElement;
    expect(remove).not.toBeNull(); expect(remove.disabled).toBe(false); remove.click();
    const save = http.expectOne(`${base}/commerce/accounts/${account.id}/cart`);
    expect(save.request.method).toBe('PUT'); expect(save.request.body).toEqual({ version: 4, lines: [] });
    save.flush({ version: 5, lines: [] }); fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('button[aria-label="Remove Saved product"]')).toBeNull();
  });
  it('still requests server confirmation when the checkout finishes after navigation', async () => {
    start(); let finish!: () => void;
    open.mockImplementation((_config, check) => new Promise<void>(resolve => { finish = () => { check(); resolve(); }; }));
    const payment = { ...order.payment, status: 'REQUIRES_ACTION', checkout: { provider: 'RAZORPAY' as const, keyId: 'rzp_test_public', orderId: 'order_test1', amountMinor: 100, currency: 'INR' } };
    component.selected.set({ ...order, payment }); component.pay(); await Promise.resolve();
    component.ngOnDestroy(); finish();
    http.expectOne(`${base}/billing/intents/intent-a/reconcile`).flush({ ...payment, status: 'CAPTURED' });
    http.expectNone(`${base}/commerce/orders/order-a`);
    expect(component.selected()?.status).toBe('PAYMENT_PENDING'); expect(component.notice()).toBe('');
    await Promise.resolve();
  });
  it('refuses a checkout whose payment amount or currency differs from the order', () => {
    for (const [amountMinor, currency] of [[200, 'INR'], [100, 'USD']] as const) {
      const payment = { ...order.payment, amountMinor, currency, status: 'REQUIRES_ACTION', checkout: { provider: 'RAZORPAY' as const, keyId: 'rzp_test_public', orderId: 'order_test1', amountMinor, currency } };
      component.selected.set({ ...order, payment }); component.pay();
      expect(component.error()).toContain('differs'); expect(open).not.toHaveBeenCalled();
    }
    http.expectNone(`${base}/billing/intents/intent-a/submit`);
  });
  it('rechecks the order amount when a submit response supplies checkout details', () => {
    start(); component.selected.set(order); component.pay();
    http.expectOne(`${base}/billing/intents/intent-a/submit`).flush({ ...order.payment, amountMinor: 200, status: 'REQUIRES_ACTION', checkout: { provider: 'RAZORPAY', keyId: 'rzp_test_public', orderId: 'order_test1', amountMinor: 200, currency: 'INR' } });
    expect(component.error()).toContain('differs'); expect(open).not.toHaveBeenCalled();
  });
  it('does not simulate another capture when the reference already has recorded money', () => {
    component.selected.set({ ...order, payment: { ...order.payment, status: 'PROCESSING', simulatorEnabled: true }, paymentSummary: { status: 'PAID', capturedMinor: 100, refundedMinor: 0, returnedMinor: 0, currency: 'INR' } });
    component.simulate();http.expectNone(`${base}/billing/intents/intent-a/simulate`);
  });
});
