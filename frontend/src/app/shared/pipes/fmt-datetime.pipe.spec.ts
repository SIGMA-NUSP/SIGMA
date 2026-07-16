import { FmtDateTimePipe } from './fmt-datetime.pipe';

describe('FmtDateTimePipe', () => {
  it('transform — delega a formatarDataHoraBr (ISO para dd/mm/aaaa HH:MM)', () => {
    expect(new FmtDateTimePipe().transform('2026-07-16T14:30:00')).toBe('16/07/2026 14:30');
  });

  it('transform — valor falsy retorna "--"', () => {
    expect(new FmtDateTimePipe().transform(undefined)).toBe('--');
  });
});
