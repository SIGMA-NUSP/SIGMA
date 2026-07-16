// Helpers de data/hora — fonte única do projeto (consolidação da Etapa 6 do plano de refatoração).
// As funções mortas hojeDdMm/hojeAgendaLabel foram removidas na Etapa 1 (E3-4).

/** True se `a` e `b` caem no mesmo dia do calendário (ignora hora). */
export function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}

/** Cópia de `d` com o horário zerado (00:00:00.000). */
export function startOfDay(d: Date): Date {
  const r = new Date(d);
  r.setHours(0, 0, 0, 0);
  return r;
}

/** Formata um Date como 'YYYY-MM-DD' (data local). */
export function toISODate(d: Date): string {
  const ano = d.getFullYear();
  const mes = String(d.getMonth() + 1).padStart(2, '0');
  const dia = String(d.getDate()).padStart(2, '0');
  return `${ano}-${mes}-${dia}`;
}

/** Formata um Date como 'HH:MM:SS' (hora local). */
export function hhmmss(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** Extrai a parte de data (YYYY-MM-DD) de um valor 'YYYY-MM-DD[ T]...'; `fallback` quando vazio. */
export function extractDate(raw: string, fallback = ''): string {
  if (!raw) return fallback;
  return raw.split(' ')[0].split('T')[0];
}

/** Extrai HH:MM de um valor de horário 'HH:MM[:SS]'. */
export function extractTime(raw: string): string {
  if (!raw) return '';
  const parts = raw.split(':');
  return parts.length >= 2 ? `${parts[0]}:${parts[1]}` : raw;
}

/** Duração 'H:MM:SS' entre dois horários 'HH:MM:SS'; '-' se inválida ou não-positiva. */
export function duracaoHms(inicio: string, termino: string): string {
  if (!inicio || !termino) return '-';
  const toSec = (t: string) => {
    const p = t.split(':');
    return (+p[0]) * 3600 + (+p[1]) * 60 + (+p[2] || 0);
  };
  const diff = toSec(termino) - toSec(inicio);
  if (diff <= 0) return '-';
  const h = Math.floor(diff / 3600);
  const m = Math.floor((diff % 3600) / 60);
  const s = diff % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** Formata data ISO como dd/mm/aaaa para exibição; '--' se vazio. */
export function formatarDataBr(value: unknown): string {
  if (!value) return '--';
  const s = String(value);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : s;
}

/**
 * Data ISO como 'Dia-da-semana, dd/mm/aaaa' (ex.: 'Quinta-feira, 16/07/2026');
 * '--' se vazio. Constrói o Date com componentes LOCAIS — nunca `new Date(iso)`,
 * que interpreta 'YYYY-MM-DD' como UTC e desloca o dia da semana.
 */
export function formatarDataExtensoBr(value: unknown): string {
  if (!value) return '--';
  const s = String(value);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return s;
  const dt = new Date(+m[1], +m[2] - 1, +m[3]);
  const ext = dt.toLocaleDateString('pt-BR',
    { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
  return ext.charAt(0).toUpperCase() + ext.slice(1);
}

/** Formata data-hora ISO como dd/mm/aaaa HH:MM para exibição; '--' se vazio. */
export function formatarDataHoraBr(value: unknown): string {
  if (!value) return '--';
  const s = String(value);
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : s;
}

/** Formata horário 'HH:MM[:SS]' como HH:MM para exibição; '--' se vazio. */
export function formatarHoraBr(value: unknown): string {
  if (!value) return '--';
  const s = String(value);
  if (s.includes(':') && s.length >= 5) return s.substring(0, 5);
  return s;
}
