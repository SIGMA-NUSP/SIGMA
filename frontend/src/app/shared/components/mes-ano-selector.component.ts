import { Component, computed, input, output, signal } from '@angular/core';
import { ANO_MINIMO_PONTO, MESES } from '../../core/helpers/table.helpers';

/** Par ano/mês (mes 1–12) emitido pelo seletor. */
export interface MesAno { ano: number; mes: number; }

/** Anos navegáveis desde a implantação do Ponto até o mês local corrente mais dois. */
export function anosNavegaveis(hoje: Date): number[] {
  const teto = new Date(hoje.getFullYear(), hoje.getMonth() + 2, 1);
  const quantidade = Math.max(0, teto.getFullYear() - ANO_MINIMO_PONTO + 1);
  return Array.from({ length: quantidade }, (_, i) => ANO_MINIMO_PONTO + i);
}

/** Posição absoluta de um mês, usada para comparar pares ano/mês. */
function ordinalMes(ano: number, mes: number): number { return ano * 12 + mes; }

/** Seletor reutilizável do período mensal do Ponto. */
@Component({
  selector: 'app-mes-ano-selector',
  standalone: true,
  imports: [],
  template: `
    <div class="seletor">
      <button type="button" class="nav-btn" (click)="voltarMes()"
              [disabled]="!podeVoltar()" aria-label="Mês anterior">‹</button>

      <!-- [selected] por option (e não [value] no <select>): no 1º render o binding do
           <select> roda antes de o @for criar as <option>s → cairia sempre na 1ª ("Janeiro"). -->
      <select class="sel sel-mes" (change)="onSelectMes($event)" aria-label="Mês">
        @for (m of meses; track $index) {
          <option [value]="$index + 1"
                  [selected]="mes() === $index + 1"
                  [disabled]="mesDesabilitado($index + 1)">{{ m }}</option>
        }
      </select>

      <select class="sel sel-ano" (change)="onSelectAno($event)" aria-label="Ano">
        @for (a of anos(); track a) {
          <option [value]="a" [selected]="ano() === a">{{ a }}</option>
        }
      </select>

      <button type="button" class="nav-btn" (click)="avancarMes()"
              [disabled]="!podeAvancar()" aria-label="Próximo mês">›</button>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .seletor {
      display: flex; align-items: center; justify-content: center;
      gap: 8px; margin: 4px 0 18px;
    }
    .nav-btn {
      width: 40px; height: 40px; border-radius: 999px;
      border: 1px solid var(--border); background: #fff; color: var(--primary);
      font-size: 1.4rem; line-height: 1; cursor: pointer; padding: 0;
      display: flex; align-items: center; justify-content: center;
    }
    .nav-btn:hover:not(:disabled) { background: var(--row-hover); }
    .nav-btn:disabled { opacity: .35; cursor: default; }
    .sel {
      height: 40px; border: 1px solid var(--border); border-radius: 8px;
      background: #fff; color: var(--text); font-size: .95rem; padding: 0 8px;
    }
    .sel-mes { min-width: 120px; }
    .sel-ano { min-width: 84px; }
  `],
})
export class MesAnoSelectorComponent {
  /** MESES é 1-based (índice 0 = ''); aqui itera a lista 0-based. */
  readonly meses = MESES.slice(1);

  /** O relógio local é capturado uma vez para manter estado, anos e teto coerentes. */
  private readonly hoje = new Date();
  private readonly teto = new Date(this.hoje.getFullYear(), this.hoje.getMonth() + 2, 1);
  private readonly inicioJanela = ordinalMes(ANO_MINIMO_PONTO, 1);
  private readonly fimJanela = ordinalMes(this.teto.getFullYear(), this.teto.getMonth() + 1);

  /** Anos ofertados no select, em ordem crescente. */
  anos = input<number[]>(anosNavegaveis(this.hoje));

  /** Emitido uma vez por mudança de mês ou ano. */
  mudou = output<MesAno>();

  ano = signal<number>(this.hoje.getFullYear());
  mes = signal<number>(this.hoje.getMonth() + 1);

  podeVoltar = computed(() => ordinalMes(this.ano(), this.mes()) > this.inicioJanela);
  podeAvancar = computed(() => ordinalMes(this.ano(), this.mes()) < this.fimJanela);

  /** Mantém todas as opções visíveis e desabilita as que ficam fora do range global. */
  mesDesabilitado(mes: number): boolean {
    const ordinal = ordinalMes(this.ano(), mes);
    return ordinal < this.inicioJanela || ordinal > this.fimJanela;
  }

  voltarMes(): void {
    if (!this.podeVoltar()) return;
    if (this.mes() > 1) { this.mes.update(v => v - 1); }
    else { this.mes.set(12); this.ano.update(v => v - 1); }
    this.emit();
  }

  avancarMes(): void {
    if (!this.podeAvancar()) return;
    if (this.mes() < 12) { this.mes.update(v => v + 1); }
    else { this.mes.set(1); this.ano.update(v => v + 1); }
    this.emit();
  }

  onSelectMes(ev: Event): void {
    const novoMes = Number((ev.target as HTMLSelectElement).value);
    if (!Number.isInteger(novoMes) || novoMes < 1 || novoMes > 12 || this.mesDesabilitado(novoMes)) return;
    this.mes.set(novoMes);
    this.emit();
  }

  /** Preserva o mês na troca de ano ou o aproxima do limite válido mais próximo. */
  onSelectAno(ev: Event): void {
    const novoAno = Number((ev.target as HTMLSelectElement).value);
    const limites = this.limitesDoAno(novoAno);
    if (!limites) return;
    const novoMes = Math.min(Math.max(this.mes(), limites.minimo), limites.maximo);
    this.ano.set(novoAno);
    this.mes.set(novoMes);
    this.emit();
  }

  private limitesDoAno(ano: number): { minimo: number; maximo: number } | null {
    if (!Number.isInteger(ano) || ano < ANO_MINIMO_PONTO || ano > this.teto.getFullYear()) return null;
    return {
      minimo: 1,
      maximo: ano === this.teto.getFullYear() ? this.teto.getMonth() + 1 : 12,
    };
  }

  private emit(): void { this.mudou.emit({ ano: this.ano(), mes: this.mes() }); }
}
