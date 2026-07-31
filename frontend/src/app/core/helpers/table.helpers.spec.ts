import {
  getDistinct, buildFilters, buildReportParams, MESES, mesNome,
  ANO_MINIMO_PONTO, competenciaMensal, competenciaMensalSlug, periodoFolha,
} from './table.helpers';
import { PaginationMeta } from '../models/user.model';
import { ColumnFilterState } from '../../shared/components/column-filter.component';

describe('getDistinct', () => {
  it('retorna os valores distintos da chave pedida', () => {
    const meta: PaginationMeta = {
      page: 1,
      limit: 10,
      total: 2,
      pages: 1,
      distinct: { sala: [{ value: '1', label: 'Plenário' }] },
    };
    expect(getDistinct(meta, 'sala')).toEqual([{ value: '1', label: 'Plenário' }]);
  });

  it('meta null retorna array vazio', () => {
    expect(getDistinct(null, 'sala')).toEqual([]);
  });

  it('meta sem distinct retorna array vazio', () => {
    const meta: PaginationMeta = { page: 1, limit: 10, total: 0, pages: 0 };
    expect(getDistinct(meta, 'sala')).toEqual([]);
  });

  it('chave inexistente no distinct retorna array vazio', () => {
    const meta: PaginationMeta = { page: 1, limit: 10, total: 0, pages: 0, distinct: {} };
    expect(getDistinct(meta, 'sala')).toEqual([]);
  });
});

describe('buildFilters', () => {
  it('inclui apenas campos truthy de cada estado', () => {
    const filters: Record<string, ColumnFilterState> = {
      nome: { text: 'joão' },
      sala: { values: ['1', '2'] },
      periodo: { range: { from: '2026-01-01', to: '2026-01-31' } },
    };
    expect(buildFilters(filters)).toEqual({
      nome: { text: 'joão' },
      sala: { values: ['1', '2'] },
      periodo: { range: { from: '2026-01-01', to: '2026-01-31' } },
    });
  });

  it('estado totalmente vazio não entra no resultado', () => {
    const filters: Record<string, ColumnFilterState> = { nome: {} };
    expect(buildFilters(filters)).toEqual({});
  });

  it('values null (todos selecionados) não entra no resultado', () => {
    const filters: Record<string, ColumnFilterState> = { sala: { values: null } };
    expect(buildFilters(filters)).toEqual({});
  });
});

describe('buildReportParams', () => {
  it('monta os params base sem search nem filters', () => {
    expect(buildReportParams('pdf', 'nome', 'asc')).toEqual({ format: 'pdf', sort: 'nome', direction: 'asc' });
  });

  it('acrescenta search quando truthy', () => {
    expect(buildReportParams('pdf', 'nome', 'asc', 'joão')).toEqual({
      format: 'pdf',
      sort: 'nome',
      direction: 'asc',
      search: 'joão',
    });
  });

  it('acrescenta filters como JSON quando buildFilters retorna não-vazio', () => {
    const filters: Record<string, ColumnFilterState> = { nome: { text: 'joão' } };
    const params = buildReportParams('pdf', 'nome', 'asc', undefined, filters);
    expect(params['filters']).toBe(JSON.stringify({ nome: { text: 'joão' } }));
  });

  it('filters cujo buildFilters resulte vazio não entra nos params', () => {
    const filters: Record<string, ColumnFilterState> = { nome: {} };
    const params = buildReportParams('pdf', 'nome', 'asc', undefined, filters);
    expect(params['filters']).toBeUndefined();
  });
});

describe('MESES', () => {
  it('índice 0 é vazio e 1-12 são os nomes dos meses', () => {
    expect(MESES[0]).toBe('');
    expect(MESES[1]).toBe('Janeiro');
    expect(MESES[12]).toBe('Dezembro');
    expect(MESES.length).toBe(13);
  });
});

describe('mesNome', () => {
  it('retorna o nome do mês para índice válido', () => {
    expect(mesNome(7)).toBe('Julho');
  });

  it('índice inválido cai no fallback String(m)', () => {
    expect(mesNome(13)).toBe('13');
    expect(mesNome(0)).toBe('0');
  });
});

describe('ANO_MINIMO_PONTO', () => {
  it('é 2026, o piso de seleção de período do módulo de ponto', () => {
    expect(ANO_MINIMO_PONTO).toBe(2026);
  });
});

describe('competenciaMensal', () => {
  it('janeiro: "2026-01-01" vira "Janeiro/2026"', () => {
    expect(competenciaMensal('2026-01-01')).toBe('Janeiro/2026');
  });

  it('dezembro: "2026-12-01" vira "Dezembro/2026"', () => {
    expect(competenciaMensal('2026-12-01')).toBe('Dezembro/2026');
  });

  it('junho: "2026-06-01" vira "Junho/2026"', () => {
    expect(competenciaMensal('2026-06-01')).toBe('Junho/2026');
  });
});

describe('competenciaMensalSlug', () => {
  it('mês com acento fica minúsculo e sem acento: março vira "marco-2026"', () => {
    expect(competenciaMensalSlug('2026-03-01')).toBe('marco-2026');
  });

  it('mês sem acento só fica minúsculo: junho vira "junho-2026"', () => {
    expect(competenciaMensalSlug('2026-06-01')).toBe('junho-2026');
  });
});

describe('periodoFolha', () => {
  it('MENSAL retorna a competência do início, ignorando fim e separador', () => {
    expect(periodoFolha('MENSAL', '2026-06-01', '2026-07-05', ' a ')).toBe('Junho/2026');
  });

  it('SEMANAL formata o intervalo em dd/mm/aaaa com o separador default " — "', () => {
    expect(periodoFolha('SEMANAL', '2026-06-01', '2026-06-07')).toBe('01/06/2026 — 07/06/2026');
  });

  it('SEMANAL aceita separador customizado " a "', () => {
    expect(periodoFolha('SEMANAL', '2026-06-01', '2026-06-07', ' a ')).toBe('01/06/2026 a 07/06/2026');
  });
});
