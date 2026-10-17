import { Injectable, signal } from '@angular/core';
import { AppMessage } from './models';

/** Single liquid-glass message popup used across greeting, payments, notifications. */
@Injectable({ providedIn: 'root' })
export class MessageService {
  readonly message = signal<AppMessage | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  show(msg: AppMessage) {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.message.set(msg);
    const ms = msg.autoDismissMs;
    if (ms != null && ms > 0) {
      this.timer = setTimeout(() => this.dismiss(), ms);
    }
  }

  dismiss() {
    this.message.set(null);
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
