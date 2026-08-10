import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { HORA_RE, HoraMaskDirective } from '../directives/hora-mask.directive';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { periodoFolha } from '../../core/helpers/table.helpers';
import { ErroCargaComponent } from './erro-carga.component';
import { AjudaChatComponent } from './ajuda-chat.component';
import { ToastService } from './toast.component';

/**
 * Guia da tela quando a listagem das retificações falha (F63). Fail-closed: sem ela, a tela não
 * sabe quais dias JÁ foram retificados nem se o prazo corre — e o lote é tudo-ou-nada (C10), então
 * um único dia repetido derruba o envio inteiro.
 */
const GUIA_RETIFICACOES =
  'Não foi possível concluir a operação.';

/** Retificação já gravada de um dia, como o backend a devolve. */
interface RetifSalva {
  id: string;
  data: string;   // YYYY-MM-DD
  ent1: string | null; sai1: string | null; ent2: string | null; sai2: string | null;
  observacoes: string | null;
}

interface LinhaPonto {
  dia: string;
  ent1: string; sai1: string; ent2: string; sai2: string;
  total_dia: string; banco: string;
  // estado local da retificação (edição por dia)
  aberto?: boolean;
  r_ent1?: string; r_sai1?: string; r_ent2?: string; r_sai2?: string;
  observacoes?: string;
  ja_retificado?: boolean;
  /** Conteúdo da retificação gravada (dia já retificado). */
  retif?: RetifSalva;
  /** Área da retificação gravada expandida (leitura ou edição). */
  retifExpandida?: boolean;
  /** Campos habilitados para editar a retificação gravada. */
  editando?: boolean;
  /** PUT de edição em voo — trava os botões da linha. */
  salvandoEdicao?: boolean;
}

interface DadosFolha {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  linhas: LinhaPonto[];
}

/**
 * Retificação de ponto: mostra a folha publicada como tabela (7 colunas
 * espelhando o Secullum) e permite, por dia dentro do prazo, informar os
 * horários corretos (ao menos UM par Ent./Saí. completo — 2 ou 4 horários)
 * + observações (até 300 caracteres). As áreas aparecem em ordem cronológica.
 * Grava em UM POST de LOTE, transacional no backend — tudo-ou-nada.
 * Dia já retificado aparece acinzentado e expande com um clique, mostrando o
 * conteúdo gravado; dentro do prazo ele pode ser EDITADO (sobrescrita via PUT).
 * Dono via principal (gotcha 5).
 */
@Component({
  selector: 'app-ponto-retificar',
  standalone: true,
  imports: [FormsModule, RouterLink, HoraMaskDirective, ErroCargaComponent, AjudaChatComponent],
  template: `
    <h1>Retificação de Ponto</h1>
    <div class="topo-bar">
      <a [routerLink]="voltarLink" class="back-link">&larr; Voltar</a>
      <!-- F63: sem a listagem das retificações não há Salvar. A tela não saberia quais dias já
           foram retificados (nem se o prazo corre), e o lote é tudo-ou-nada — um dia repetido
           derruba o envio inteiro, inclusive os dias novos. Mesmo idioma do prazo: o botão some. -->
      @if (selecionadas().length > 0 && !enviado() && !bloqueado() && retificacoesCarregadas()) {
        <button class="btn-primary-custom salvar-top" (click)="salvar()" [disabled]="salvando()">
          {{ salvando() ? 'Salvando...' : 'Salvar' }}
        </button>
      }
    </div>

    @if (enviado()) {
      <div class="ok-box">Retificação Enviada</div>
    }

    @if (loading()) {
      <p class="text-muted-sm">Carregando folha...</p>
    } @else if (erro() && !dados()) {
      <div class="error-box">{{ erro() }}</div>
    } @else {
      <p class="text-muted-sm periodo">
        Folha {{ tipoLabel() }} — {{ periodoFolhaLabel() }}
      </p>
      <!-- O mês encerrado pela folha definitiva prevalece sobre o prazo: com ele não há mais o que
           retificar naquela competência, ainda que os 5 dias desta folha estejam correndo. -->
      @if (mesFechado()) {
        <div class="error-box">
          Não é possível retificar esta folha.
        </div>
      } @else if (limiteFmt()) {
        @if (prazoExpirado()) {
          <div class="error-box">Prazo de retificação encerrado em {{ limiteFmt() }}.</div>
        } @else {
          <p class="text-muted-sm prazo-aviso">Retificações permitidas até <strong>{{ limiteFmt() }}</strong>.</p>
        }
      }
      <!-- Canal PRÓPRIO da carga das retificações (F63) — separado do sinal "erro", que carrega as
           validações e a recusa do salvar(). Enquanto estiver preenchido, não há Salvar. O estado
           "verificando" existe para a tela não ficar muda no meio do retry (o Salvar e a caixa saem
           de cena juntos enquanto o GET voa). -->
      @if (carregandoRetificacoes()) {
        <p class="text-muted-sm">Verificando os dias já retificados e o prazo...</p>
      } @else if (erroRetificacoes()) {
        <app-erro-carga [mensagem]="erroRetificacoes()" (tentarNovamente)="recarregarRetificacoes()" />
      }
      @if (erro() && dados()) {
        <div class="error-box">{{ erro() }}</div>
      }

      <!-- Desktop: tabela -->
      <div class="table-container vista-desktop">
        <table class="data-table ponto-table">
          <thead><tr>
            <th>DIA</th>
            <th>ENT. 1</th><th>SAÍ. 1</th><th>ENT. 2</th><th>SAÍ. 2</th>
            <th>TOTALDIA</th><th>BANCO</th>
            <th style="width:96px; text-align:center">Retificar</th>
          </tr></thead>
          <tbody>
            @for (l of linhas(); track l.dia) {
              <tr [class.row-sel]="l.aberto" [class.row-retif]="l.ja_retificado"
                  [attr.title]="l.ja_retificado ? 'Ver retificação' : null"
                  (click)="l.ja_retificado ? toggleRetif(l) : null">
                <td><strong>{{ l.dia }}</strong></td>
                <td>{{ l.ent1 }}</td><td>{{ l.sai1 }}</td>
                <td>{{ l.ent2 }}</td><td>{{ l.sai2 }}</td>
                <td>{{ l.total_dia }}</td><td>{{ l.banco }}</td>
                <td style="text-align:center">
                  @if (l.ja_retificado) {
                    <span class="badge-retif" title="Dia já retificado">✓ Retificado</span>
                  } @else {
                    <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                            [disabled]="bloqueado()"
                            [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                      {{ l.aberto ? '−' : '+' }}
                    </button>
                  }
                </td>
              </tr>
              @if (l.aberto || (l.ja_retificado && l.retifExpandida)) {
                <tr class="accordion-row">
                  <td colspan="8">
                    <div class="retif-area">
                      <div class="retif-horas">
                        <label>Ent. 1<input appHoraMask [value]="l.r_ent1 || ''" (horaChange)="l.r_ent1 = $event"
                               [disabled]="!!l.ja_retificado && !l.editando"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Saí. 1<input appHoraMask [value]="l.r_sai1 || ''" (horaChange)="l.r_sai1 = $event"
                               [disabled]="!!l.ja_retificado && !l.editando"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Ent. 2<input appHoraMask [value]="l.r_ent2 || ''" (horaChange)="l.r_ent2 = $event"
                               [disabled]="!!l.ja_retificado && !l.editando"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Saí. 2<input appHoraMask [value]="l.r_sai2 || ''" (horaChange)="l.r_sai2 = $event"
                               [disabled]="!!l.ja_retificado && !l.editando"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                      </div>
                      <label class="obs-label">Observações</label>
                      <textarea [(ngModel)]="l.observacoes" rows="3" maxlength="300"
                                [disabled]="!!l.ja_retificado && !l.editando"></textarea>
                      @if (l.ja_retificado) {
                        <div class="retif-acoes">
                          @if (!l.editando) {
                            @if (!bloqueado()) {
                              <button class="btn-outline" (click)="iniciarEdicao(l)">Editar</button>
                            }
                          } @else {
                            <button class="btn-outline" (click)="cancelarEdicao(l)" [disabled]="!!l.salvandoEdicao">Cancelar</button>
                            <button class="btn-primary-custom" (click)="salvarEdicao(l)" [disabled]="!!l.salvandoEdicao">
                              {{ l.salvandoEdicao ? 'Salvando...' : 'Salvar' }}
                            </button>
                          }
                        </div>
                      }
                    </div>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>

      <!-- Mobile: um card por dia -->
      <div class="vista-mobile">
        @for (l of linhas(); track l.dia) {
          <div class="dia-card" [class.sel]="l.aberto" [class.retif]="l.ja_retificado"
               [attr.title]="l.ja_retificado ? 'Ver retificação' : null"
               (click)="l.ja_retificado ? toggleRetif(l) : null">
            <div class="col-dia">
              <strong>{{ l.dia }}</strong>
              @if (l.ja_retificado) {
                <span class="badge-retif" title="Dia já retificado">✓</span>
              } @else {
                <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                        [disabled]="bloqueado()"
                        [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                  {{ l.aberto ? '−' : '+' }}
                </button>
              }
            </div>

            @if (isStatus(l)) {
              <div class="status-cell">{{ l.ent1 }}</div>
            } @else {
              <div class="cel c-ent1"><span class="lbl">Ent. 1</span><span class="val" [class.hora]="!!l.ent1">{{ l.ent1 || '—' }}</span></div>
              <div class="cel c-sai1"><span class="lbl">Saí. 1</span><span class="val" [class.hora]="!!l.sai1">{{ l.sai1 || '—' }}</span></div>
              <div class="cel c-ent2"><span class="lbl">Ent. 2</span><span class="val" [class.hora]="!!l.ent2">{{ l.ent2 || '—' }}</span></div>
              <div class="cel c-sai2"><span class="lbl">Saí. 2</span><span class="val" [class.hora]="!!l.sai2">{{ l.sai2 || '—' }}</span></div>
            }

            <div class="resumo total"><span class="lbl">Total dia</span><span class="val" [class.hora]="!!l.total_dia">{{ l.total_dia || '—' }}</span></div>
            <div class="resumo banco"><span class="lbl">Banco</span><span class="val" [class.hora]="!!l.banco">{{ l.banco || '—' }}</span></div>
          </div>
          @if (l.aberto || (l.ja_retificado && l.retifExpandida)) {
            <div class="retif-area retif-area-mobile">
              <div class="retif-horas">
                <label>Ent. 1<input appHoraMask [value]="l.r_ent1 || ''" (horaChange)="l.r_ent1 = $event"
                       [disabled]="!!l.ja_retificado && !l.editando"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Saí. 1<input appHoraMask [value]="l.r_sai1 || ''" (horaChange)="l.r_sai1 = $event"
                       [disabled]="!!l.ja_retificado && !l.editando"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Ent. 2<input appHoraMask [value]="l.r_ent2 || ''" (horaChange)="l.r_ent2 = $event"
                       [disabled]="!!l.ja_retificado && !l.editando"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Saí. 2<input appHoraMask [value]="l.r_sai2 || ''" (horaChange)="l.r_sai2 = $event"
                       [disabled]="!!l.ja_retificado && !l.editando"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
              </div>
              <label class="obs-label">Observações</label>
              <textarea [(ngModel)]="l.observacoes" rows="3" maxlength="300"
                        [disabled]="!!l.ja_retificado && !l.editando"></textarea>
              @if (l.ja_retificado) {
                <div class="retif-acoes">
                  @if (!l.editando) {
                    @if (!bloqueado()) {
                      <button class="btn-outline" (click)="iniciarEdicao(l)">Editar</button>
                    }
                  } @else {
                    <button class="btn-outline" (click)="cancelarEdicao(l)" [disabled]="!!l.salvandoEdicao">Cancelar</button>
                    <button class="btn-primary-custom" (click)="salvarEdicao(l)" [disabled]="!!l.salvandoEdicao">
                      {{ l.salvandoEdicao ? 'Salvando...' : 'Salvar' }}
                    </button>
                  }
                </div>
              }
            </div>
          }
        }
      </div>
    }

    <!-- Chat de ajuda com IA (piloto) — mesmo manual do /ponto; se auto-esconde sem a flag 'ajudaIa' -->
    <app-ajuda-chat pagina="ponto-banco" titulo="Ajuda — Retificação de Ponto" />
  `,
  styles: [`
    .periodo { margin: 0 0 8px; }
    .prazo-aviso { margin: 0 0 16px; }
    .ponto-table td { font-variant-numeric: tabular-nums; }
    .row-sel td { background: #eff6ff; }
    .btn-pm {
      width: 30px; height: 30px; line-height: 1; font-size: 1.1rem; font-weight: 700;
      border: 1px solid var(--border); border-radius: 6px; background: #fff; color: var(--text);
      cursor: pointer; padding: 0;
    }
    .btn-pm:hover:not(:disabled) { background: var(--row-hover); }
    .btn-pm:disabled { opacity: .4; cursor: not-allowed; }
    .btn-pm.on { border-color: var(--primary); color: var(--primary); }
    .badge-retif { font-size: .72rem; font-weight: 700; color: #047857; white-space: nowrap; }

    /* Dia já retificado: linha acinzentada, clicável — expande o conteúdo gravado */
    .row-retif { cursor: pointer; }
    .row-retif td { background: #f4f4f5; }
    .row-retif:hover td { background: #e9e9eb; }
    .dia-card.retif { background: #f4f4f5; cursor: pointer; }
    .retif-acoes { display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px; }
    .retif-area input:disabled, .retif-area textarea:disabled {
      background: #fafafa; color: var(--text); opacity: 1; cursor: default;
    }

    /* Topo: Voltar à esquerda, Salvar à direita (na mesma linha) */
    .topo-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .topo-bar .back-link { margin-bottom: 0; }
    .salvar-top { margin-left: auto; }

    /* Área de retificação inline (abaixo do dia, no desktop e no celular) */
    .retif-area { margin: 0; }
    .retif-area label { display: block; font-weight: 600; font-size: .9375rem; margin-bottom: 6px; }
    .retif-area textarea { width: 100%; resize: vertical; box-sizing: border-box; }
    .retif-area-mobile { padding: 0 2px 4px; }

    /* 4 campos de hora (Ent.1/Saí.1/Ent.2/Saí.2) com máscara HH:MM */
    .retif-horas { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 10px; }
    .retif-horas label {
      display: flex; flex-direction: column; gap: 3px;
      font-weight: 600; font-size: .8rem; margin-bottom: 0;
    }
    .retif-horas input {
      height: 34px; text-align: center; font-variant-numeric: tabular-nums;
      border: 1px solid var(--border); border-radius: 6px; padding: 0 4px; font-size: .9rem;
    }
    .obs-label { display: block; font-weight: 600; font-size: .9375rem; margin-bottom: 6px; }

    /* aqui o box substitui o conteúdo da página — sem a margem superior da global */
    .error-box { margin-top: 0; }
    .ok-box {
      margin-top: 16px; background: #ecfdf5; color: #047857; border: 1px solid #6ee7b7;
      border-radius: 8px; padding: 12px 16px; font-weight: 600;
    }

    /* ───── Responsivo: tabela no desktop, cards por dia no celular ───── */
    .vista-mobile { display: none; }
    @media (max-width: 640px) {
      .vista-desktop { display: none; }
      .vista-mobile { display: flex; flex-direction: column; gap: 8px; }
      .retif-horas { grid-template-columns: repeat(2, 1fr); }

      .dia-card {
        display: grid;
        grid-template-columns: minmax(58px, .8fr) 1fr 1fr 1fr 1fr;
        grid-template-rows: auto auto;
        gap: 6px;
        border: 1px solid var(--border); border-radius: 10px; padding: 8px;
      }
      .dia-card.sel { background: #eff6ff; border-color: var(--primary); }
      .col-dia {
        grid-column: 1; grid-row: 1 / 3;
        display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 8px;
      }
      .col-dia strong { font-size: .8rem; color: var(--primary); line-height: 1.15; word-break: break-word; }

      .cel { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
      .cel .lbl { font-size: .6rem; font-weight: 600; color: #64748b; }
      .cel .val { font-size: .82rem; font-variant-numeric: tabular-nums; }
      /* Só o horário registrado ganha destaque; dia sem batida e rótulos ficam leves. */
      .dia-card .val.hora { font-weight: 700; }
      .c-ent1 { grid-column: 2; grid-row: 1; }
      .c-sai1 { grid-column: 3; grid-row: 1; }
      .c-ent2 { grid-column: 4; grid-row: 1; }
      .c-sai2 { grid-column: 5; grid-row: 1; }
      .status-cell {
        grid-column: 2 / 6; grid-row: 1; align-self: center;
        font-size: .85rem; font-weight: 600; color: var(--text);
      }
      .resumo {
        display: flex; align-items: center; justify-content: space-between; gap: 6px;
        background: #f1f5f9; border-radius: 6px; padding: 4px 8px; font-size: .76rem;
      }
      .resumo .lbl { color: #475569; }
      .resumo .val { font-variant-numeric: tabular-nums; color: var(--text); }
      .total { grid-column: 2 / 4; grid-row: 2; }
      .banco { grid-column: 4 / 6; grid-row: 2; }
    }
  `],
})
export class PontoRetificarComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private toast = inject(ToastService);

  dados = signal<DadosFolha | null>(null);
  linhas = signal<LinhaPonto[]>([]);
  loading = signal(true);
  erro = signal('');
  enviado = signal(false);
  salvando = signal(false);
  limiteFmt = signal<string | null>(null);
  prazoExpirado = signal(false);
  /** Competência da folha encerrada por folha mensal definitiva — bloqueio permanente, sem prazo. */
  mesFechado = signal(false);
  /** Nada de retificar nesta folha: ou o prazo venceu, ou o mês foi encerrado pela definitiva. */
  bloqueado = computed(() => this.prazoExpirado() || this.mesFechado());

  /** Canal de erro da carga das retificações (F63) — próprio, com retry; não é o `erro` do formulário. */
  erroRetificacoes = signal('');
  /** A listagem das retificações chegou: é o que destrava o envio (fail-closed — F63). */
  retificacoesCarregadas = signal(false);
  /** Carga em voo: sem isto, o Salvar e a caixa de erro somem JUNTOS e a tela fica muda no meio do retry. */
  carregandoRetificacoes = signal(false);
  /** Token de recência (idioma C9): dois cliques no "Tentar novamente" põem duas cargas em voo, e
   *  um erro velho não pode re-bloquear o Salvar que uma carga nova já destravou. */
  private seqRetificacoes = 0;

  /** Handle da saída pós-sucesso — sem ele, o timer navega DEPOIS de o usuário já ter saído (F40). */
  private timerSaida?: ReturnType<typeof setTimeout>;

  /** Linhas com área aberta — filtradas da lista, então já em ordem cronológica. */
  selecionadas = computed(() => this.linhas().filter(l => l.aberto));
  /** Todos (inclusive o admin, via card "Meu Ponto e Banco") chegam aqui pelo /ponto. */
  readonly voltarLink = '/ponto';

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('paginaId');
    if (!id) { this.erro.set('Folha não informada.'); this.loading.set(false); return; }
    this.api.get<any>(`/api/ponto/folha/${id}/dados`).subscribe({
      next: res => {
        const d: DadosFolha = res.data;
        this.dados.set(d);
        this.linhas.set((d.linhas || []).map(l => ({ ...l, aberto: false })));
        this.loading.set(false);
        this.carregarRetificacoes(id);
      },
      error: err => {
        this.erro.set(httpErrorMsg(err, 'Não foi possível carregar a folha.', ['error', 'message']));
        this.loading.set(false);
      },
    });
  }

  /** Retry da caixa de erro (F63) — o único gesto que destrava o Salvar depois de uma falha. */
  recarregarRetificacoes(): void {
    const id = this.dados()?.id ?? this.route.snapshot.paramMap.get('paginaId');
    if (id) this.carregarRetificacoes(id);
  }

  /**
   * Marca os dias já retificados e carrega o dia-limite / estado do prazo.
   *
   * FAIL-CLOSED (F63): a falha desta carga BLOQUEIA o envio. Antes ela era silenciosa (fail-open) —
   * num 500/timeout a folha ficava na tela com todos os dias livres e sem prazo; o usuário abria um
   * dia que já retificara, enviava, e levava o 400 "O dia … já foi retificado" sem ver retificação
   * nenhuma na tela. E, como o lote é tudo-ou-nada (C10), o dia novo enviado junto TAMBÉM não
   * gravava. É o sintoma que o C12 curou, ressuscitado por outra porta.
   */
  private carregarRetificacoes(paginaId: string): void {
    const seq = ++this.seqRetificacoes;
    this.erroRetificacoes.set('');
    this.retificacoesCarregadas.set(false);
    this.carregandoRetificacoes.set(true);
    this.api.get<any>(`/api/ponto/folha/${paginaId}/retificacoes`).subscribe({
      next: res => {
        if (seq !== this.seqRetificacoes) return;   // obsoleta: há uma carga mais nova em voo
        const d = res.data || {};
        this.limiteFmt.set(d.limite_fmt ?? null);
        this.prazoExpirado.set(!!d.prazo_expirado);
        this.mesFechado.set(!!d.mes_fechado);
        const porDia = new Map<string, RetifSalva>((d.retificacoes || []).map((r: RetifSalva) => [r.data, r]));
        this.linhas.update(ls => ls.map(l => {
          const retif = porDia.get(this.diaParaISO(l.dia));
          return { ...l, ja_retificado: !!retif, retif };
        }));
        this.carregandoRetificacoes.set(false);
        this.retificacoesCarregadas.set(true);
      },
      error: err => {
        if (seq !== this.seqRetificacoes) return;   // um erro velho não re-bloqueia o que o retry destravou
        this.carregandoRetificacoes.set(false);
        this.erroRetificacoes.set(erroCargaMsg(err, GUIA_RETIFICACOES));
      },
    });
  }

  tipoLabel(): string { return this.dados()?.tipo === 'MENSAL' ? 'mensal' : 'semanal'; }

  /** Período do cabeçalho: mensal = "Junho/2026"; semanal = "dd/mm/aaaa a dd/mm/aaaa". */
  periodoFolhaLabel(): string {
    const d = this.dados();
    return d ? periodoFolha(d.tipo, d.data_inicio, d.data_fim, ' a ') : '';
  }

  /**
   * Valida os horários digitados de um dia: formato HH:MM, ao menos o par 1 completo e pares
   * fechados (2 ou 4 horários). Devolve a mensagem de recusa ou null. Dia sem nenhum horário é
   * recusado — retificação vazia apagava a célula do dia na grade e na planilha da chefia.
   */
  private erroHoras(l: LinhaPonto): string | null {
    const horas = [l.r_ent1, l.r_sai1, l.r_ent2, l.r_sai2].map(h => (h || '').trim());
    for (const h of horas) {
      if (h && !HORA_RE.test(h)) return `Horário inválido em ${l.dia} (use HH:MM).`;
    }
    if (horas.every(h => !h)) {
      return `Horário de entrada e saída são obrigatórios.`;
    }
    const par1Completo = !!horas[0] && !!horas[1];   // ≥1 par completo — e o par 1 vem primeiro
    const par2Completo = !!horas[2] === !!horas[3];
    if (!par1Completo || !par2Completo) return `Preencha os pares Ent./Saí. completos em ${l.dia}.`;
    return null;
  }

  /** Dia de status (Feriado/Falta/DISPOSI/…) — tem letras nas células, não horas. */
  isStatus(l: LinhaPonto): boolean {
    return /[A-Za-zÀ-ÿ]/.test(l.ent1 || '');
  }

  /** "dd/mm/aa - diasem" → "20aa-mm-dd" (ISO); '' se não casar. */
  private diaParaISO(dia: string): string {
    const m = (dia || '').match(/^(\d{2})\/(\d{2})\/(\d{2})/);
    return m ? `20${m[3]}-${m[2]}-${m[1]}` : '';
  }

  /** "+" abre a área do dia; "−" remove (exclui o que foi digitado). */
  toggle(l: LinhaPonto): void {
    l.aberto = !l.aberto;
    if (!l.aberto) {
      l.r_ent1 = l.r_sai1 = l.r_ent2 = l.r_sai2 = '';
      l.observacoes = '';
    }
    this.linhas.set([...this.linhas()]);
  }

  /** Clique na linha retificada: expande/colapsa o conteúdo gravado (sempre a partir do servidor). */
  toggleRetif(l: LinhaPonto): void {
    if (!l.retif || l.salvandoEdicao) return;
    l.retifExpandida = !l.retifExpandida;
    l.editando = false;
    if (l.retifExpandida) this.preencherDaRetif(l);
    this.linhas.set([...this.linhas()]);
  }

  /** Leva o conteúdo gravado para os campos da área (leitura e ponto de partida da edição). */
  private preencherDaRetif(l: LinhaPonto): void {
    const r = l.retif!;
    l.r_ent1 = r.ent1 || '';
    l.r_sai1 = r.sai1 || '';
    l.r_ent2 = r.ent2 || '';
    l.r_sai2 = r.sai2 || '';
    l.observacoes = r.observacoes || '';
  }

  iniciarEdicao(l: LinhaPonto): void {
    l.editando = true;
    this.linhas.set([...this.linhas()]);
  }

  /** Descarta o que foi digitado e volta à leitura com os valores gravados. */
  cancelarEdicao(l: LinhaPonto): void {
    l.editando = false;
    this.preencherDaRetif(l);
    this.linhas.set([...this.linhas()]);
  }

  /** Sobrescreve a retificação do dia (PUT) — a área continua aberta, de volta à leitura. */
  salvarEdicao(l: LinhaPonto): void {
    if (l.salvandoEdicao || !l.retif) return;
    this.erro.set('');
    if (this.mesFechado()) { this.erro.set('Mês encerrado pela folha de ponto definitiva.'); return; }
    if (this.prazoExpirado()) { this.erro.set('Prazo de retificação encerrado.'); return; }
    const msg = this.erroHoras(l);
    if (msg) { this.erro.set(msg); return; }

    const horas = [l.r_ent1, l.r_sai1, l.r_ent2, l.r_sai2].map(h => (h || '').trim());
    l.salvandoEdicao = true;
    this.linhas.set([...this.linhas()]);
    this.api.put<any>(`/api/ponto/folha/${this.dados()!.id}/retificacoes/${l.retif.id}`, {
      ent1: horas[0] || null, sai1: horas[1] || null,
      ent2: horas[2] || null, sai2: horas[3] || null,
      observacoes: (l.observacoes || '').trim(),
    }).subscribe({
      next: res => {
        l.salvandoEdicao = false;
        l.editando = false;
        l.retif = res.data;
        this.preencherDaRetif(l);
        this.linhas.set([...this.linhas()]);
        this.toast.success('Retificação atualizada.');
      },
      error: err => {
        l.salvandoEdicao = false;
        this.linhas.set([...this.linhas()]);
        this.erro.set(erroCargaMsg(err, 'Não foi possível salvar a edição.'));
      },
    });
  }

  /**
   * Envia TODOS os dias abertos num único POST de lote — o backend grava numa transação só
   * (F39). A trava de duplo clique tem duas camadas: o `[disabled]` do botão (visível) e este
   * guard (que o `[disabled]` sozinho não garante — lição do C9).
   */
  salvar(): void {
    if (this.salvando()) return;
    this.erro.set('');
    if (this.mesFechado()) { this.erro.set('Mês encerrado pela folha de ponto definitiva.'); return; }
    if (this.prazoExpirado()) { this.erro.set('Prazo de retificação encerrado.'); return; }
    // F63: o botão já some sem a listagem carregada — este guard é a segunda camada (lição do C9:
    // esconder/desabilitar no template não é garantia de que o handler não roda).
    if (!this.retificacoesCarregadas()) {
      this.erro.set('Não foi possível concluir a operação.');
      return;
    }

    const payloads: Record<string, unknown>[] = [];
    for (const l of this.selecionadas().filter(x => !x.ja_retificado)) {
      const horas = [l.r_ent1, l.r_sai1, l.r_ent2, l.r_sai2].map(h => (h || '').trim());
      const obs = (l.observacoes || '').trim();

      // Dia aberto e INTOCADO (o "+" clicado por engano): não é retificação — fica fora do lote.
      if (horas.every(h => !h) && !obs) continue;

      // Um dia com observação e NENHUM horário é tentativa real de retificar: recusa visível,
      // nunca descarte silencioso — as mesmas regras de conteúdo da edição (erroHoras).
      const msg = this.erroHoras(l);
      if (msg) { this.erro.set(msg); return; }
      const data = this.diaParaISO(l.dia);
      if (!data) { this.erro.set(`Data inválida em ${l.dia}.`); return; }
      payloads.push({
        data,
        ent1: horas[0] || null, sai1: horas[1] || null,
        ent2: horas[2] || null, sai2: horas[3] || null,
        observacoes: obs,
      });
    }
    if (!payloads.length) { this.erro.set('Nenhum dia preenchido para retificar.'); return; }

    const id = this.dados()!.id;
    this.salvando.set(true);
    this.api.post<any>(`/api/ponto/folha/${id}/retificacoes`, { dias: payloads }).subscribe({
      next: () => {
        // segue travado: a tela já está de saída (o ok-box fica 1,4 s e o componente é destruído)
        this.enviado.set(true);
        this.timerSaida = setTimeout(() => this.router.navigateByUrl(this.voltarLink), 1400);
      },
      error: err => {
        this.salvando.set(false);
        // A recusa é uma TAREFA (qual dia consertar): a guia da tela — que o lote é tudo-ou-nada —
        // vem na frente, e o motivo do backend, que nomeia o dia, vem anexado. Fica na tela.
        this.erro.set(erroCargaMsg(err, 'Não foi possível concluir a operação.'));
        this.carregarRetificacoes(id);   // re-sincroniza os dias que porventura passaram
      },
    });
  }

  /** Sem isto, sair da tela dentro da janela de 1,4 s é arrancado de volta para /ponto (F40). */
  ngOnDestroy(): void {
    clearTimeout(this.timerSaida);
  }
}
