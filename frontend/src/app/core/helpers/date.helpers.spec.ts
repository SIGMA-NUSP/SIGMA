import {
  sameDay,
  startOfDay,
  toISODate,
  hhmmss,
  extractDate,
  extractTime,
  duracaoHms,
  formatarDataBr,
  formatarDataExtensoBr,
  formatarDataHoraBr,
  formatarHoraBr,
} from './date.helpers';

describe('sameDay', () => {
  it('mesmo dia com horas diferentes retorna true', () => {
    expect(sameDay(new Date(2026, 6, 16, 1, 0, 0), new Date(2026, 6, 16, 23, 0, 0))).toBe(true);
  });

  it('dias diferentes retorna false', () => {
    expect(sameDay(new Date(2026, 6, 16), new Date(2026, 6, 17))).toBe(false);
  });
});

describe('startOfDay', () => {
  it('zera hora/minuto/segundo/ms mantendo ano/mês/dia', () => {
    const r = startOfDay(new Date(2026, 6, 16, 15, 30, 45, 500));
    expect(r.getFullYear()).toBe(2026);
    expect(r.getMonth()).toBe(6);
    expect(r.getDate()).toBe(16);
    expect(r.getHours()).toBe(0);
    expect(r.getMinutes()).toBe(0);
    expect(r.getSeconds()).toBe(0);
    expect(r.getMilliseconds()).toBe(0);
  });

  it('não muta o argumento original', () => {
    const original = new Date(2026, 6, 16, 15, 30, 0);
    startOfDay(original);
    expect(original.getHours()).toBe(15);
    expect(original.getMinutes()).toBe(30);
  });
});

describe('toISODate', () => {
  it('formata com componentes locais, sem shift UTC', () => {
    expect(toISODate(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});

describe('hhmmss', () => {
  it('formata HH:MM:SS locais com padding', () => {
    expect(hhmmss(new Date(2026, 0, 5, 9, 7, 3))).toBe('09:07:03');
  });
});

describe('extractDate', () => {
  it('corta no primeiro espaço', () => {
    expect(extractDate('2026-07-16 10:00:00')).toBe('2026-07-16');
  });

  it('corta no T', () => {
    expect(extractDate('2026-07-16T10:00:00')).toBe('2026-07-16');
  });

  it('sem espaço nem T retorna a própria data', () => {
    expect(extractDate('2026-07-16')).toBe('2026-07-16');
  });

  it('falsy retorna fallback default (vazio)', () => {
    expect(extractDate('')).toBe('');
  });

  it('falsy retorna fallback customizado', () => {
    expect(extractDate('', 'N/A')).toBe('N/A');
  });
});

describe('extractTime', () => {
  it('HH:MM:SS retorna HH:MM', () => {
    expect(extractTime('10:30:45')).toBe('10:30');
  });

  it('HH:MM retorna HH:MM', () => {
    expect(extractTime('10:30')).toBe('10:30');
  });

  it('sem separador ":" retorna o valor cru', () => {
    expect(extractTime('semColon')).toBe('semColon');
  });

  it('falsy retorna string vazia', () => {
    expect(extractTime('')).toBe('');
  });
});

describe('duracaoHms', () => {
  it('calcula H:MM:SS com horas sem padding', () => {
    expect(duracaoHms('08:00:00', '10:05:07')).toBe('2:05:07');
  });

  it('horas com 2 dígitos não ganham padding extra', () => {
    expect(duracaoHms('08:00:00', '20:00:00')).toBe('12:00:00');
  });

  it('diff igual a zero retorna "-"', () => {
    expect(duracaoHms('08:00:00', '08:00:00')).toBe('-');
  });

  it('diff negativo retorna "-"', () => {
    expect(duracaoHms('10:00:00', '09:00:00')).toBe('-');
  });

  it('início falsy retorna "-"', () => {
    expect(duracaoHms('', '10:00:00')).toBe('-');
  });

  it('término falsy retorna "-"', () => {
    expect(duracaoHms('10:00:00', '')).toBe('-');
  });
});

describe('formatarDataBr', () => {
  it('valor falsy retorna "--"', () => {
    expect(formatarDataBr(null)).toBe('--');
  });

  it('ISO formata para dd/mm/aaaa', () => {
    expect(formatarDataBr('2026-07-16')).toBe('16/07/2026');
  });

  it('sem match retorna a string original', () => {
    expect(formatarDataBr('lixo')).toBe('lixo');
  });
});

describe('formatarDataExtensoBr', () => {
  it('valor falsy retorna "--"', () => {
    expect(formatarDataExtensoBr(null)).toBe('--');
  });

  it('formata por extenso com 1ª letra maiúscula (depende do ICU pt-BR)', () => {
    expect(formatarDataExtensoBr('2026-07-16')).toBe('Quinta-feira, 16/07/2026');
  });

  it('sem match retorna a string original', () => {
    expect(formatarDataExtensoBr('lixo')).toBe('lixo');
  });
});

describe('formatarDataHoraBr', () => {
  it('valor falsy retorna "--"', () => {
    expect(formatarDataHoraBr('')).toBe('--');
  });

  it('separador T formata dd/mm/aaaa HH:MM', () => {
    expect(formatarDataHoraBr('2026-07-16T14:30:00')).toBe('16/07/2026 14:30');
  });

  it('separador espaço formata dd/mm/aaaa HH:MM', () => {
    expect(formatarDataHoraBr('2026-07-16 14:30:00')).toBe('16/07/2026 14:30');
  });
});

describe('formatarHoraBr', () => {
  it('valor falsy retorna "--"', () => {
    expect(formatarHoraBr('')).toBe('--');
  });

  it('corta HH:MM:SS para HH:MM via substring(0,5)', () => {
    expect(formatarHoraBr('14:30:00')).toBe('14:30');
  });

  it('string curta sem 5+ chars retorna o valor cru', () => {
    expect(formatarHoraBr('14:3')).toBe('14:3');
  });
});
