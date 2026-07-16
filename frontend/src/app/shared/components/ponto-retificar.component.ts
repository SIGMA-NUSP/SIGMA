import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../pipes/fmt-date.pipe';
import { HORA_RE, HoraMaskDirective } from '../directives/hora-mask.directive';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ErroCargaComponent } from './erro-carga.component';

/**
 * Guia da tela quando a listagem das retificações falha (F63). Fail-closed: sem ela, a tela não
 * sabe quais dias JÁ foram retificados nem se o prazo corre — e o lote é tudo-ou-nada (C10), então
 * um único dia repetido derruba o envio inteiro.
 */
const GUIA_RETIFICACOES =
  'Não foi possível verificar quais dias você já retificou nem o prazo desta folha. Enviar agora ' +
  'poderia derrubar o lote inteiro — tente novamente.';

interface LinhaPonto {
  dia: string;
  ent1: string; sai1: string; ent2: string; sai2: string;
  total_dia: string; banco: string;
  // estado local da retificação (edição por dia)
  aberto?: boolean;
  r_ent1?: string; r_sai1?: string; r_ent2?: string; r_sai2?: string;
  observacoes?: string;
  ja_retificado?: boolean;
}

interface DadosFolha {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  linhas: LinhaPonto[];
}

/**
 * Retificação de ponto (Bloco B-1): mostra a folha publicada como tabela (7
 * colunas espelhando o Secullum) e permite, por dia dentro do prazo, informar
 * os horários corretos (ao menos UM par Ent./Saí. completo — 2 ou 4 horários;
 * Q32 + F31) + observações (até 300 caracteres — F33). As áreas aparecem em
 * ordem cronológica. Grava em UM POST de LOTE, transacional no backend —
 * tudo-ou-nada (F39): sem edição nem exclusão na v1 (Q1), um dia gravado por
 * engano seria definitivo. Dias já retificados vêm marcados.
 * Dono via principal (gotcha 5).
 */
@Component({
  selector: 'app-ponto-retificar',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe, HoraMaskDirective, ErroCargaComponent],
  template: `
    <h1>Retificação de Ponto</h1>
    <div class="topo-bar">
      <a [routerLink]="voltarLink" class="back-link">&larr; Voltar</a>
      <!-- F63: sem a listagem das retificações não há Salvar. A tela não saberia quais dias já
           foram retificados (nem se o prazo corre), e o lote é tudo-ou-nada — um dia repetido
           derruba o envio inteiro, inclusive os dias novos. Mesmo idioma do prazo: o botão some. -->
      @if (selecionadas().length > 0 && !enviado() && !prazoExpirado() && retificacoesCarregadas()) {
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
        Folha {{ tipoLabel() }} — {{ dados()!.data_inicio | fmtDate }} a {{ dados()!.data_fim | fmtDate }}
      </p>
      @if (limiteFmt()) {
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
              <tr [class.row-sel]="l.aberto">
                <td><strong>{{ l.dia }}</strong></td>
                <td>{{ l.ent1 }}</td><td>{{ l.sai1 }}</td>
                <td>{{ l.ent2 }}</td><td>{{ l.sai2 }}</td>
                <td>{{ l.total_dia }}</td><td>{{ l.banco }}</td>
                <td style="text-align:center">
                  @if (l.ja_retificado) {
                    <span class="badge-retif" title="Dia já retificado">✓ Retificado</span>
                  } @else {
                    <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                            [disabled]="prazoExpirado()"
                            [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                      {{ l.aberto ? '−' : '+' }}
                    </button>
                  }
                </td>
              </tr>
              @if (l.aberto) {
                <tr class="accordion-row">
                  <td colspan="8">
                    <div class="retif-area">
                      <div class="retif-horas">
                        <label>Ent. 1<input appHoraMask [value]="l.r_ent1 || ''" (horaChange)="l.r_ent1 = $event"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Saí. 1<input appHoraMask [value]="l.r_sai1 || ''" (horaChange)="l.r_sai1 = $event"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Ent. 2<input appHoraMask [value]="l.r_ent2 || ''" (horaChange)="l.r_ent2 = $event"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                        <label>Saí. 2<input appHoraMask [value]="l.r_sai2 || ''" (horaChange)="l.r_sai2 = $event"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                      </div>
                      <label class="obs-label">Observações</label>
                      <textarea [(ngModel)]="l.observacoes" rows="3" maxlength="300"></textarea>
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
          <div class="dia-card" [class.sel]="l.aberto">
            <div class="col-dia">
              <strong>{{ l.dia }}</strong>
              @if (l.ja_retificado) {
                <span class="badge-retif" title="Dia já retificado">✓</span>
              } @else {
                <button class="btn-pm" [class.on]="l.aberto" (click)="toggle(l)"
                        [disabled]="prazoExpirado()"
                        [attr.aria-label]="l.aberto ? 'Remover retificação' : 'Retificar este dia'">
                  {{ l.aberto ? '−' : '+' }}
                </button>
              }
            </div>

            @if (isStatus(l)) {
              <div class="status-cell">{{ l.ent1 }}</div>
            } @else {
              <div class="cel c-ent1"><span class="lbl">Ent. 1</span><span class="val">{{ l.ent1 || '—' }}</span></div>
              <div class="cel c-sai1"><span class="lbl">Saí. 1</span><span class="val">{{ l.sai1 || '—' }}</span></div>
              <div class="cel c-ent2"><span class="lbl">Ent. 2</span><span class="val">{{ l.ent2 || '—' }}</span></div>
              <div class="cel c-sai2"><span class="lbl">Saí. 2</span><span class="val">{{ l.sai2 || '—' }}</span></div>
            }

            <div class="resumo total"><span class="lbl">Total dia</span><strong>{{ l.total_dia || '—' }}</strong></div>
            <div class="resumo banco"><span class="lbl">Banco</span><strong>{{ l.banco || '—' }}</strong></div>
          </div>
          @if (l.aberto) {
            <div class="retif-area retif-area-mobile">
              <div class="retif-horas">
                <label>Ent. 1<input appHoraMask [value]="l.r_ent1 || ''" (horaChange)="l.r_ent1 = $event"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Saí. 1<input appHoraMask [value]="l.r_sai1 || ''" (horaChange)="l.r_sai1 = $event"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Ent. 2<input appHoraMask [value]="l.r_ent2 || ''" (horaChange)="l.r_ent2 = $event"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
                <label>Saí. 2<input appHoraMask [value]="l.r_sai2 || ''" (horaChange)="l.r_sai2 = $event"
                       inputmode="numeric" maxlength="5" placeholder="HH:MM"></label>
              </div>
              <label class="obs-label">Observações</label>
              <textarea [(ngModel)]="l.observacoes" rows="3" maxlength="300"></textarea>
            </div>
          }
        }
      </div>
    }
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
      .resumo strong { font-variant-numeric: tabular-nums; color: var(--text); }
      .total { grid-column: 2 / 4; grid-row: 2; }
      .banco { grid-column: 4 / 6; grid-row: 2; }
    }
  `],
})
export class PontoRetificarComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  dados = signal<DadosFolha | null>(null);
  linhas = signal<LinhaPonto[]>([]);
  loading = signal(true);
  erro = signal('');
  enviado = signal(false);
  salvando = signal(false);
  limiteFmt = signal<string | null>(null);
  prazoExpirado = signal(false);

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
        const feitas = new Set<string>((d.retificacoes || []).map((r: any) => r.data));
        this.linhas.update(ls => ls.map(l => ({ ...l, ja_retificado: feitas.has(this.diaParaISO(l.dia)) })));
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

  /**
   * Envia TODOS os dias abertos num único POST de lote — o backend grava numa transação só
   * (F39). A trava de duplo clique tem duas camadas: o `[disabled]` do botão (visível) e este
   * guard (que o `[disabled]` sozinho não garante — lição do C9).
   */
  salvar(): void {
    if (this.salvando()) return;
    this.erro.set('');
    if (this.prazoExpirado()) { this.erro.set('Prazo de retificação encerrado.'); return; }
    // F63: o botão já some sem a listagem carregada — este guard é a segunda camada (lição do C9:
    // esconder/desabilitar no template não é garantia de que o handler não roda).
    if (!this.retificacoesCarregadas()) {
      this.erro.set('Não foi possível verificar os dias já retificados desta folha — recarregue antes de enviar.');
      return;
    }

    const payloads: Record<string, unknown>[] = [];
    for (const l of this.selecionadas().filter(x => !x.ja_retificado)) {
      const horas = [l.r_ent1, l.r_sai1, l.r_ent2, l.r_sai2].map(h => (h || '').trim());
      const obs = (l.observacoes || '').trim();
      const n = horas.filter(Boolean).length;

      // Dia aberto e INTOCADO (o "+" clicado por engano): não é retificação — fica fora do lote (F31).
      if (n === 0 && !obs) continue;

      for (const h of horas) {
        if (h && !HORA_RE.test(h)) { this.erro.set(`Horário inválido em ${l.dia} (use HH:MM).`); return; }
      }
      // ...mas um dia com observação e NENHUM horário é uma tentativa real de retificar: recusa
      // visível, nunca descarte silencioso. Retificação vazia apagava a célula do dia na grade e na
      // planilha da chefia — e, sem edição nem exclusão na v1, só o DBA desfazia (F31).
      if (n === 0) {
        this.erro.set(`Informe ao menos o par Ent. 1 / Saí. 1 em ${l.dia}: não é possível retificar um dia sem horários.`);
        return;
      }
      const par1Completo = !!horas[0] && !!horas[1];   // ≥1 par completo — e o par 1 vem primeiro (Q32)
      const par2Completo = !!horas[2] === !!horas[3];
      if (!par1Completo || !par2Completo) {
        this.erro.set(`Preencha os pares Ent./Saí. completos em ${l.dia}.`); return;
      }
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
        this.erro.set(erroCargaMsg(err, 'Não foi possível salvar a retificação — nenhum dia foi gravado.'));
        this.carregarRetificacoes(id);   // re-sincroniza os dias que porventura passaram
      },
    });
  }

  /** Sem isto, sair da tela dentro da janela de 1,4 s é arrancado de volta para /ponto (F40). */
  ngOnDestroy(): void {
    clearTimeout(this.timerSaida);
  }
}
