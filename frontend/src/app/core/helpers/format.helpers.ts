/** Coerção segura de payloads dinâmicos para array (retorna [] quando não for array). */
export function asArray(v: unknown): any[] {
  return Array.isArray(v) ? v : [];
}

/** Saldo do banco de horas em minutos com sinal → '±HH:MM' (Q23; ex.: +53:12, -02:03, +00:00). */
export function formatarSaldoMin(minutos: number): string {
  const abs = Math.abs(minutos);
  const h = String(Math.floor(abs / 60)).padStart(2, '0');
  const m = String(abs % 60).padStart(2, '0');
  return `${minutos < 0 ? '-' : '+'}${h}:${m}`;
}

/** Trunca o texto em `max` caracteres, acrescentando reticências. */
export function truncate(v: unknown, max: number): string {
  const s = String(v || '');
  return s.length > max ? s.substring(0, max) + '...' : s;
}

/**
 * Formata o evento de uma linha de operação como "SIGLA - Evento" (a sigla é o
 * trecho de `comissao_nome` antes do primeiro " - "). `eventoKey` parametriza o
 * campo de origem: 'nome_evento' (home) ou 'ultimo_evento' (admin-operacao-audio).
 */
export function formatEvento(row: Record<string, unknown>, eventoKey: string): string {
  const evento = row[eventoKey] == null ? '' : String(row[eventoKey]);
  const comissao = row['comissao_nome'] == null ? '' : String(row['comissao_nome']);
  if (!evento) return '';
  if (!comissao) return evento;
  const idx = comissao.indexOf(' - ');
  const sigla = idx >= 0 ? comissao.substring(0, idx).trim() : comissao.trim();
  return sigla + ' - ' + evento;
}
