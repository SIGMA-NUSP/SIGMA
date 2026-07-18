import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ClientPager } from '../../core/helpers/client-pager';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { SolicitacoesAdminComponent } from '../../shared/components/solicitacoes-admin.component';
import { GradeRetificacoesComponent } from '../../shared/components/grade-retificacoes.component';
import { AjudaChatComponent } from '../../shared/components/ajuda-chat.component';
import { ToastService } from '../../shared/components/toast.component';

type PessoaTipo = 'OPERADOR' | 'TECNICO' | 'ADMINISTRADOR';

interface Pessoa { id: string; nome: string; tipo: PessoaTipo; }

interface Pagina {
  id: string;
  numero_pagina: number;
  nome_extraido?: string;
  pessoa_id?: string;
  pessoa_tipo?: PessoaTipo;
  pessoa_nome?: string;
  status_match: 'AUTO' | 'MANUAL' | 'PENDENTE';
}

interface Lote {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  status: 'REVISAO' | 'PUBLICADO';
  total_paginas: number;
  pendentes: number;
  criado_em?: string;
  publicado_em?: string;
  paginas?: Pagina[];
  _exp?: boolean;        // linha expandida (acordeão)
  emitirAviso?: boolean; // checkbox "Emitir aviso" (client-side; ausente = marcado)
}

/** Uma pessoa atingida pela exclusão, como o backend a descreve (F59). */
interface PessoaAfetada {
  pessoa_id: string;
  nome: string;
  tipo: PessoaTipo;
  retificacoes_excluidas: number;
  /** "não muda" | "volta para a folha dd/mm/aaaa a dd/mm/aaaa" | "fica sem folha oficial — abertura 0" */
  reancora: string;
}

/**
 * O que a exclusão vai destruir — vem do endpoint de preview, contado no banco (F59). O modal de
 * confirmação renderiza ISTO: nada de texto genérico, porque as consequências mudam radicalmente de
 * item para item (um lote em revisão não apaga nada além dos PDFs; uma mensal publicada reabre o mês
 * e pode levar retificações e avisos junto).
 */
interface PreviewExclusao {
  escopo: 'LOTE' | 'PAGINA';
  lote: { id: string; tipo: string; data_inicio: string; data_fim: string; status: string; publicado: boolean };
  pagina: { id: string; numero_pagina: number; pessoa_nome?: string } | null;
  pessoas: PessoaAfetada[];
  paginas_excluidas: number;
  retificacoes_excluidas: number;
  avisos_destinatarios: string[];
  avisos_removidos: number;
  reabre_competencia: string | null;
  arquivos: number;
}

/** O item que o X mirou — guardado enquanto o modal está aberto (é dele que sai o DELETE). */
interface AlvoExclusao { lote: Lote; pagina?: Pagina; }

@Component({
  selector: 'app-admin-ponto',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe, ErroCargaComponent, PaginationComponent,
    SolicitacoesAdminComponent, GradeRetificacoesComponent, AjudaChatComponent],
  template: `
    <h1>Ponto e Banco de Horas</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <!-- ═══ Cards de navegação (mesmo padrão de /admin/form-edit) ═══ -->
    <div class="grid-cards cols-auto">
      <button class="card-custom card-pick" [class.active]="activeCard() === 'folhas'" (click)="selectCard('folhas')">
        <strong>Folhas de Ponto</strong><span class="text-muted-sm">Upload das folhas de ponto</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeCard() === 'retificacoes'" (click)="selectCard('retificacoes')">
        <strong>Retificações</strong><span class="text-muted-sm">Grade mensal por funcionário</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeCard() === 'banco'" (click)="selectCard('banco')">
        <strong>Banco de Horas</strong><span class="text-muted-sm">Solicitações</span>
      </button>
      <!-- Área pessoal do admin com folha (SERVIDOR_PUBLICO=0 — Q35/E9): navega p/ a página compartilhada /ponto -->
      @if (auth.temFolhaPonto()) {
        <button class="card-custom card-pick" routerLink="/ponto">
          <strong>Meu Ponto e Banco</strong><span class="text-muted-sm">Minhas folhas, banco e solicitações</span>
        </button>
      }
    </div>

    @if (activeCard() === 'folhas') {
    <!-- ═══ Upload ═══ -->
    <div class="card-custom" style="max-width:760px; margin:0 auto 24px">
      <h2 class="form-title">Enviar PDF</h2>

      <div class="form-grid">
        <div class="form-row">
          <label>Tipo *</label>
          <select [(ngModel)]="tipo" name="tipo">
            <option value="MENSAL">Mensal</option>
            <option value="SEMANAL">Semanal</option>
          </select>
        </div>
        <div class="form-row">
          <label>De *</label>
          <input type="date" [(ngModel)]="dataInicio" name="data_inicio">
        </div>
        <div class="form-row">
          <label>Até *</label>
          <input type="date" [(ngModel)]="dataFim" name="data_fim">
        </div>
      </div>
      <div class="form-row">
        <label>Arquivo PDF *</label>
        <input #fileInput type="file" accept="application/pdf" (change)="onFileSelect($event)">
      </div>

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div style="margin-top:16px">
        <button class="btn-primary-custom" [disabled]="uploading()" (click)="onUpload()">
          {{ uploading() ? 'Processando...' : 'Enviar e processar' }}
        </button>
      </div>
    </div>

    <!-- ═══ Lotes enviados (linha expande/contrai em acordeão) ═══ -->
    <section>
      <div class="section-header"><h2>Lotes enviados</h2></div>

      <!-- Canal de erro (C7/F50): sem a lista de pessoas os selects de vínculo ficam vazios —
           a falha é anunciada e o vínculo, bloqueado (nunca um select interativo sobre optgroups vazios). -->
      @if (erroPessoas()) {
        <div style="margin-bottom:12px">
          <app-erro-carga [mensagem]="erroPessoas()" (tentarNovamente)="loadPessoas()" />
        </div>
      }

      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th style="width:34px"></th>
            <th>Período</th><th style="width:110px">Tipo</th>
            <th style="width:90px; text-align:center">Páginas</th>
            <th style="width:110px; text-align:center">Pendentes</th>
            <th style="width:130px">Status</th>
            <th style="width:130px">Enviado em</th>
            <!-- Coluna do X: só existe para o master (a flag vem do backend; o 403 é a segurança) -->
            @if (podeExcluir()) { <th style="width:70px; text-align:center">Excluir</th> }
          </tr></thead>
          <tbody>
            @if (erroLotes()) {
              <!-- Canal de erro (C7/F50): "nenhum lote enviado" induziria ao reenvio de um PDF já processado -->
              <tr><td [attr.colspan]="colunasLote()">
                <app-erro-carga [mensagem]="erroLotes()" (tentarNovamente)="loadLotes()" />
              </td></tr>
            } @else if (lotes().length === 0) {
              <tr><td [attr.colspan]="colunasLote()" class="empty-state">{{ loadingLotes() ? 'Carregando...' : 'Nenhum lote enviado ainda.' }}</td></tr>
            } @else {
              @for (l of lotesPager.rows(); track l.id) {
                <tr class="row-clickable" (click)="toggleLote(l)">
                  <td><span class="btn-toggle">{{ l._exp ? '▼' : '▶' }}</span></td>
                  <td><strong>{{ l.data_inicio | fmtDate }} — {{ l.data_fim | fmtDate }}</strong></td>
                  <td>{{ l.tipo === 'MENSAL' ? 'Mensal' : 'Semanal' }}</td>
                  <td style="text-align:center">{{ l.total_paginas }}</td>
                  <td style="text-align:center" [style.color]="l.pendentes > 0 ? 'var(--color-red)' : 'var(--color-green)'">
                    <strong>{{ l.pendentes }}</strong>
                  </td>
                  <td>
                    @if (l.status === 'PUBLICADO') { <span class="badge-ok">Publicado</span> }
                    @else { <span class="badge-rev">Em revisão</span> }
                  </td>
                  <td>{{ l.criado_em | fmtDate }}</td>
                  @if (podeExcluir()) {
                    <td style="text-align:center">
                      <!-- stopPropagation: a linha inteira é o acordeão — sem ele o X também expandiria o lote -->
                      <button class="btn-x btn-x-lote" title="Excluir este lote"
                              [disabled]="exclusaoEmVoo(chaveLote(l))"
                              (click)="$event.stopPropagation(); abrirExclusaoLote(l)">✕</button>
                    </td>
                  }
                </tr>

                @if (l._exp) {
                  <tr class="accordion-row">
                    <td [attr.colspan]="colunasLote()">
                      @if (!l.paginas) {
                        <p class="text-muted-sm">Carregando páginas...</p>
                      } @else {
                        @if (l.status === 'REVISAO') {
                          <p class="text-muted-sm" style="margin:0 0 10px">
                            Confira o vínculo de cada página. Páginas <strong>pendentes</strong> não ficarão
                            visíveis a ninguém até serem vinculadas. Use “Ver PDF” em caso de dúvida.
                          </p>
                        } @else {
                          <p class="text-muted-sm" style="margin:0 0 10px">
                            Lote publicado em {{ l.publicado_em | fmtDate }}.
                          </p>
                        }

                        <table class="sub-table">
                          <thead><tr>
                            <th style="width:50px; text-align:center">Pág.</th>
                            <th style="width:120px">Vínculo</th>
                            <th>Operador / Técnico</th>
                            <th>Nome lido (dica)</th>
                            <th style="width:90px; text-align:center">PDF</th>
                            @if (podeExcluir()) { <th style="width:70px; text-align:center">Excluir</th> }
                          </tr></thead>
                          <tbody>
                            @for (p of l.paginas; track p.id) {
                              <tr>
                                <td style="text-align:center">{{ p.numero_pagina }}</td>
                                <td>
                                  @switch (p.status_match) {
                                    @case ('AUTO')   { <span class="badge-ok">Automático</span> }
                                    @case ('MANUAL') { <span class="badge-manual">Manual</span> }
                                    @default         { <span class="badge-falha">Pendente</span> }
                                  }
                                </td>
                                <td>
                                  @if (l.status === 'REVISAO') {
                                    <!-- [disabled] (C7/F50): sem pessoas carregadas, a única opção do select seria
                                         "— pendente —" — um clique inocente DESVINCULARIA a página. -->
                                    <select class="pessoa-select" [ngModel]="valorPessoa(p)" (ngModelChange)="onAssign(l, p, $event)" [name]="'pessoa-' + p.id"
                                            [disabled]="vinculoBloqueado()"
                                            [title]="vinculoBloqueado() ? 'Lista de pessoas indisponível — aguarde a carga ou tente novamente' : ''">
                                      <option value="">— pendente —</option>
                                      <optgroup label="Operadores">
                                        @for (o of operadores(); track o.id) {
                                          <option [value]="'OPERADOR:' + o.id">{{ o.nome }}</option>
                                        }
                                      </optgroup>
                                      <optgroup label="Técnicos">
                                        @for (t of tecnicos(); track t.id) {
                                          <option [value]="'TECNICO:' + t.id">{{ t.nome }}</option>
                                        }
                                      </optgroup>
                                      <optgroup label="Administradores">
                                        @for (a of administradores(); track a.id) {
                                          <option [value]="'ADMINISTRADOR:' + a.id">{{ a.nome }}</option>
                                        }
                                      </optgroup>
                                    </select>
                                  } @else {
                                    {{ p.pessoa_nome || '—' }}
                                    @if (p.pessoa_tipo) { <span class="text-muted-sm">({{ tipoLabel(p.pessoa_tipo) }})</span> }
                                  }
                                </td>
                                <td class="text-muted-sm">{{ p.nome_extraido || '—' }}</td>
                                <td style="text-align:center">
                                  <button class="btn-xs" (click)="preview(p)">Ver PDF</button>
                                </td>
                                @if (podeExcluir()) {
                                  <td style="text-align:center">
                                    <button class="btn-x btn-x-pagina" title="Excluir esta folha"
                                            [disabled]="exclusaoEmVoo(chavePagina(p))"
                                            (click)="abrirExclusaoPagina(l, p)">✕</button>
                                  </td>
                                }
                              </tr>
                            }
                          </tbody>
                        </table>

                        @if (l.status === 'REVISAO') {
                          <div style="display:flex; align-items:center; gap:12px; margin-top:12px">
                            <!-- [disabled] por LOTE (C9/F49): com um slot único de publicação, a resposta de um
                                 lote reabilitava o botão de OUTRO ainda em voo — e o reclique publicava o mesmo
                                 lote duas vezes. -->
                            <button class="btn-primary-custom btn-publicar" [disabled]="publicando(l.id)" (click)="publicar(l)">
                              {{ publicando(l.id) ? 'Publicando...' : 'Publicar lote' }}
                            </button>
                            <label class="aviso-ciente" style="margin:0" title="Avisa cada pessoa com folha no lote ao publicar">
                              <input type="checkbox" [checked]="l.emitirAviso !== false"
                                     (change)="l.emitirAviso = $any($event.target).checked">
                              Emitir aviso
                            </label>
                            @if (l.pendentes > 0) {
                              <span class="text-muted-sm" style="color:var(--color-red)">
                                {{ l.pendentes }} página(s) pendente(s) — ficarão indisponíveis se publicar agora.
                              </span>
                            }
                          </div>

                          <!-- Recusa da publicação (C9/F57): não é um aviso, é uma TAREFA — as recusas da
                               folha mensal nomeiam as pessoas cuja página precisa sair do lote. O toast some
                               em 12 s; esta caixa fica até a próxima tentativa. -->
                          @if (erroPublicacao()[l.id]) {
                            <div class="error-box" style="margin-top:10px">{{ erroPublicacao()[l.id] }}</div>
                          }
                        }
                      }
                    </td>
                  </tr>
                }
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="lotesPager.meta()" (pageChange)="lotesPager.onPage($event)" (limitChange)="lotesPager.onLimit($event)" />
    </section>
    }

    @if (activeCard() === 'retificacoes') {
      <app-grade-retificacoes />
    }

    @if (activeCard() === 'banco') {
      <app-solicitacoes-admin />
    }

    <!-- ═══ Confirmação da exclusão (F59) — as consequências REAIS daquele item, vindas do preview ═══
         Nada de texto genérico: o que morre num lote em revisão e o que morre numa mensal publicada
         não têm nada em comum, e é o segundo caso (retificações, avisos, âncora, mês reaberto) que o
         admin precisa enxergar ANTES de confirmar. -->
    @if (previewExclusao(); as pv) {
      <div class="modal-overlay" (click)="fecharExclusao()">
        <div class="modal-card card-custom" (click)="$event.stopPropagation()">
          <h2 class="modal-title">
            {{ pv.escopo === 'LOTE' ? 'Excluir o lote inteiro?' : 'Excluir esta folha?' }}
          </h2>

          <p class="alvo">
            <strong>{{ pv.lote.tipo === 'MENSAL' ? 'Mensal' : 'Semanal' }}
              {{ pv.lote.data_inicio | fmtDate }} — {{ pv.lote.data_fim | fmtDate }}</strong>
            <span class="text-muted-sm">({{ pv.lote.publicado ? 'publicado' : 'em revisão' }})</span>
            @if (pv.pagina) {
              <br><span class="text-muted-sm">Página {{ pv.pagina.numero_pagina }} — {{ pv.pagina.pessoa_nome || 'sem vínculo' }}</span>
            }
          </p>

          <p class="consequencias-titulo">O que será apagado:</p>
          <ul class="consequencias">
            <li>{{ pv.paginas_excluidas }} folha(s) e {{ pv.arquivos }} arquivo(s) PDF</li>
            <li>{{ pv.retificacoes_excluidas }} retificação(ões) registrada(s) nessa(s) folha(s)</li>
            <li>
              {{ pv.avisos_removidos }} aviso(s) pessoal(is) desta publicação
              @if (pv.avisos_destinatarios.length) {
                <span class="text-muted-sm">({{ pv.avisos_destinatarios.join(', ') }})</span>
              }
            </li>
          </ul>

          @if (pv.pessoas.length) {
            <p class="consequencias-titulo">Banco de horas:</p>
            <ul class="consequencias">
              @for (p of pv.pessoas; track p.pessoa_id) {
                <li>
                  <strong>{{ p.nome }}</strong>
                  <span class="text-muted-sm">— saldo {{ p.reancora }}</span>
                  @if (p.retificacoes_excluidas > 0) {
                    <span class="text-muted-sm">; {{ p.retificacoes_excluidas }} retificação(ões) perdida(s)</span>
                  }
                </li>
              }
            </ul>
          }

          @if (pv.reabre_competencia) {
            <p class="reabre">A competência <strong>{{ pv.reabre_competencia }}</strong> volta a aceitar publicação.</p>
          }

          <p class="irreversivel"><strong>Esta ação é irreversível.</strong></p>

          @if (erroExclusao()) { <div class="error-box">{{ erroExclusao() }}</div> }

          <div class="modal-actions" style="gap:8px">
            <button type="button" class="btn-outline" [disabled]="excluindoAlvo()" (click)="fecharExclusao()">Cancelar</button>
            <button type="button" class="btn-danger" [disabled]="excluindoAlvo()" (click)="confirmarExclusao()">
              {{ excluindoAlvo() ? 'Excluindo...' : 'Excluir definitivamente' }}
            </button>
          </div>
        </div>
      </div>
    }

    <!-- Chat de ajuda com IA (piloto) — manual próprio 'admin-ponto'; se auto-esconde sem a flag 'ajudaIa' -->
    <app-ajuda-chat pagina="admin-ponto" titulo="Ajuda — Ponto e Banco" />
  `,
  styles: [`
    .grid-cards { margin-bottom:24px; }
    .form-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; }
    .pessoa-select { width:100%; max-width:360px; }
    .badge-rev { color:#b45309; font-weight:600; }
    .badge-manual { color:var(--primary); font-weight:600; }
    .btn-x {
      background:none; border:none; cursor:pointer; padding:2px 6px;
      color:var(--color-red); font-weight:700; font-size:1rem; line-height:1;
    }
    .btn-x:disabled { opacity:.45; cursor:default; }
    .btn-danger {
      background:var(--color-red); color:#fff; border:none; border-radius:6px;
      padding:8px 16px; font-weight:600; cursor:pointer;
    }
    .btn-danger:disabled { opacity:.6; cursor:default; }
    .alvo { margin:0 0 12px; }
    .consequencias-titulo { margin:10px 0 4px; font-weight:600; }
    .consequencias { margin:0; padding-left:20px; }
    .consequencias li { margin-bottom:4px; }
    .reabre { margin:12px 0 0; }
    .irreversivel { margin:12px 0 0; color:var(--color-red); }
    @media (max-width:640px) { .form-grid { grid-template-columns:1fr; } }
  `],
})
export class AdminPontoComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  /** Admin com folha (SERVIDOR_PUBLICO=0 — Q35): destrava o card "Meu Ponto e Banco" (T-1.1/E9). */
  protected auth = inject(AuthService);

  @ViewChild('fileInput') fileInput!: ElementRef<HTMLInputElement>;

  // Navegação por cards (mesmo padrão de /admin/form-edit)
  activeCard = signal<'folhas' | 'retificacoes' | 'banco' | null>(null);

  // Upload
  tipo = 'MENSAL';
  dataInicio = '';
  dataFim = '';
  arquivo: File | null = null;
  uploading = signal(false);
  errorMsg = signal('');

  // Dados
  pessoas = signal<Pessoa[]>([]);
  operadores = computed(() => this.pessoas().filter(p => p.tipo === 'OPERADOR'));
  tecnicos = computed(() => this.pessoas().filter(p => p.tipo === 'TECNICO'));
  administradores = computed(() => this.pessoas().filter(p => p.tipo === 'ADMINISTRADOR'));
  lotes = signal<Lote[]>([]);
  protected lotesPager = new ClientPager(this.lotes);
  loadingLotes = signal(true);

  /**
   * Publicações em voo, POR LOTE (C9/F49). Era um slot único (`publicandoId`), e **qualquer**
   * resposta o zerava: publicar A e depois B fazia o retorno de A reabilitar o botão de B com o
   * POST de B ainda em voo — o reclique disparava um segundo POST do MESMO lote. Com um conjunto,
   * a resposta de um lote jamais destrava o botão de outro.
   */
  private publicandoIds = signal<ReadonlySet<string>>(new Set());
  publicando(loteId: string): boolean { return this.publicandoIds().has(loteId); }

  /**
   * Vínculo escolhido no `<select>` enquanto o PATCH daquela página está em voo (chave = id da
   * página) — é ele, e não mais o objeto da página, a fonte de verdade do valor exibido (C9/F48).
   *
   * Com o binding one-way sobre `p.pessoa_id`, o valor bindado não mudava quando o PATCH falhava
   * (continuava o do servidor), o Angular não via mudança de input e o `<select>` do DOM seguia
   * exibindo a pessoa escolhida — um vínculo que não existe no servidor. E reescolher a MESMA
   * pessoa não emite `change`: nenhum request saía. Publicado assim, a página ficava invisível
   * para sempre. Agora o valor sobe para o escolhido no disparo e **cai de volta** ao vínculo real
   * quando a resposta chega (sucesso ou erro) — a queda é a mudança de input que reescreve o DOM.
   */
  private valorEmVoo = signal<Record<string, string>>({});

  /**
   * Token de recência por PÁGINA (C9/F51): só a resposta do PATCH mais novo de cada página é
   * aplicada. Sem ele, dois PATCHes da mesma página fora de ordem deixavam vencer o que chegasse
   * por último — não o mais novo.
   */
  private seqPagina = new Map<string, number>();

  /**
   * Escritas já aplicadas por LOTE (C9/F51 — a mesma obrigação, do outro lado): o GET do detalhe
   * devolve o lote inteiro e pode ter lido o banco ANTES do commit de um PATCH/publicação que já
   * chegou à tela. Sem esta marca, o snapshot atrasado desfazia o vínculo recém-salvo (página de
   * volta a "— pendente —") ou revertia o lote publicado para "Em revisão" — com o botão
   * destrutivo de volta.
   */
  private seqEscrita = new Map<string, number>();

  /**
   * Recusa da publicação, POR LOTE (C9/F57). O toast chama a atenção mas some em 12 s — e esta
   * mensagem não é um aviso, é uma TAREFA: as recusas da folha mensal NOMEIAM as pessoas cuja
   * página precisa sair do lote. Ela fica na tela, junto do botão, até a próxima tentativa.
   */
  erroPublicacao = signal<Record<string, string>>({});

  /**
   * O usuário é o admin master (F59)? Vem do BACKEND, junto da listagem de lotes — o front nunca
   * compara username nenhum. É o que faz aparecer o X das duas tabelas; a segurança é o 403 do
   * endpoint, que continua valendo para quem chamá-lo à mão.
   */
  podeExcluir = signal(false);

  /** 7 colunas + a do X quando o master está logado (colspan das linhas de erro/vazio e do acordeão). */
  protected colunasLote = computed(() => (this.podeExcluir() ? 8 : 7));

  /** O preview aberto no modal (null = modal fechado) — é ele que descreve o que a exclusão vai apagar. */
  previewExclusao = signal<PreviewExclusao | null>(null);

  /** O item que o X mirou; o DELETE sai daqui (e não do preview, que é só a descrição). */
  private alvoExclusao = signal<AlvoExclusao | null>(null);

  /** Erro da ação de exclusão — fica NO MODAL, junto do botão que falhou (idioma C9/F57). */
  erroExclusao = signal('');

  /**
   * Exclusões em voo, POR ITEM (idioma do `publicandoIds` do C9): a chave é `lote:<id>` ou
   * `pag:<id>`. Cobre as DUAS pernas do gesto — o GET do preview e o DELETE —, porque as duas são
   * disparadas pelo mesmo X, e a resposta de um item jamais pode destravar o botão de outro.
   */
  private exclusaoIds = signal<ReadonlySet<string>>(new Set());
  exclusaoEmVoo(chave: string): boolean { return this.exclusaoIds().has(chave); }

  /** O DELETE do alvo aberto está em voo? (trava "Excluir definitivamente" e "Cancelar"). */
  excluindoAlvo = computed(() => {
    const alvo = this.alvoExclusao();
    return !!alvo && this.exclusaoIds().has(this.chaveDoAlvo(alvo));
  });

  /** Token de recência das duas cargas da tela: o retry pode ser clicado duas vezes. */
  private seqPessoas = 0;
  private seqLotes = 0;

  /**
   * Referências VIVAS por id (C18/F62): a identidade do lote na tela é a referência do objeto
   * (`_exp`, `paginas`, `emitirAviso` e os closures de publicar/onAssign/toggleLote a capturam),
   * mas o `loadLotes` recebia objetos NOVOS do backend — a resposta de uma publicação em voo
   * mutava um lote ÓRFÃO e o lote publicado seguia "Em revisão" na tela, com o botão destrutivo.
   * O mapa sobrevive ao ERRO de carga (que zera a lista): o retry da caixa reconcilia contra ele
   * e as respostas em voo continuam chegando ao objeto que volta à lista.
   */
  private lotesPorId = new Map<string, Lote>();

  /** Canais de erro das duas cargas (C7/F50): '' = sem erro. Limpos no início de cada carga. */
  erroPessoas = signal('');
  erroLotes = signal('');
  loadingPessoas = signal(true);
  /**
   * Vínculo bloqueado (C7/F50): a lista de pessoas está vazia e NÃO é um vazio legítimo —
   * a carga falhou ou ainda está em voo. O "em voo" é indispensável: o retry limpa o erro no
   * disparo, e sem ele o `<select>` voltaria a ficar interativo (com "— pendente —" como única
   * opção) durante todo o request — a janela em que o admin desvincula uma página correta.
   */
  vinculoBloqueado = computed(() =>
    this.pessoas().length === 0 && (!!this.erroPessoas() || this.loadingPessoas()));

  ngOnInit(): void {
    this.loadPessoas();
    this.loadLotes();
  }

  /** Alterna o card ativo; troca o conteúdo exibido (Folhas de Ponto / Retificações / Banco de Horas). */
  selectCard(card: 'folhas' | 'retificacoes' | 'banco'): void {
    this.activeCard.set(card);
  }

  tipoLabel(t?: PessoaTipo): string {
    return t === 'OPERADOR' ? 'Operador' : t === 'TECNICO' ? 'Técnico' : t === 'ADMINISTRADOR' ? 'Administrador' : '';
  }

  // ── Carregamento ──
  /** Pessoas dos selects de vínculo; também é o retry da caixa de erro (C7/F50). */
  loadPessoas(): void {
    const seq = ++this.seqPessoas;   // dois cliques no "Tentar novamente" põem duas cargas em voo
    this.erroPessoas.set('');
    this.loadingPessoas.set(true);
    this.api.get<any>('/api/admin/ponto/pessoas').subscribe({
      next: res => {
        if (seq !== this.seqPessoas) return;   // obsoleta: uma carga mais nova está em voo
        this.pessoas.set(res.data || []);
        this.loadingPessoas.set(false);
      },
      error: err => {
        if (seq !== this.seqPessoas) return;   // a falha de uma carga velha não apaga a lista da nova
        this.pessoas.set([]);
        this.loadingPessoas.set(false);
        this.erroPessoas.set(erroCargaMsg(err,
          'Não foi possível carregar a lista de pessoas. O vínculo das páginas fica indisponível até recarregar.'));
      },
    });
  }

  /**
   * Carrega a lista de lotes; se `abrir` for passado, mescla o detalhe e expande aquele lote.
   * Sem argumento é também o retry da caixa de erro (C7/F50).
   */
  loadLotes(abrir?: Lote): void {
    const seq = ++this.seqLotes;   // idem: o botão de retry não trava, e o gesto natural é reclicar
    // Snapshot das marcas de escrita POR LOTE no disparo (C18/F62 — a mesma justificativa do
    // `carregarDetalhe`): o SELECT da listagem pode ter lido o banco ANTES do commit de uma
    // publicação/PATCH que já chegou à tela; aplicar esses campos regrediria o lote vivo
    // (publicado de volta a "Em revisão", com o botão destrutivo).
    const marcas = new Map(this.seqEscrita);
    this.loadingLotes.set(true);
    this.erroLotes.set('');
    this.api.get<any>('/api/admin/ponto/lotes').subscribe({
      next: res => {
        if (seq !== this.seqLotes) return;
        // Reconciliação por id (C18/F62): o lote que JÁ tem referência viva é ATUALIZADO nela
        // (Object.assign) e é ela que volta à lista — `_exp`/`emitirAviso`/`paginas` são campos de
        // UI, não vêm no payload da listagem e sobrevivem; os closures em voo apontam para o objeto
        // que ESTÁ na tela. Lote que sumiu do servidor sai da lista (e do mapa: morre silencioso).
        const list: Lote[] = (res.data || []).map((novo: Lote) => {
          const vivo = this.lotesPorId.get(novo.id);
          if (!vivo) return novo;
          // Escrita aplicada DEPOIS do disparo deste GET → o snapshot é obsoleto PARA ESTE lote:
          // mantém o vivo como está (o estado mais novo vence; a lista segue com a referência).
          if ((this.seqEscrita.get(novo.id) ?? 0) !== (marcas.get(novo.id) ?? 0)) return vivo;
          return Object.assign(vivo, novo);
        });
        this.lotesPorId = new Map(list.map(l => [l.id, l]));
        if (abrir) {
          const row = list.find(x => x.id === abrir.id);
          if (row) { Object.assign(row, abrir); row._exp = true; }
          this.lotesPager.onPage(1);   // lote novo é o mais recente (ordem desc) → garante que fique visível
        }
        this.lotes.set(list);
        this.podeExcluir.set(res.pode_excluir === true);   // F59: quem pode excluir é o backend que diz
        this.loadingLotes.set(false);
      },
      error: err => {
        if (seq !== this.seqLotes) return;   // uma falha atrasada não apaga a lista que o retry já trouxe
        this.lotes.set([]);
        this.loadingLotes.set(false);
        this.erroLotes.set(erroCargaMsg(err,
          'Não foi possível carregar os lotes enviados. Não reenvie o PDF antes de recarregar — o lote pode já ter sido processado.'));
      },
    });
  }

  /** Aplica dados opcionais ao lote e força o refresh do signal `lotes`. */
  private aplicarLote(l: Lote, dados?: any): void {
    if (dados) Object.assign(l, dados);
    this.lotes.set([...this.lotes()]);
  }

  /** Expande/contrai a linha do lote (acordeão). Carrega as páginas no 1º clique. */
  toggleLote(l: Lote): void {
    l._exp = !l._exp;
    if (l._exp && !l.paginas) {
      this.carregarDetalhe(l, err => {
        l._exp = false;
        this.aplicarLote(l);
        this.toast.error(erroCargaMsg(err, 'Não foi possível abrir o lote.'));
      });
    }
    this.aplicarLote(l);
  }

  // ── Upload ──
  onFileSelect(event: Event): void {
    this.arquivo = (event.target as HTMLInputElement).files?.[0] || null;
  }

  onUpload(): void {
    this.errorMsg.set('');
    if (!this.arquivo) { this.errorMsg.set('Selecione o arquivo PDF.'); return; }
    if (!this.dataInicio || !this.dataFim) { this.errorMsg.set('Informe o início e o fim do período.'); return; }
    if (this.dataFim < this.dataInicio) { this.errorMsg.set('A data final não pode ser anterior à inicial.'); return; }

    this.uploading.set(true);
    const fd = new FormData();
    fd.append('arquivo', this.arquivo);
    fd.append('tipo', this.tipo);
    fd.append('data_inicio', this.dataInicio);
    fd.append('data_fim', this.dataFim);

    this.api.postForm<any>('/api/admin/ponto/upload', fd).subscribe({
      next: res => {
        this.uploading.set(false);
        if (res.ok) {
          this.arquivo = null;
          if (this.fileInput) this.fileInput.nativeElement.value = '';
          this.loadLotes(res.data);   // recarrega e já abre o lote recém-enviado
        } else {
          this.errorMsg.set(res.error || 'Erro ao processar o PDF.');
        }
      },
      error: err => {
        this.uploading.set(false);
        this.errorMsg.set(httpErrorMsg(err, 'Erro ao processar o PDF.'));
      },
    });
  }

  // ── Vínculo (uma máquina de estado por página: valor exibido + PATCH em voo + recência) ──
  /** Valor do `<select>` da página: o escolhido enquanto o PATCH voa, senão o vínculo salvo (F48). */
  valorPessoa(p: Pagina): string {
    return this.valorEmVoo()[p.id] ?? this.vinculoSalvo(p);
  }

  /** O vínculo que o SERVIDOR tem para a página — `''` = "— pendente —". */
  private vinculoSalvo(p: Pagina): string {
    return p.pessoa_id ? `${p.pessoa_tipo}:${p.pessoa_id}` : '';
  }

  onAssign(l: Lote, p: Pagina, value: string): void {
    let body: { pessoa_id: string | null; pessoa_tipo: string | null } = { pessoa_id: null, pessoa_tipo: null };
    if (value) {
      const idx = value.indexOf(':');
      body = { pessoa_tipo: value.substring(0, idx), pessoa_id: value.substring(idx + 1) };
    }

    const seq = (this.seqPagina.get(p.id) ?? 0) + 1;
    this.seqPagina.set(p.id, seq);
    this.valorEmVoo.update(v => ({ ...v, [p.id]: value }));   // o select passa a exibir a escolha, não o salvo

    this.api.patch<any>(`/api/admin/ponto/lote/${l.id}/pagina/${p.id}`, body).subscribe({
      next: res => {
        if (seq !== this.seqPagina.get(p.id)) return;   // obsoleta: há um PATCH mais novo desta página (F51)
        this.soltarValorEmVoo(p.id);                    // o select volta a derivar do servidor — agora atualizado
        if (res.ok) this.aplicarPagina(l, p.id, res.data);
      },
      error: err => {
        if (seq !== this.seqPagina.get(p.id)) return;
        this.soltarValorEmVoo(p.id);                    // F48: o select REVERTE ao vínculo real do servidor
        // F57: a frase do backend vem em `error` (não em `message`), mas a GUIA da tela vem na frente:
        // em todo 500 o corpo é o genérico "Erro interno do servidor", que sozinho não diz nada ao admin.
        this.toast.error(erroCargaMsg(err, 'Não foi possível vincular a página — a escolha foi desfeita.'));
        this.recarregarLote(l);
      },
    });
  }

  /** Devolve o `<select>` da página ao valor do servidor (fim do voo — por sucesso ou por erro). */
  private soltarValorEmVoo(paginaId: string): void {
    this.valorEmVoo.update(v => {
      const resto = { ...v };
      delete resto[paginaId];
      return resto;
    });
  }

  /**
   * Aplica ao lote APENAS a página que este PATCH alterou (F51). O endpoint devolve o LOTE INTEIRO
   * (`PontoService.detalheLote`), e mesclá-lo fazia a resposta atrasada de uma página desfazer o
   * vínculo de outra já salvo — vencia quem chegasse por último, não o estado mais novo.
   *
   * ⚠️ A página é **substituída**, não mesclada: o backend serializa com
   * `default-property-inclusion: non_null`, então a página DESVINCULADA volta sem as chaves
   * `pessoa_id`/`pessoa_tipo`/`pessoa_nome` — um `Object.assign` deixaria o vínculo antigo intacto
   * no objeto local e o `<select>` voltaria a exibir a pessoa que o admin acabou de remover.
   *
   * O contador de pendentes volta a ser DERIVADO das páginas em tela, pelo mesmo critério do
   * backend (`status_match = PENDENTE`), em vez de copiado de um payload que pode estar velho.
   */
  private aplicarPagina(l: Lote, paginaId: string, dados: any): void {
    const respondida = (dados?.paginas as Pagina[] | undefined)?.find(x => x.id === paginaId);
    const idx = l.paginas?.findIndex(x => x.id === paginaId) ?? -1;
    if (respondida && idx >= 0) l.paginas![idx] = respondida;
    if (l.paginas) l.pendentes = l.paginas.filter(x => x.status_match === 'PENDENTE').length;
    this.marcarEscrita(l.id);
    this.aplicarLote(l);
  }

  /**
   * Marca que uma ESCRITA daquele lote foi aplicada na tela (PATCH de vínculo, publicação).
   * É o que permite descartar o snapshot de um GET de detalhe que saiu antes dela: o SELECT do
   * servidor pode ter rodado antes do commit da escrita, e aplicá-lo desfaria estado mais novo
   * (a página voltando a "— pendente —", o lote publicado voltando a "Em revisão").
   */
  private marcarEscrita(loteId: string): void {
    this.seqEscrita.set(loteId, (this.seqEscrita.get(loteId) ?? 0) + 1);
  }

  /**
   * GET do detalhe do lote — caminho ÚNICO (abrir o acordeão e recuperar-se de um PATCH que falhou),
   * com **descarte de snapshot obsoleto**: se uma escrita daquele lote foi aplicada depois do
   * disparo, esta resposta pode ser mais VELHA do que a tela e é jogada fora.
   */
  private carregarDetalhe(l: Lote, onErro: (err: any) => void): void {
    const marca = this.seqEscrita.get(l.id) ?? 0;
    this.api.get<any>(`/api/admin/ponto/lote/${l.id}`).subscribe({
      next: res => {
        if ((this.seqEscrita.get(l.id) ?? 0) !== marca) return;   // obsoleto: uma escrita chegou primeiro
        if (res.ok) this.aplicarLote(l, res.data);
      },
      error: onErro,
    });
  }

  /**
   * Recarrega o detalhe de um lote (após erro) sem mudar o estado de expansão.
   * Se a própria recuperação falhar (C7 — faceta silenciosa do F48), o admin precisa saber:
   * o que está na tela deixou de ser confiável. Sem handler, o erro subia como unhandled.
   */
  private recarregarLote(l: Lote): void {
    this.carregarDetalhe(l, err => this.toast.error(erroCargaMsg(err,
      'Não foi possível recarregar o lote. Os vínculos exibidos podem estar desatualizados — recarregue a página.')));
  }

  // ── Preview ──
  preview(p: Pagina): void {
    this.api.getBlob(`/api/admin/ponto/pagina/${p.id}/preview`).subscribe({
      next: blob => this.api.abrirBlobInline(blob),
      error: () => this.toast.error('Não foi possível abrir o PDF da página.'),
    });
  }

  // ── Publicação ──
  publicar(l: Lote): void {
    if (this.publicando(l.id)) return;   // F49: trava por lote (o [disabled] do botão é a camada de UI)

    const aviso = l.pendentes > 0
      ? `\n\nAtenção: ${l.pendentes} página(s) pendente(s) não ficarão visíveis a ninguém.`
      : '';
    if (!confirm(`Publicar este lote? As folhas vinculadas ficarão disponíveis para os operadores/técnicos.${aviso}`)) return;

    this.marcarPublicando(l.id, true);
    this.definirErroPublicacao(l.id, '');   // nova tentativa: a recusa anterior sai da tela
    this.api.post<any>(`/api/admin/ponto/lote/${l.id}/publicar`, { emitir_aviso: l.emitirAviso !== false }).subscribe({
      next: res => {
        this.marcarPublicando(l.id, false);
        if (res.ok) { this.marcarEscrita(l.id); this.aplicarLote(l, res.data); }
      },
      error: err => {
        this.marcarPublicando(l.id, false);
        // F57: a recusa do backend vem em `{ok:false, error:"<frase>"}` — a lista fixa `['message']`
        // descartava justamente as frases que dizem O QUE FAZER ("Lote já está publicado.", as recusas
        // da folha mensal, que NOMEIAM a pessoa em conflito). `erroCargaMsg` usa os dois campos e mantém
        // a guia da tela na frente (em todo 500 o corpo é o genérico "Erro interno do servidor").
        const msg = erroCargaMsg(err, 'Não foi possível publicar o lote.');
        this.definirErroPublicacao(l.id, msg);   // fica na tela: a recusa é uma TAREFA (quais páginas remover)
        this.toast.error(msg);
      },
    });
  }

  /** Liga/desliga a trava de publicação daquele lote (F49) — nunca a dos outros. */
  private marcarPublicando(loteId: string, emVoo: boolean): void {
    this.publicandoIds.update(ids => {
      const novo = new Set(ids);
      if (emVoo) novo.add(loteId); else novo.delete(loteId);
      return novo;
    });
  }

  private definirErroPublicacao(loteId: string, msg: string): void {
    this.erroPublicacao.update(e => {
      const novo = { ...e };
      if (msg) novo[loteId] = msg; else delete novo[loteId];
      return novo;
    });
  }

  // ── Exclusão (F59): X → preview → modal com as consequências reais → DELETE ──

  chaveLote(l: Lote): string { return `lote:${l.id}`; }
  chavePagina(p: Pagina): string { return `pag:${p.id}`; }

  private chaveDoAlvo(alvo: AlvoExclusao): string {
    return alvo.pagina ? this.chavePagina(alvo.pagina) : this.chaveLote(alvo.lote);
  }

  /** X do LOTE (tabela principal). */
  abrirExclusaoLote(l: Lote): void {
    this.abrirExclusao({ lote: l }, `/api/admin/ponto/lote/${l.id}/exclusao/preview`);
  }

  /** X da FOLHA (tabela em cascata). */
  abrirExclusaoPagina(l: Lote, p: Pagina): void {
    this.abrirExclusao({ lote: l, pagina: p }, `/api/admin/ponto/lote/${l.id}/pagina/${p.id}/exclusao/preview`);
  }

  /**
   * Carrega o preview e só então abre o modal: uma confirmação que não sabe o que vai apagar não é
   * confirmação — é um "tem certeza?" com o admin adivinhando. Falhou o preview, o modal NÃO abre
   * (o erro vai ao toast) e nada é excluído.
   */
  private abrirExclusao(alvo: AlvoExclusao, url: string): void {
    const chave = this.chaveDoAlvo(alvo);
    if (this.exclusaoEmVoo(chave)) return;   // o [disabled] do X é a camada de UI; esta é a trava real

    this.marcarExclusao(chave, true);
    this.erroExclusao.set('');
    this.api.get<any>(url).subscribe({
      next: res => {
        this.marcarExclusao(chave, false);
        if (res.ok) {
          this.alvoExclusao.set(alvo);
          this.previewExclusao.set(res.data);
        }
      },
      error: err => {
        this.marcarExclusao(chave, false);
        this.toast.error(erroCargaMsg(err, 'Não foi possível verificar o que a exclusão apagaria — nada foi excluído.'));
      },
    });
  }

  /**
   * O DELETE. Sucesso: o modal fecha e a listagem é recarregada do servidor — nada de remendar a
   * lista local, porque a exclusão muda mais do que a linha clicada (páginas, contagens, e o lote
   * inteiro quando o escopo é ele).
   */
  confirmarExclusao(): void {
    const alvo = this.alvoExclusao();
    if (!alvo) return;
    const chave = this.chaveDoAlvo(alvo);
    if (this.exclusaoEmVoo(chave)) return;

    const url = alvo.pagina
      ? `/api/admin/ponto/lote/${alvo.lote.id}/pagina/${alvo.pagina.id}`
      : `/api/admin/ponto/lote/${alvo.lote.id}`;

    this.marcarExclusao(chave, true);
    this.erroExclusao.set('');
    this.api.delete<any>(url).subscribe({
      next: res => {
        this.marcarExclusao(chave, false);
        if (res.ok) {
          this.fecharExclusao();
          this.toast.success(alvo.pagina ? 'Folha excluída.' : 'Lote excluído.');
          this.loadLotes();
        }
      },
      error: err => {
        this.marcarExclusao(chave, false);
        // A recusa fica NO MODAL (o 403 do não-master, o 404 de um lote que outro admin já excluiu):
        // é ali que o admin está olhando, e o modal segue aberto para ele entender o que houve.
        this.erroExclusao.set(erroCargaMsg(err, 'Não foi possível excluir.'));
      },
    });
  }

  /** Cancelar: fecha sem efeito nenhum. */
  fecharExclusao(): void {
    this.previewExclusao.set(null);
    this.alvoExclusao.set(null);
    this.erroExclusao.set('');
  }

  private marcarExclusao(chave: string, emVoo: boolean): void {
    this.exclusaoIds.update(ids => {
      const novo = new Set(ids);
      if (emVoo) novo.add(chave); else novo.delete(chave);
      return novo;
    });
  }
}
