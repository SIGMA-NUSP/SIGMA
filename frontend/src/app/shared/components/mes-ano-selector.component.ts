import { Component, OnInit, computed, input, output, signal } from '@angular/core';
import { MESES } from '../../core/helpers/table.helpers';

/** Par ano/mês (mes 1–12) emitido pelo seletor. */
export interface MesAno { ano: number; mes: number; }

/**
 * Anos que o módulo Ponto oferta no seletor, em ordem CRESCENTE (contrato do input
 * `anos`, do qual `podeVoltar`/`podeAvancar` dependem via `anos[0]`/`anos[length-1]`).
 *
 * [F37][C14] A folha de um mês é publicada no início do mês SEGUINTE, e é a publicação
 * que abre a janela de 5 dias para retificar. Em janeiro, portanto, o mês que importa é
 * dezembro — folha recém-publicada, prazo correndo — e ofertar só o ano do relógio o
 * tornava inalcançável pela UI (seta ‹ desabilitada, `<select>` de ano com uma opção só).
 * Política do Douglas, em ANOS: dezembro → [ano, ano+1]; janeiro → [ano-1, ano];
 * fevereiro a novembro → [ano].
 *
 * [F70][C20] A navegação por setas fica contida na janela do componente: o ano corrente
 * INTEIRO + só o mês vizinho da virada (dez do ano anterior em janeiro; jan do ano seguinte em
 * dezembro) = 13 meses na virada. A política de ANOS (esta função) NÃO muda; quem limita os
 * MESES são os guards `podeVoltar`/`podeAvancar` (antes barravam só no 1º/último ANO do range,
 * e a navegação alcançava o ano vizinho INTEIRO — 24 meses).
 *
 * Função do relógio local do cliente (T-3.1) — nada hardcoded.
 */
export function anosNavegaveis(hoje: Date): number[] {
  const ano = hoje.getFullYear();
  const mes = hoje.getMonth() + 1;
  if (mes === 12) return [ano, ano + 1];
  if (mes === 1) return [ano - 1, ano];
  return [ano];
}

/** Posição absoluta de um mês (ano·12 + mês) — ordena pares (ano, mês) como um inteiro
 *  monotônico, para comparar a posição corrente às bordas da janela navegável (F70). */
function ordinalMes(ano: number, mes: number): number { return ano * 12 + mes; }

/**
 * Seletor de mês/ano reutilizável (‹ mês ›). O ano vem do relógio local do
 * cliente (T-3.1) — nunca hardcode. Por padrão oferta só o ano corrente; um
 * range maior pode ser passado em [anos] (ordem crescente — no módulo Ponto,
 * o de `anosNavegaveis`). Emite (mudou) a cada alteração de mês ou ano; a
 * navegação cruza o ano na virada, contida na janela de 13 meses (F70).
 */
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
          <option [value]="$index + 1" [selected]="mes() === $index + 1">{{ m }}</option>
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
export class MesAnoSelectorComponent implements OnInit {
  /** MESES é 1-based (índice 0 = ''); aqui itera a lista 0-based. */
  readonly meses = MESES.slice(1);

  /**
   * Anos ofertados no select (ordem crescente). Default: só o ano do relógio local.
   *
   * ⚠️ [F37][C14] O default É o comportamento que o F37 corrigiu: quem monta este seletor no
   * módulo Ponto DEVE passar `[anos]="anosNavegaveis(hoje)"` — senão, em janeiro, dezembro (a
   * folha no prazo) volta a ficar inalcançável. Os 3 consumidores atuais passam; um 4º que
   * esqueça reabre o defeito em silêncio (registrado como observação do C14).
   */
  anos = input<number[]>([new Date().getFullYear()]);

  /** Emitido a cada mudança de mês OU ano (setas e selects). */
  mudou = output<MesAno>();

  private hoje = new Date();
  ano = signal<number>(this.hoje.getFullYear());
  mes = signal<number>(this.hoje.getMonth() + 1);

  /** Ano do relógio local (T-3.1) — centro da janela navegável do F70; nunca hardcode. */
  private anoCorrente = this.hoje.getFullYear();

  /**
   * [F70][C20] Bordas da janela navegável: o ano corrente INTEIRO + só o mês vizinho da virada
   * (dez do ano anterior em janeiro; jan do ano seguinte em dezembro) → 13 meses na virada, 12
   * fora dela. Antes, `podeVoltar`/`podeAvancar` barravam só no 1º/último ANO do range e a
   * navegação alcançava o ano vizinho INTEIRO (24 meses): o banco de horas abria meses a um ano
   * de distância e o Configurar da grade escrevia em qualquer mês navegável. As bordas leem o
   * range (`anos`) só para saber se o vizinho é ofertado; o centro é sempre o relógio.
   */
  private inicioJanela = computed(() => {
    const anos = this.anos();
    return anos.length > 0 && anos[0] < this.anoCorrente
      ? ordinalMes(this.anoCorrente - 1, 12)   // dez do ano anterior
      : ordinalMes(this.anoCorrente, 1);        // jan do ano corrente
  });
  private fimJanela = computed(() => {
    const anos = this.anos();
    return anos.length > 0 && anos[anos.length - 1] > this.anoCorrente
      ? ordinalMes(this.anoCorrente + 1, 1)    // jan do ano seguinte
      : ordinalMes(this.anoCorrente, 12);       // dez do ano corrente
  });

  podeVoltar = computed(() => ordinalMes(this.ano(), this.mes()) > this.inicioJanela());
  podeAvancar = computed(() => ordinalMes(this.ano(), this.mes()) < this.fimJanela());

  /**
   * [F37][C14] Clampa o ano inicial ao range ofertado — SEM emitir `mudou`: o contrato
   * "não emite nada ao ser criado" está cimentado por teste, e os pais partem do próprio
   * relógio (o estado deles já é o do seletor). Sob a política de `anosNavegaveis` o clamp
   * nunca dispara — o range sempre contém o ano do relógio. Ele existe para um `[anos]` que
   * NÃO contenha esse ano: aí o estado interno divergia do `<select>` (nenhuma `<option>`
   * casava) e `podeVoltar`/`podeAvancar` mentiam, medindo o ano corrente contra um range
   * que não é o dele. Com o clamp, select e setas voltam a falar do mesmo ano.
   *
   * ⚠️ Alcance honesto: o clamp reconcilia o FILHO (select ↔ setas ↔ estado). Ele não avisa o
   * pai — não pode, sem emitir. Um pai que passe um `[anos]` sem o próprio ano exibido ficaria
   * com rótulo de um ano e dados de outro; sob esta política isso é inalcançável (nenhum dos 3
   * consumidores produz tal range), e por isso a defesa para aqui.
   */
  ngOnInit(): void {
    const anos = this.anos();
    if (anos.length === 0) return;
    this.ano.update(a => Math.min(Math.max(a, anos[0]), anos[anos.length - 1]));
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

  onSelectMes(ev: Event): void { this.mes.set(Number((ev.target as HTMLSelectElement).value)); this.emit(); }

  /**
   * [F69][C20] Trocar o ano pelo `<select>` salta para o mês da virada — o único mês navegável do
   * ano vizinho sob a janela do F70 (ano anterior → dezembro; ano seguinte → janeiro). Antes só
   * trocava o ano e mantinha o mês: em 05/01/2027 escolher 2026 caía em jan/2026 — 12 meses antes
   * de dezembro, a folha no prazo. Mesmo ano → mês intacto. `emit()` segue único e ao final (um
   * evento por gesto, `MesAno` já consistente).
   */
  onSelectAno(ev: Event): void {
    const novoAno = Number((ev.target as HTMLSelectElement).value);
    if (novoAno < this.ano()) this.mes.set(12);
    else if (novoAno > this.ano()) this.mes.set(1);
    this.ano.set(novoAno);
    this.emit();
  }

  private emit(): void { this.mudou.emit({ ano: this.ano(), mes: this.mes() }); }
}
