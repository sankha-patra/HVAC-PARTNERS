import { safeReturnUrl } from './return-url';

describe('Sign-in return destination', () => {
  it('preserves customer order links including the previous email link', () => {
    expect(safeReturnUrl('/orders')).toBe('/orders');
    expect(safeReturnUrl('/shop#orders')).toBe('/shop#orders');
    expect(safeReturnUrl('/finance')).toBe('/finance');
  });
  it('rejects external, unknown and escaped destinations', () => {
    for (const value of ['https://other.example', '//other.example', '/\\other.example', '/admin', '/login', '/orders/../../login', '/orders#x\nhttps://other.example']) expect(safeReturnUrl(value)).toBe('/shop');
  });
});
