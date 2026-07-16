import { Pipe, PipeTransform } from '@angular/core';
import { formatarHoraBr } from '../../core/helpers/date.helpers';

@Pipe({ name: 'fmtTime', standalone: true })
export class FmtTimePipe implements PipeTransform {
  transform(value: unknown): string {
    return formatarHoraBr(value);
  }
}
