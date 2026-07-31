import { PaginationMeta } from '../models/user.model';
import { ColumnFilterState } from '../../shared/components/column-filter.component';
import { formatarDataBr } from './date.helpers';

/**
 * Extrai valores distintos do meta de paginação para um dado campo.
 * Uso: getDistinct(meta, 'sala') → [{value, label}, ...]
 */
export function getDistinct(meta: PaginationMeta | null, key: string): { value: string; label: string }[] {
  return (meta?.distinct as any)?.[key] ?? [];
}

/**
 * Converte o mapa de filtros do ColumnFilterComponent para o formato
 * esperado pelo ApiService (Record<string, unknown> serializado como JSON).
 */
export function buildFilters(filters: Record<string, ColumnFilterState>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, state] of Object.entries(filters)) {
    const f: Record<string, unknown> = {};
    if (state.values) f['values'] = state.values;
    if (state.range) f['range'] = state.range;
    if (state.text) f['text'] = state.text;
    if (Object.keys(f).length) result[key] = f;
  }
  return result;
}

/**
 * Monta params de relatório incluindo sort, direction, search e filters.
 */
export function buildReportParams(
  format: string,
  sort: string,
  direction: string,
  search?: string,
  filters?: Record<string, ColumnFilterState>,
): Record<string, string> {
  const params: Record<string, string> = { format, sort, direction };
  if (search) params['search'] = search;
  if (filters && Object.keys(filters).length) {
    const built = buildFilters(filters);
    if (Object.keys(built).length) params['filters'] = JSON.stringify(built);
  }
  return params;
}

/** Ano de implantação do módulo de ponto — piso de qualquer seleção de período da feature. */
export const ANO_MINIMO_PONTO = 2026;

/** Nomes dos meses em pt-BR (índice 1-12; o índice 0 é vazio). */
export const MESES = ['', 'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'];

/** Retorna o nome do mês dado o número (1-12). */
export function mesNome(m: number): string {
  return MESES[m] || String(m);
}

/** Competência de uma folha mensal ("Junho/2026") a partir da data ISO 'YYYY-MM-DD'. */
export function competenciaMensal(dataIso: string): string {
  return `${mesNome(parseInt(dataIso.slice(5, 7), 10))}/${dataIso.slice(0, 4)}`;
}

/** Competência em forma de slug para nome de arquivo ("junho-2026" — minúsculo, sem acento). */
export function competenciaMensalSlug(dataIso: string): string {
  return competenciaMensal(dataIso)
    .toLowerCase()
    .normalize('NFD').replace(/\p{M}/gu, '')
    .replace('/', '-');
}

/**
 * Período de exibição de uma folha/lote de ponto: mensal = competência ("Junho/2026");
 * semanal = intervalo de datas ("dd/mm/aaaa — dd/mm/aaaa", separador configurável).
 */
export function periodoFolha(tipo: string, dataInicio: string, dataFim: string, sep = ' — '): string {
  if (tipo === 'MENSAL') return competenciaMensal(dataInicio);
  return `${formatarDataBr(dataInicio)}${sep}${formatarDataBr(dataFim)}`;
}
