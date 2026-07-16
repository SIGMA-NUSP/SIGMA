import { asArray, formatarSaldoMin, truncate, formatEvento } from './format.helpers';

describe('asArray', () => {
  it('array retorna a mesma referência', () => {
    const arr = [1, 2, 3];
    expect(asArray(arr)).toBe(arr);
  });

  it('não-array retorna array vazio', () => {
    expect(asArray('não é array')).toEqual([]);
  });

  it('null/undefined retornam array vazio', () => {
    expect(asArray(null)).toEqual([]);
    expect(asArray(undefined)).toEqual([]);
  });
});

describe('formatarSaldoMin', () => {
  it('positivo formata com sinal "+"', () => {
    expect(formatarSaldoMin(3192)).toBe('+53:12');
  });

  it('negativo formata com sinal "-"', () => {
    expect(formatarSaldoMin(-123)).toBe('-02:03');
  });

  it('zero formata como "+00:00"', () => {
    expect(formatarSaldoMin(0)).toBe('+00:00');
  });

  it('menos-zero também formata como "+00:00"', () => {
    expect(formatarSaldoMin(-0)).toBe('+00:00');
  });
});

describe('truncate', () => {
  it('string maior que max corta e acrescenta "..."', () => {
    expect(truncate('abcdefgh', 4)).toBe('abcd...');
  });

  it('string menor ou igual a max retorna sem alteração', () => {
    expect(truncate('abc', 4)).toBe('abc');
  });

  it('null retorna string vazia', () => {
    expect(truncate(null, 4)).toBe('');
  });

  it('0 retorna string vazia', () => {
    expect(truncate(0, 4)).toBe('');
  });

  it('string vazia retorna string vazia', () => {
    expect(truncate('', 4)).toBe('');
  });
});

describe('formatEvento', () => {
  it('monta "SIGLA - Evento" quando comissão tem separador " - "', () => {
    const row = { nome_evento: 'Sessão Deliberativa', comissao_nome: 'CCJ - Comissão de Constituição e Justiça' };
    expect(formatEvento(row, 'nome_evento')).toBe('CCJ - Sessão Deliberativa');
  });

  it('comissão sem " - " usa a comissão inteira como sigla', () => {
    const row = { nome_evento: 'Reunião', comissao_nome: 'Plenário' };
    expect(formatEvento(row, 'nome_evento')).toBe('Plenário - Reunião');
  });

  it('comissão vazia retorna só o evento', () => {
    const row = { nome_evento: 'Reunião', comissao_nome: '' };
    expect(formatEvento(row, 'nome_evento')).toBe('Reunião');
  });

  it('evento vazio retorna string vazia mesmo com comissão preenchida', () => {
    const row = { nome_evento: '', comissao_nome: 'CCJ - Comissão' };
    expect(formatEvento(row, 'nome_evento')).toBe('');
  });

  it('eventoKey parametriza o campo de origem lido', () => {
    const row = { ultimo_evento: 'Audiência Pública', comissao_nome: 'CAE - Comissão de Assuntos Econômicos' };
    expect(formatEvento(row, 'ultimo_evento')).toBe('CAE - Audiência Pública');
  });
});
