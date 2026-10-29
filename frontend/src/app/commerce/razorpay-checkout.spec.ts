import { RazorpayCheckout } from './razorpay-checkout';

describe('Razorpay modal ownership', () => {
  let options: Record<string, any>;
  const config = { provider: 'RAZORPAY' as const, keyId: 'rzp_test_public', orderId: 'order_fixture', amountMinor: 100, currency: 'INR' };
  beforeEach(() => { window.Razorpay = class { constructor(value: Record<string, unknown>) { options = value; } open() {} }; });
  afterEach(() => { delete window.Razorpay; });
  it('keeps the operation open until the payment window closes and checks only once', async () => {
    let resolved = false; const check = vi.fn();
    const operation = new RazorpayCheckout().open(config, check).then(() => { resolved = true; });
    await Promise.resolve(); await Promise.resolve(); expect(resolved).toBe(false); expect(check).not.toHaveBeenCalled();
    options['handler'](); await operation; expect(resolved).toBe(true); expect(check).toHaveBeenCalledTimes(1);
    options['modal'].ondismiss(); expect(check).toHaveBeenCalledTimes(1);
  });
  it('dismissal requests server confirmation without a browser claim that money was captured', async () => {
    const check = vi.fn(); const operation = new RazorpayCheckout().open(config, check);
    await Promise.resolve(); options['modal'].ondismiss(); await operation;
    expect(check).toHaveBeenCalledWith();
  });
  it('refuses live keys before opening a payment window', async () => {
    await expect(new RazorpayCheckout().open({ ...config, keyId: 'rzp_live_forbidden' }, vi.fn())).rejects.toThrow('test checkout');
  });
});
