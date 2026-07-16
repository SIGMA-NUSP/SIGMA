import { PaginationMeta } from '../models/user.model';
import { ColumnFilterState } from '../../shared/components/column-filter.component';

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

/** Nomes dos meses em pt-BR (índice 1-12; o índice 0 é vazio). */
export const MESES = ['', 'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'];

/** Retorna o nome do mês dado o número (1-12). */
export function mesNome(m: number): string {
  return MESES[m] || String(m);
}
