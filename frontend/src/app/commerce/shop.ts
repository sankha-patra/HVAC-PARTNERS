import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, timeout } from 'rxjs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BillingAccount, BillingApi } from '../billing/billing-api';
import { AuthService } from '../shared/auth.service';
import { Cart, Checkout, CommerceApi, Order, Product, Quote, ShopPayment } from './commerce-api';
import { RazorpayCheckout } from './razorpay-checkout';

interface PendingCheckout { key: string; body: Checkout; }
@Component({ selector: 'app-shop', imports: [CommonModule, FormsModule, RouterLink], templateUrl: './shop.html', styleUrl: './shop.scss' })
export class ShopComponent implements OnInit, OnDestroy {
  private readonly api = inject(CommerceApi);
  private readonly billing = inject(BillingApi);
  private readonly auth = inject(AuthService);
  private readonly checkoutWindow = inject(RazorpayCheckout);
  private readonly route = inject(ActivatedRoute, { optional: true });
  readonly ordersView = signal(this.route?.snapshot.data['view'] === 'orders' || this.route?.snapshot.fragment === 'orders');
  private subscriptions = new Subscription();
  private epoch = 0;
  private mutation = 0;
  private timer?: ReturnType<typeof setInterval>;
  private pollUntil = 0;
  readonly accounts = signal<BillingAccount[]>([]);
  readonly accountsLoaded = signal(false);
  readonly ordersLoaded = signal(false);
  readonly account = signal('');
  readonly products = signal<Product[]>([]);
  readonly currency = signal('INR');
  readonly cart = signal<Cart>({ version: 0, lines: [] });
  readonly quote = signal<Quote | null>(null);
  readonly orders = signal<Order[]>([]);
  readonly selected = signal<Order | null>(null);
  readonly pending = signal<PendingCheckout | null>(null);
  readonly busy = signal(false);
  readonly checking = signal(false);
  readonly ready = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly quantity = computed(() => this.cart().lines.reduce((total, line) => total + line.quantity, 0));
  readonly canEdit = computed(() => this.ready() && !this.busy() && !this.pending());
  ngOnInit() {
    if (this.route) this.subscriptions.add(this.route.fragment.subscribe(fragment => this.ordersView.set(this.route!.snapshot.data['view'] === 'orders' || fragment === 'orders')));
    this.subscriptions.add(this.billing.accounts().subscribe({ next: accounts => {
      this.accounts.set(accounts.filter(a => a.role === 'PAYER')); this.accountsLoaded.set(true);
      if (this.accounts().length) this.selectAccount(this.accounts()[0].id);
    }, error: () => { this.accountsLoaded.set(true); this.error.set('Your companies could not be loaded. Please try again.'); } }));
  }
  private storageKey() { return `commerce-checkout:${this.auth.user()?.userId ?? 'unknown'}:${this.account()}`; }
  private savePending(value: PendingCheckout | null) {
    this.pending.set(value);
    try { if (value) sessionStorage.setItem(this.storageKey(), JSON.stringify(value)); else sessionStorage.removeItem(this.storageKey()); }
    catch { /* A blocked browser store does not change the server's idempotency guard. */ }
  }
  selectAccount(account: string) {
    if (this.busy()) return;
    if (!this.accounts().some(a => a.id === account)) return;
    const epoch = ++this.epoch;
    this.stopPolling(); this.account.set(account); this.products.set([]); this.cart.set({ version: 0, lines: [] });
    this.orders.set([]); this.selected.set(null); this.quote.set(null); this.pending.set(null);
    this.ordersLoaded.set(false);
    this.ready.set(false); this.busy.set(false); this.checking.set(false); this.error.set(''); this.notice.set('');
    try {
      const saved = JSON.parse(sessionStorage.getItem(this.storageKey()) ?? 'null');
      if (saved?.key && saved?.body?.lines?.length) this.pending.set(saved);
    } catch { /* Ignore a corrupt tab-local checkout record. */ }
    this.subscriptions.add(this.api.catalog(account).subscribe({ next: catalog => {
      if (epoch !== this.epoch) return; this.products.set(catalog.items); this.currency.set(catalog.currency);
    }, error: () => { if (epoch === this.epoch) this.error.set('The catalog is unavailable. Your saved orders are safe.'); } }));
    this.loadCart(epoch); this.loadOrders(epoch);
  }
  private loadCart(epoch = this.epoch) {
    this.subscriptions.add(this.api.cart(this.account()).subscribe({ next: cart => {
      if (epoch !== this.epoch) return; if (cart.version !== this.cart().version) this.quote.set(null); this.cart.set(cart); this.ready.set(true);
    }, error: () => { if (epoch === this.epoch) this.error.set('Your basket could not be loaded. Please reload it.'); } }));
  }
  private loadOrders(epoch = this.epoch) {
    this.subscriptions.add(this.api.orders(this.account()).subscribe({ next: orders => { if (epoch === this.epoch) { this.orders.set(orders); this.ordersLoaded.set(true); } },
      error: () => { if (epoch === this.epoch) this.error.set('Your orders could not be loaded. Please try again.'); } }));
  }
  count(product: string) { return this.cart().lines.find(line => line.productId === product)?.quantity ?? 0; }
  productName(product: string) { return this.products().find(item => item.id === product)?.name ?? 'Saved product'; }
  orderLabel(order: Order) { return order.orderNumber ? `Order #${order.orderNumber}` : 'Order'; }
  backToShop() { if (this.busy()) return; ++this.mutation; this.stopPolling(); this.selected.set(null); this.notice.set(''); this.error.set(''); }
  changeQuantity(product: string, quantity: number) {
    if (!this.canEdit() || !Number.isInteger(quantity) || quantity < 0 || quantity > 100) return;
    const lines = this.cart().lines.filter(line => line.productId !== product);
    if (quantity) lines.push({ productId: product, quantity });
    const epoch = this.epoch; this.busy.set(true); this.quote.set(null); this.error.set('');
    this.subscriptions.add(this.api.saveCart(this.account(), { version: this.cart().version, lines }).subscribe({ next: cart => {
      if (epoch !== this.epoch) return; this.cart.set(cart); this.busy.set(false);
    }, error: error => { if (epoch !== this.epoch) return; this.busy.set(false);
      this.error.set(error.status === 409 ? 'Your basket changed in another tab. We have reloaded it; check it before continuing.' : 'The basket could not be saved. We are checking its saved contents.'); this.loadCart(epoch); } }));
  }
  review() {
    if (!this.canEdit() || !this.quantity()) return;
    const epoch = this.epoch; this.busy.set(true); this.error.set('');
    this.subscriptions.add(this.api.quote(this.account(), this.cart().lines).subscribe({ next: quote => {
      if (epoch !== this.epoch) return; this.quote.set(quote); this.busy.set(false);
    }, error: () => { if (epoch === this.epoch) { this.busy.set(false); this.error.set('We could not confirm the current price and availability. Please try again.'); } } }));
  }
  placeOrder() {
    if (this.busy()) return;
    let pending = this.pending();
    if (!pending) {
      const quote = this.quote(); if (!quote || !this.canEdit()) return;
      pending = { key: crypto.randomUUID(), body: { lines: quote.lines.map(line => ({ productId: line.productId, quantity: line.quantity, priceVersion: line.priceVersion })), expectedTotalMinor: quote.totalMinor, method: 'CARD', cartVersion: this.cart().version, quoteToken: quote.quoteToken } };
      this.savePending(pending);
    }
    const epoch = this.epoch; this.busy.set(true); this.error.set('');
    this.subscriptions.add(this.api.checkout(this.account(), pending.body, pending.key).subscribe({ next: order => {
      if (epoch !== this.epoch) return; this.savePending(null); this.quote.set(null); this.busy.set(false);
      this.selected.set(order); this.notice.set('Your order is ready for payment.'); this.loadOrders(epoch); this.loadCart(epoch);
    }, error: error => {
      if (epoch !== this.epoch) return; this.busy.set(false);
      const code = error.error?.code;
      if (error.status === 409 && ['PRICE_CHANGED', 'OUT_OF_STOCK', 'PRODUCT_UNAVAILABLE', 'CART_CHANGED', 'IDEMPOTENCY_CONFLICT'].includes(code)) {
        this.savePending(null); this.quote.set(null); this.error.set(code === 'PRICE_CHANGED' ? 'The price changed. Review the updated basket before placing your order.' : code === 'CART_CHANGED' ? 'The basket changed in another tab. Review its saved contents before placing an order.' : 'An item is no longer available in this quantity. Update your basket.');
        if (code === 'CART_CHANGED' || code === 'IDEMPOTENCY_CONFLICT') {
          this.error.set('This saved checkout differs from another request. We have reloaded your basket and orders; review them before continuing.');
          this.loadCart(epoch); this.loadOrders(epoch);
        }
      } else if (error.status === 400) {
        this.savePending(null); this.quote.set(null); this.error.set('This checkout request was rejected. Review your saved basket and orders before continuing.');
        this.loadCart(epoch); this.loadOrders(epoch);
      } else this.error.set('The order result is not confirmed. Check the saved request below; do not place a second order.');
    } }));
  }
  openOrder(order: Order) { if (this.busy()) return; ++this.mutation; this.selected.set(order); this.error.set(''); this.refreshOrder(); this.startPolling(); }
  refreshOrder() {
    const order = this.selected(); if (!order || this.checking()) return;
    const epoch = this.epoch; const mutation = this.mutation; this.checking.set(true);
    this.subscriptions.add(this.api.order(order.id).subscribe({ next: updated => {
      if (epoch !== this.epoch) return; this.checking.set(false);
      if (this.selected()?.id !== updated.id || mutation !== this.mutation) return;
      this.selected.set(updated); this.orders.update(orders => orders.map(item => item.id === updated.id ? updated : item));
      if (['PAID', 'CANCELLED', 'EXPIRED', 'NEEDS_REVIEW'].includes(updated.status)) { this.stopPolling(); this.notice.set(''); }
    }, error: () => { if (epoch === this.epoch) { this.checking.set(false); this.notice.set('The latest result is unavailable. Your order stays saved; please do not pay again.'); } } }));
  }
  private startPolling() { this.stopPolling(); this.pollUntil = Date.now() + 600000; this.timer = setInterval(() => { if (Date.now() >= this.pollUntil) this.stopPolling(); else this.refreshOrder(); }, 3000); }
  private stopPolling() { if (this.timer) clearInterval(this.timer); this.timer = undefined; }
  paymentReceived(order: Order) { return (order.paymentSummary?.capturedMinor ?? 0) > 0 || order.payment?.status === 'CAPTURED'; }
  canPay(order: Order) { return !this.paymentReceived(order) && order.status === 'PAYMENT_PENDING' && ['CREATED', 'REQUIRES_ACTION'].includes(order.payment?.status) && !this.busy(); }
  pay() {
    const order = this.selected(); if (!order || !this.canPay(order)) return;
    if (order.payment.amountMinor !== order.totalMinor || order.payment.currency !== order.currency) {
      this.error.set('The payment amount differs from your order. Refresh the order before continuing.'); return;
    }
    ++this.mutation;
    if (order.payment.checkout && order.payment.status === 'REQUIRES_ACTION') { void this.openCheckout(order.payment); return; }
    const epoch = this.epoch; this.busy.set(true); this.error.set('');
    this.subscriptions.add(this.billing.submit(order.payment.id).subscribe({ next: payment => {
      if (epoch !== this.epoch) return; this.busy.set(false); this.selected.set({ ...order, payment }); this.startPolling();
      if (payment.checkout && payment.status === 'REQUIRES_ACTION') void this.openCheckout(payment);
    }, error: () => { if (epoch === this.epoch) {
      this.busy.set(false); this.selected.set({ ...order, payment: { ...order.payment, status: 'UNKNOWN' } });
      this.notice.set('Payment is being checked. Do not pay again.'); this.startPolling();
    } } }));
  }
  private async openCheckout(payment: ShopPayment) {
    if (!payment.checkout) return;
    const order = this.selected();
    if (!order || payment.amountMinor !== order.totalMinor || payment.currency !== order.currency
        || payment.checkout.amountMinor !== payment.amountMinor || payment.checkout.currency !== payment.currency) {
      this.error.set('The checkout amount differs from this payment. Check the latest order status.'); return;
    }
    const epoch = this.epoch; this.busy.set(true);
    try { await this.checkoutWindow.open(payment.checkout, () => this.checkPayment(payment.id, epoch)); }
    catch (error) { if (epoch === this.epoch) this.error.set(error instanceof Error ? error.message : 'Checkout is unavailable.'); }
    finally { if (epoch === this.epoch) { this.busy.set(false); this.startPolling(); } }
  }
  checkPayment(intent: string, epoch = this.epoch) {
    if (epoch === this.epoch) { ++this.mutation; this.notice.set('Checking the payment result.'); }
    // The provider modal can finish after navigation destroys this component. Complete the
    // bounded server lookup anyway; only the original active view may receive UI updates.
    this.api.reconcile(intent).pipe(timeout({ first: 15000 })).subscribe({ next: () => { if (epoch === this.epoch) this.refreshOrder(); },
      error: () => { if (epoch === this.epoch) this.notice.set('Payment confirmation is still pending. Please do not pay again.'); } });
  }
  cancel() {
    const order = this.selected(); if (!order || this.busy() || this.paymentReceived(order) || order.payment?.status !== 'CREATED') return;
    ++this.mutation;
    const epoch = this.epoch; this.busy.set(true); this.error.set('');
    this.subscriptions.add(this.api.cancel(order.id).subscribe({ next: updated => {
      if (epoch !== this.epoch) return; this.busy.set(false); this.selected.set(updated); this.loadOrders(epoch);
    }, error: () => { if (epoch === this.epoch) { this.busy.set(false); this.error.set('This order could not be cancelled. We are checking whether payment has already started.'); this.refreshOrder(); } } }));
  }
  retry() {
    const order = this.selected();
    if (!order || this.busy() || this.paymentReceived(order) || order.status !== 'PAYMENT_PENDING' || order.payment?.status !== 'FAILED') return;
    ++this.mutation;
    const storage = `${this.storageKey()}:retry:${order.payment.id}`;
    let key: string;
    try { key = sessionStorage.getItem(storage) ?? crypto.randomUUID(); sessionStorage.setItem(storage, key); }
    catch { this.error.set('The retry request could not be saved in this tab. Check your latest order status before trying again.'); return; }
    const epoch = this.epoch; this.busy.set(true); this.error.set('');
    this.subscriptions.add(this.billing.retry(order.payment.id, 'CARD', key).subscribe({ next: payment => {
      if (epoch !== this.epoch) return;
      try { sessionStorage.removeItem(storage); } catch { /* A completed server request remains safe to replay with its old key. */ }
      this.busy.set(false);
      this.selected.set({ ...order, payment }); this.notice.set('A new payment attempt is ready. Review the amount, then choose Pay.'); this.refreshOrder();
    }, error: error => {
      if (epoch !== this.epoch) return; this.busy.set(false);
      this.error.set(error.status === 409 ? 'A new payment attempt is not available. We are checking your latest order status.' : 'The retry result is not confirmed. Checking again uses this same saved request.'); this.refreshOrder();
    } }));
  }
  simulate() {
    const order = this.selected(); if (!order?.payment?.simulatorEnabled || this.busy() || this.paymentReceived(order)
      || order.status !== 'PAYMENT_PENDING' || !['REQUIRES_ACTION','PROCESSING'].includes(order.payment.status)) return;
    ++this.mutation;
    const epoch = this.epoch; this.busy.set(true);
    this.subscriptions.add(this.billing.simulate(order.payment.id, 'CAPTURED').subscribe({ next: () => {
      if (epoch === this.epoch) { this.busy.set(false); this.refreshOrder(); this.startPolling(); }
    }, error: () => { if (epoch === this.epoch) { this.busy.set(false); this.error.set('The simulated payment could not be completed.'); } } }));
  }
  explanation(order: Order) {
    if (order.paymentSummary?.status === 'REFUNDED') return 'Your refund has been issued. Your bank may take additional time to show it.';
    if (order.paymentSummary?.status === 'PARTIALLY_REFUNDED') return 'A partial refund has been issued. Your bank may take additional time to show it.';
    if (order.paymentSummary?.status === 'RETURNED') return 'This payment was returned. Please contact the store about your order.';
    if (order.status === 'PAID') return 'Payment received. Your order is confirmed.';
    if (order.status === 'NEEDS_REVIEW') return 'We are reviewing this order. Please do not pay again.';
    if (this.paymentReceived(order) && ['CANCELLED','EXPIRED'].includes(order.status)) return 'We received a payment for this closed order. The store is reviewing it. Please do not pay again.';
    if (this.paymentReceived(order)) return 'Payment received. We are finishing your order update.';
    if (order.status === 'CANCELLED') return 'This order was cancelled before payment.';
    if (order.status === 'EXPIRED') return 'The time to pay for this order has ended. You can start a new order from the shop.';
    if (order.payment?.status === 'CREATED') return 'Review the amount, then choose Pay.';
    if (order.payment?.status === 'REQUIRES_ACTION') return 'Complete payment in the secure checkout window.';
    if (order.payment?.status === 'FAILED') return 'This payment attempt failed. Check whether a new attempt is available.';
    return 'We are checking your payment. Please do not pay again.';
  }
  statusLabel(order: Order) {
    if (order.paymentSummary?.status === 'REFUNDED') return 'Refund issued';
    if (order.paymentSummary?.status === 'PARTIALLY_REFUNDED') return 'Partially refunded';
    if (order.paymentSummary?.status === 'RETURNED') return 'Payment returned';
    if (this.paymentReceived(order) && ['CANCELLED','EXPIRED'].includes(order.status)) return 'Payment received · under review';
    if (order.status === 'PAYMENT_PENDING' && this.paymentReceived(order)) return 'Payment received';
    if (order.status === 'PAYMENT_PENDING' && (order.paymentSummary?.status === 'ACTION_REQUIRED' || order.payment?.status === 'REQUIRES_ACTION')) return 'Complete payment';
    if (order.status === 'PAYMENT_PENDING' && (order.paymentSummary?.status === 'PENDING' || ['UNKNOWN','PROCESSING','SUBMITTING','CAPTURED'].includes(order.payment?.status))) return 'Confirming payment';
    if (order.status === 'PAYMENT_PENDING' && order.payment?.status === 'FAILED') return 'Payment unsuccessful';
    return ({PAID:'Paid',PAYMENT_PENDING:'Awaiting payment',CANCELLED:'Cancelled',EXPIRED:'Expired',NEEDS_REVIEW:'Under review'} as Record<string,string>)[order.status] ?? 'Updating';
  }
  ngOnDestroy() { ++this.epoch; this.stopPolling(); this.subscriptions.unsubscribe(); }
}
