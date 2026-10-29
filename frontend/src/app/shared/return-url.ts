/** Only return to known application pages after sign-in. Never accept an external URL. */
export function safeReturnUrl(value: string | null | undefined): string {
  return value && /^\/(?:shop|orders|finance|invoices|reconciliation)(?:[?#][^\r\n\\]*)?$/.test(value) ? value : '/shop';
}
