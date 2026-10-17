import { Pipe, PipeTransform } from '@angular/core';

/**
 * API LocalDateTime used to arrive without a timezone. JS treats that as local,
 * so 18:43 UTC showed as 6:43 PM IST instead of 12:13 AM.
 * Strings that already include Z / an offset are left alone.
 */
@Pipe({
  name: 'utcDate',
  standalone: true
})
export class UtcDatePipe implements PipeTransform {
  transform(value: string | Date | null | undefined): Date | null {
    if (value == null || value === '') {
      return null;
    }
    if (value instanceof Date) {
      return value;
    }
    const s = value.trim();
    if (/Z$|[+-]\d{2}:?\d{2}$/.test(s)) {
      return new Date(s);
    }
    return new Date(s + 'Z');
  }
}
