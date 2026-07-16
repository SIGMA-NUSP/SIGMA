import { FmtDatePipe } from './fmt-date.pipe';

describe('FmtDatePipe', () => {
  it('transform — delega a formatarDataBr (ISO para dd/mm/aaaa)', () => {
    expect(new FmtDatePipe().transform('2026-07-16')).toBe('16/07/2026');
  });

  it('transform — valor falsy retorna "--"', () => {
    expect(new FmtDatePipe().transform(null)).toBe('--');
  });
});
