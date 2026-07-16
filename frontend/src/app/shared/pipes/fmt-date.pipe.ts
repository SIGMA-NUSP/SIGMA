import { Pipe, PipeTransform } from '@angular/core';
import { formatarDataBr } from '../../core/helpers/date.helpers';

@Pipe({ name: 'fmtDate', standalone: true })
export class FmtDatePipe implements PipeTransform {
  transform(value: unknown): string {
    return formatarDataBr(value);
  }
}
