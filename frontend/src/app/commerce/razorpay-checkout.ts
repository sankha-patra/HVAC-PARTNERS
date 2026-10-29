import { Injectable } from '@angular/core';
import { BillingIntent } from '../billing/billing-api';

type CheckoutConstructor = new (options: Record<string, unknown>) => { open(): void };
declare global { interface Window { Razorpay?: CheckoutConstructor; } }

/** Browser callbacks only request a server lookup; they never mark a payment paid. */
@Injectable({ providedIn: 'root' })
export class RazorpayCheckout {
  private loading?: Promise<void>;
  private load(): Promise<void> {
    if (window.Razorpay) return Promise.resolve();
    if (!this.loading) this.loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://checkout.razorpay.com/v1/checkout.js';
      script.async = true;
      const timer = window.setTimeout(() => { script.remove(); this.loading = undefined; reject(new Error('Checkout could not load. Please try again.')); }, 15000);
      script.onload = () => { window.clearTimeout(timer); if (window.Razorpay) resolve(); else { this.loading = undefined; reject(new Error('Checkout is unavailable.')); } };
      script.onerror = () => { window.clearTimeout(timer); script.remove(); this.loading = undefined; reject(new Error('Checkout could not load. Please try again.')); };
      document.head.appendChild(script);
    });
    return this.loading;
  }
  async open(config: NonNullable<BillingIntent['checkout']>, check: () => void): Promise<void> {
    if (config.provider !== 'RAZORPAY' || !config.keyId.startsWith('rzp_test_') || !/^order_[A-Za-z0-9]+$/.test(config.orderId)
        || !Number.isSafeInteger(config.amountMinor) || config.amountMinor <= 0 || config.currency !== 'INR') {
      throw new Error('This lab only accepts a valid Razorpay test checkout.');
    }
    await this.load();
    await new Promise<void>((resolve, reject) => {
      let finished = false;
      const done = () => { if (finished) return; finished = true; try { check(); } finally { resolve(); } };
      try {
        new window.Razorpay!({ key: config.keyId, order_id: config.orderId, amount: config.amountMinor,
          currency: config.currency, name: 'Commerce payment lab', description: 'Test payment',
          handler: done, modal: { ondismiss: done } }).open();
      } catch (error) { reject(error); }
    });
  }
}
