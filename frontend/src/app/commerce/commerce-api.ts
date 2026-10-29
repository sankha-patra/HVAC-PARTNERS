import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { BillingIntent } from '../billing/billing-api';

export interface CartLine { productId: string; quantity: number; }
export interface Cart { version: number; lines: CartLine[]; }
export interface Product { id: string; sku: string; name: string; category: string; unitPriceMinor: number; priceVersion: number; availableQty: number; }
export interface Catalog { currency: string; items: Product[]; page: number; pageSize: number; }
export interface QuoteLine extends CartLine { sku: string; description: string; unitPriceMinor: number; lineTotalMinor: number; priceVersion: number; }
export interface Quote { currency: string; totalMinor: number; lines: QuoteLine[]; quoteToken: string; }
export interface Checkout { lines: (CartLine & { priceVersion: number })[]; expectedTotalMinor: number; method: 'CARD'; cartVersion: number; quoteToken: string; }
/** Buyer checkout fields; invoice allocations and internal payment history are not part of this API. */
export type ShopPayment = Pick<BillingIntent, 'id' | 'currency' | 'amountMinor' | 'status' | 'provider' | 'method' | 'checkout' | 'checkoutUrl' | 'simulatorEnabled' | 'paymentReference' | 'retryOf' | 'createdAt'> & { refundedMinor?: number; updatedAt?: string };
export interface Order { id: string; orderNumber?: number; status: string; totalMinor: number; currency: string; lines: QuoteLine[]; payment: ShopPayment; holdUntil?: string;
  paymentSummary?: { status: 'NOT_PAID' | 'PENDING' | 'ACTION_REQUIRED' | 'PAID' | 'PARTIALLY_REFUNDED' | 'REFUNDED' | 'RETURNED'; capturedMinor: number; refundedMinor: number; returnedMinor?: number; currency: string }; }

@Injectable({ providedIn: 'root' })
export class CommerceApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/commerce`;
  private account(id: string) { return `${this.base}/accounts/${encodeURIComponent(id)}`; }
  catalog(account: string) { return this.http.get<Catalog>(`${this.account(account)}/catalog`); }
  cart(account: string) { return this.http.get<Cart>(`${this.account(account)}/cart`); }
  saveCart(account: string, cart: Cart) { return this.http.put<Cart>(`${this.account(account)}/cart`, cart); }
  quote(account: string, lines: CartLine[]) { return this.http.post<Quote>(`${this.account(account)}/quote`, { lines }); }
  checkout(account: string, body: Checkout, key: string) {
    return this.http.post<Order>(`${this.account(account)}/checkout`, body, { headers: { 'Checkout-Key': key } });
  }
  orders(account: string) { return this.http.get<Order[]>(`${this.account(account)}/orders`); }
  order(id: string) { return this.http.get<Order>(`${this.base}/orders/${encodeURIComponent(id)}`); }
  cancel(id: string) { return this.http.post<Order>(`${this.base}/orders/${encodeURIComponent(id)}/cancel`, {}); }
  reconcile(intent: string) { return this.http.post<BillingIntent>(`${environment.apiUrl}/billing/intents/${encodeURIComponent(intent)}/reconcile`, {}); }
}
