import { Component, computed, signal } from '@angular/core';
import { MesAnoSelectorComponent, MesAno, anosNavegaveis } from './mes-ano-selector.component';

/** getDay(): 0=domingo … 6=sábado */
const DOW = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'];

interface DiaLinha {
  dia: number;
  /** "dd/mm - xxx" (ex.: "25/06 - qui") */
  rotulo: string;
}

/**
 * ESQUELETO da página "Registro manual de ponto" (card de /ponto).
 *
 * Mobile-first: em vez de uma tabela larga de colunas fixas, cada DIA é um
 * mini-grid próprio (5 colunas × 3 linhas) empilhado verticalmente. Navega-se
 * por mês/ano com o app-mes-ano-selector, no range de `anosNavegaveis` (relógio
 * local; o ano vizinho só entra no range em dezembro e janeiro — F37).
 *
 * Sem dados / sem backend ainda: os campos de hora e os botões de câmera são
 * placeholders visuais; os saldos mostram "±--:--". A persistência, o cálculo
 * de saldo (regra Secullum validada) e a foto entram nas próximas etapas.
 */
@Component({
  selector: 'app-registro-manual-ponto',
  standalone: true,
  imports: [MesAnoSelectorComponent],
  template: `
    <section class="reg-manual">
      <!-- ═══ Seletor de mês/ano (centralizado; [F37]: dez ↔ jan navegáveis) ═══ -->
      <app-mes-ano-selector [anos]="anosSeletor" (mudou)="onMesAno($event)" />

      <!-- ═══ Lista de dias (1 mini-grid por dia) ═══ -->
      <div class="dias">
        @for (l of dias(); track l.dia) {
          <div class="dia-card">
            <div class="col-dia">{{ l.rotulo }}</div>

            <div class="cel ent1"><span class="lbl">Ent. 1</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel sai1"><span class="lbl">Saí. 1</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel ent2"><span class="lbl">Ent. 2</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>
            <div class="cel sai2"><span class="lbl">Saí. 2</span>
              <input class="hora" inputmode="numeric" maxlength="5" placeholder="--:--" autocomplete="off"></div>

            @for (cam of cams; track cam.cls) {
              <button type="button" class="cam {{ cam.cls }}" [attr.aria-label]="cam.aria"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>
            }

            <div class="resumo total"><span class="lbl">Total dia</span><strong>±--:--</strong></div>
            <div class="resumo banco"><span class="lbl">Banco</span><strong>±--:--</strong></div>
          </div>
        }

        <!-- linha extra 1: em branco -->
        <div class="linha-branca"></div>

        <!-- linha extra 2: TOTAIS -->
        <div class="totais-card">
          <div class="totais-lbl">TOTAIS</div>
          <div class="resumo total"><span class="lbl">Total dia</span><strong>±--:--</strong></div>
          <div class="resumo banco"><span class="lbl">Banco</span><strong>±--:--</strong></div>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .reg-manual { margin: 0 auto; max-width: 680px; }

    /* ── Card de um dia (5 colunas × 3 linhas) ── */
    .dia-card {
      display: grid;
      grid-template-columns: minmax(52px, 0.8fr) 1fr 1fr 1fr 1fr;
      grid-template-rows: auto auto auto;
      gap: 4px 6px;
      border: 1px solid var(--border); border-radius: 10px;
      padding: 8px; margin-bottom: 8px;
    }
    .col-dia {
      grid-column: 1; grid-row: 1 / 4;
      display: flex; align-items: center;
      font-weight: 700; font-size: .8rem; color: var(--primary);
      line-height: 1.15; word-break: break-word;
    }
    .ent1 { grid-column: 2; grid-row: 1; }
    .sai1 { grid-column: 3; grid-row: 1; }
    .ent2 { grid-column: 4; grid-row: 1; }
    .sai2 { grid-column: 5; grid-row: 1; }
    .cam1 { grid-column: 2; grid-row: 2; }
    .cam2 { grid-column: 3; grid-row: 2; }
    .cam3 { grid-column: 4; grid-row: 2; }
    .cam4 { grid-column: 5; grid-row: 2; }
    .total { grid-column: 2 / 4; grid-row: 3; }
    .banco { grid-column: 4 / 6; grid-row: 3; }

    /* rótulo + campo de hora: lado a lado quando cabe; quebra (empilha) no celular */
    .cel { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 4px; }
    .lbl { font-size: .62rem; font-weight: 600; color: #64748b; white-space: nowrap; }
    .hora {
      flex: 1 1 46px; min-width: 46px; width: 100%;
      height: 30px; text-align: center; font-variant-numeric: tabular-nums;
      border: 1px solid var(--border); border-radius: 6px; padding: 0 2px; font-size: .9rem;
    }
    .cam {
      justify-self: stretch; height: 28px; cursor: pointer;
      border: 1px solid var(--border); border-radius: 6px; background: #f8fafc; color: #475569;
      display: flex; align-items: center; justify-content: center; padding: 0;
    }
    .cam:hover { background: var(--row-hover); }
    .cam svg { display: block; width: 17px; height: 17px; }

    .resumo {
      display: flex; align-items: center; justify-content: space-between; gap: 6px;
      background: #f1f5f9; border-radius: 6px; padding: 4px 8px;
      font-size: .78rem;
    }
    .resumo .lbl { color: #475569; }
    .resumo strong { font-variant-numeric: tabular-nums; color: var(--text); }

    /* ── Linhas extras ── */
    .linha-branca { height: 14px; }
    .totais-card {
      display: grid;
      grid-template-columns: minmax(52px, 0.8fr) 1fr 1fr 1fr 1fr;
      gap: 4px 6px; align-items: center;
      border: 1px solid var(--border); border-radius: 10px;
      padding: 8px; background: #eef2f7; font-weight: 700;
    }
    .totais-lbl { grid-column: 1; font-size: .82rem; color: var(--primary); letter-spacing: .03em; }
    .totais-card .total { grid-column: 2 / 4; }
    .totais-card .banco { grid-column: 4 / 6; }
  `],
})
export class RegistroManualPontoComponent {
  readonly cams = [
    { cls: 'cam1', aria: 'Foto Ent. 1' },
    { cls: 'cam2', aria: 'Foto Saí. 1' },
    { cls: 'cam3', aria: 'Foto Ent. 2' },
    { cls: 'cam4', aria: 'Foto Saí. 2' },
  ];

  /** Ano/mês correntes; atualizados pelo app-mes-ano-selector (relógio local). */
  private hoje = new Date();
  ano = signal<number>(this.hoje.getFullYear());
  mes = signal<number>(this.hoje.getMonth() + 1);
  /** Anos ofertados no seletor (F37/C14): a virada do ano só se abre em dezembro e janeiro. */
  readonly anosSeletor = anosNavegaveis(this.hoje);

  /** Uma linha por dia do mês, no formato "dd/mm - xxx". */
  dias = computed<DiaLinha[]>(() => {
    const ano = this.ano();
    const m = this.mes();
    const qtd = new Date(ano, m, 0).getDate();
    const out: DiaLinha[] = [];
    for (let d = 1; d <= qtd; d++) {
      const dt = new Date(ano, m - 1, d);
      const dd = String(d).padStart(2, '0');
      const mm = String(m).padStart(2, '0');
      out.push({ dia: d, rotulo: `${dd}/${mm} - ${DOW[dt.getDay()]}` });
    }
    return out;
  });

  onMesAno(e: MesAno): void { this.ano.set(e.ano); this.mes.set(e.mes); }
}
