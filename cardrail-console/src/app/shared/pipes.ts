import { Pipe, PipeTransform, inject } from '@angular/core';
import { APP_CONFIG } from '../core/config/app-config';
import { formatUtc } from './datetime';
import { formatMinor } from './money';
import { displayMaskedPan } from './pan';
import { responseCodeLabel } from './response-codes';

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  private readonly locale = inject(APP_CONFIG).locale;

  transform(minor: number | null | undefined, currency: number | null | undefined): string {
    return formatMinor(minor, currency, this.locale);
  }
}

@Pipe({ name: 'utc' })
export class UtcPipe implements PipeTransform {
  transform(instant: string | null | undefined): string {
    return formatUtc(instant);
  }
}

@Pipe({ name: 'maskedPan' })
export class MaskedPanPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return displayMaskedPan(value);
  }
}

@Pipe({ name: 'responseLabel' })
export class ResponseLabelPipe implements PipeTransform {
  transform(code: string | null | undefined): string {
    return responseCodeLabel(code);
  }
}
