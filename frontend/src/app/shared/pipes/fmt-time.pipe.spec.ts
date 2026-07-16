import { FmtTimePipe } from './fmt-time.pipe';

describe('FmtTimePipe', () => {
  it('transform — delega a formatarHoraBr (corta para HH:MM)', () => {
    expect(new FmtTimePipe().transform('14:30:00')).toBe('14:30');
  });

  it('transform — valor falsy retorna "--"', () => {
    expect(new FmtTimePipe().transform('')).toBe('--');
  });
});
