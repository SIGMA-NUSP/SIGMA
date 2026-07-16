import { Component, computed, EventEmitter, Input, Output, signal } from '@angular/core';
import { sameDay, startOfDay } from '../../core/helpers/date.helpers';

/**
 * Estado por-dia fornecido pelo host no modo multisseleção (Q24/C-3.2): quem
 * decide seleção, desabilitação, a marca "×" e o rótulo é o pai (ex.: banco de
 * horas). Retornar null/undefined para um dia = sem override (comportamento base).
 */
export interface DiaEstado {
  desabilitado?: boolean;   // soma-se ao min/max (C-3.3: dias bloqueados do backend)
  selecionado?: boolean;    // no modo multi, a seleção vem daqui (não do _selecionado)
  marcado?: boolean;        // C-3.2: retângulo com "×"
  badge?: string;           // texto curto opcional sob o número
  rotulo?: string;          // title / aria-label do dia
}

interface DiaCelula {
  data: Date;
  dia: number;
  noMes: boolean;
  hoje: boolean;
  selecionado: boolean;
  desabilitado: boolean;
  marcado: boolean;
  badge?: string;
  rotulo?: string;
}

@Component({
  selector: 'app-mini-calendario',
  standalone: true,
  template: `
    <div class="cal">
      <div class="cal-header">
        <button class="cal-nav" (click)="prevMes()" [disabled]="!podeIrPrev()" aria-label="Mês anterior">‹</button>
        <span class="cal-titulo">{{ tituloMes() }}</span>
        <button class="cal-nav" (click)="nextMes()" [disabled]="!podeIrNext()" aria-label="Próximo mês">›</button>
      </div>
      <div class="cal-grid">
        @for (h of headers; track h) {
          <div class="cal-cabecalho">{{ h }}</div>
        }
        @for (d of dias(); track d.data.getTime()) {
          <button
            class="cal-dia"
            [class.fora-mes]="!d.noMes"
            [class.hoje]="d.hoje"
            [class.selecionado]="d.selecionado"
            [class.com-marca]="multiSelecao"
            [disabled]="d.desabilitado"
            [attr.title]="d.rotulo || null"
            [attr.aria-label]="d.rotulo || null"
            (click)="selecionar(d)">
            <span class="cal-dia-num">{{ d.dia }}</span>
            @if (multiSelecao) {
              <span class="cal-marca" [class.marcado]="d.marcado" aria-hidden="true">@if (d.marcado) { × }</span>
            }
            @if (d.badge) { <span class="cal-badge">{{ d.badge }}</span> }
          </button>
        }
      </div>
    </div>
  `,
  styles: [`
    .cal {
      display:inline-block; background:var(--card); border:1px solid var(--border);
      border-radius:var(--radius); padding:12px; user-select:none;
    }
    .cal-header {
      display:flex; align-items:center; justify-content:space-between; gap:8px;
      margin-bottom:8px;
    }
    .cal-titulo { font-weight:600; font-size:.95rem; text-transform:capitalize; }
    .cal-nav {
      background:transparent; border:1px solid var(--border); border-radius:6px;
      width:28px; height:28px; cursor:pointer; font-size:1.1rem; line-height:1;
      &:hover:not(:disabled) { background:var(--row-hover); }
      &:disabled { opacity:.3; cursor:not-allowed; }
    }
    .cal-grid {
      display:grid; grid-template-columns:repeat(7, 1fr); gap:2px;
    }
    .cal-cabecalho {
      text-align:center; font-size:.7rem; font-weight:600;
      color:var(--muted); padding:4px 0;
    }
    .cal-dia {
      background:transparent; border:none; cursor:pointer;
      width:32px; height:32px; border-radius:6px;
      font-size:.85rem; font-variant-numeric: tabular-nums;
      transition:background .1s;
      &:hover:not(:disabled) { background:var(--row-hover); }
      &.fora-mes { color:var(--muted); opacity:.4; }
      &.hoje { font-weight:700; box-shadow:inset 0 0 0 1px var(--primary); }
      &.selecionado { background:var(--primary); color:#fff; }
      &.selecionado.hoje { box-shadow:none; }
      &:disabled { opacity:.25; cursor:not-allowed; }
    }
    /* Modo multisseleção (banco de horas): empilha número + retângulo "×". Só ativa
       com [multiSelecao]=true, então a agenda (single) fica byte-a-byte a de hoje. */
    .cal-dia.com-marca {
      display:flex; flex-direction:column; align-items:center; justify-content:center;
      gap:3px; width:32px; height:auto; min-height:44px; padding:4px 0;
    }
    .cal-marca {
      width:14px; height:14px; border-radius:3px; border:1px solid var(--border);
      display:flex; align-items:center; justify-content:center; font-size:.8rem; line-height:1;
    }
    .cal-marca.marcado { background:var(--primary); color:#fff; border-color:var(--primary); }
    .cal-badge { font-size:.6rem; color:var(--muted); line-height:1; }
  `],
})
export class MiniCalendarioComponent {
  @Input() set valorSelecionado(v: Date | null) {
    if (v) {
      this._selecionado.set(startOfDay(v));
      this._mesExibido.set(new Date(v.getFullYear(), v.getMonth(), 1));
    }
  }
  @Input() min: Date | null = null;
  @Input() max: Date | null = null;

  @Output() dataSelecionada = new EventEmitter<Date>();

  /** Multisseleção (banco de horas): a seleção/estado por-dia vem do host via [estadoDia]. Default: single (agenda). */
  @Input() multiSelecao = false;

  /** Callback de estado por-dia (Q24). Espelhado em signal p/ o computed reagir a troca de referência da função. */
  @Input() set estadoDia(fn: ((d: Date) => DiaEstado | null | undefined) | null) {
    this._estadoDia.set(fn ?? null);
  }
  private _estadoDia = signal<((d: Date) => DiaEstado | null | undefined) | null>(null);

  protected readonly headers = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'];

  private _selecionado = signal<Date>(startOfDay(new Date()));
  private _mesExibido = signal<Date>(new Date(new Date().getFullYear(), new Date().getMonth(), 1));

  protected tituloMes = computed(() => {
    const d = this._mesExibido();
    return d.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  });

  protected podeIrPrev = computed(() => {
    if (!this.min) return true;
    const m = this._mesExibido();
    const prev = new Date(m.getFullYear(), m.getMonth() - 1, 1);
    const minStart = new Date(this.min.getFullYear(), this.min.getMonth(), 1);
    return prev >= minStart;
  });

  protected podeIrNext = computed(() => {
    if (!this.max) return true;
    const m = this._mesExibido();
    const next = new Date(m.getFullYear(), m.getMonth() + 1, 1);
    const maxStart = new Date(this.max.getFullYear(), this.max.getMonth(), 1);
    return next <= maxStart;
  });

  protected dias = computed<DiaCelula[]>(() => {
    const m = this._mesExibido();
    const sel = this._selecionado();
    const hoje = startOfDay(new Date());
    const estadoFn = this._estadoDia();

    // Primeiro dia da grade = domingo da semana que contém o dia 1 do mês
    const primeiroDoMes = new Date(m.getFullYear(), m.getMonth(), 1);
    const inicio = new Date(primeiroDoMes);
    inicio.setDate(inicio.getDate() - inicio.getDay());

    const result: DiaCelula[] = [];
    for (let i = 0; i < 42; i++) {
      const d = new Date(inicio);
      d.setDate(inicio.getDate() + i);
      const est = estadoFn ? estadoFn(d) : null;
      result.push({
        data: d,
        dia: d.getDate(),
        noMes: d.getMonth() === m.getMonth(),
        hoje: sameDay(d, hoje),
        // single (agenda): seleção interna; multi: o host decide via estadoDia
        selecionado: this.multiSelecao ? !!est?.selecionado : sameDay(d, sel),
        desabilitado: this.foraDoLimite(d) || !!est?.desabilitado,
        marcado: !!est?.marcado,
        badge: est?.badge,
        rotulo: est?.rotulo,
      });
    }
    return result;
  });

  protected prevMes(): void {
    const m = this._mesExibido();
    this._mesExibido.set(new Date(m.getFullYear(), m.getMonth() - 1, 1));
  }

  protected nextMes(): void {
    const m = this._mesExibido();
    this._mesExibido.set(new Date(m.getFullYear(), m.getMonth() + 1, 1));
  }

  protected selecionar(d: DiaCelula): void {
    if (d.desabilitado) return;
    if (this.multiSelecao) {
      // Não mexe em _selecionado/_mesExibido (senão o calendário "pularia" ao
      // (des)marcar um dia fora do mês). O host trata o emit como toggle do dia.
      this.dataSelecionada.emit(d.data);
      return;
    }
    this._selecionado.set(startOfDay(d.data));
    this._mesExibido.set(new Date(d.data.getFullYear(), d.data.getMonth(), 1));
    this.dataSelecionada.emit(d.data);
  }

  private foraDoLimite(d: Date): boolean {
    if (this.min && d < startOfDay(this.min)) return true;
    if (this.max && d > startOfDay(this.max)) return true;
    return false;
  }

}
