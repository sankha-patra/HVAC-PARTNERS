import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MessageService } from './message.service';

@Component({
  selector: 'app-glass-message',
  standalone: true,
  imports: [CommonModule, MatButtonModule],
  templateUrl: './glass-message.html',
  styleUrl: './glass-message.scss'
})
export class GlassMessageComponent {
  private readonly messages = inject(MessageService);
  readonly msg = this.messages.message;

  dismiss() {
    this.messages.dismiss();
  }

  detailsEntries(details?: Record<string, string>) {
    if (!details) {
      return [] as [string, string][];
    }
    return Object.entries(details);
  }
}
