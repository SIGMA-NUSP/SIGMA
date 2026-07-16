import { Pipe, PipeTransform } from '@angular/core';
import { formatarDataHoraBr } from '../../core/helpers/date.helpers';

@Pipe({ name: 'fmtDateTime', standalone: true })
export class FmtDateTimePipe implements PipeTransform {
  transform(value: unknown): string {
    return formatarDataHoraBr(value);
  }
}
