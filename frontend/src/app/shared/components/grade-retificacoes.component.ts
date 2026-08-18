import { Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { MESES } from '../../core/helpers/table.helpers';
import { ancoraDoClique } from '../../core/helpers/popover.helpers';
import { ConfigurarTiposMarcacaoComponent, TipoMarcacao } from './configurar-tipos-marcacao.component';
import { MesAno, MesAnoSelectorComponent, anosNavegaveis } from './mes-ano-selector.component';
import { ToastService } from './toast.component';

/** Categoria exibida no combobox (B-3.1) — casa com o PESSOA_TIPO no backend. */
type Categoria = 'operadores' | 'tecnicos' | 'administradores';

/** Célula já resolvida pela precedência do §1 no backend (E10 passo 1). */
interface Celula {
  tipo: 'horarios' | 'banco' | 'ocorrencia' | 'marcacao_pessoa' | 'marcacao_global';
  texto: string;
  tem_obs: boolean;
  obs?: string;
  /** Tipo do catálogo por trás da ocorrência — ausente em horários e folga aprovada. */
  tipo_id?: string;
}
interface DiaInfo {
  dia: number;
  data: string;          // YYYY-MM-DD
  dow: number;           // 1=segunda … 7=domingo
  fim_semana: boolean;
  marcacao_global: string | null;
  /** Tipo da ocorrência geral do dia; preenchido = o dia inteiro está sob ela. */
  marcacao_global_id: string | null;
}
interface Funcionario { id: string; nome: string; folgas: number; }
interface GradeData {
  categoria: string;
  ano: number;
  mes: number;
  funcionarios: Funcionario[];
  dias: DiaInfo[];
  celulas: Record<string, Record<string, Celula>>;
}

/**
 * O que o popover de ocorrências está editando: o dia inteiro (vale para todos os
 * funcionários) ou a célula de um funcionário. `atual` é o tipo já marcado ali — é
 * ele que aparece selecionado ao abrir.
 */
interface AlvoOcorrencia {
  escopo: 'dia' | 'pessoa';
  dia: number;
  data: string;
  pessoaId: string | null;
  atual: string | null;
  x: number;
  y: number;
  /** Sem espaço abaixo, a lista sobe: `y` passa a ser o topo da célula e o popover cresce para cima. */
  acima: boolean;
}

/** Opção do popover: "Nenhuma" (remove) ou um tipo do catálogo. */
interface OpcaoOcorrencia { id: string | null; rotulo: string; }

/**
 * Card admin "Retificações" (E10, B-2/B-3): grade mensal dia × funcionário. O
 * backend entrega o payload já com a precedência do §1 resolvida por célula
 * (horários → banco → ocorrência geral → ocorrência do funcionário → vazia); aqui é
 * só apresentação. Paleta da referência 7.2.5: célula preenchida em dia útil =
 * amarelo + fonte vermelha, vazia = azul-claro, linha de fim de semana = cinza
 * (independentemente do conteúdo — B-3.4). 1ª coluna fixa (sticky — B-3.10,
 * gotcha 7) e paginação client-side de 8 colunas de funcionários (B-3.9/F#2).
 *
 * <p>As ocorrências são marcadas na própria grade: o rótulo do dia abre os tipos
 * gerais (aplicam a todos, independentemente da paginação) e a célula de um
 * funcionário abre os individuais. A escolha grava na hora.
 *
 * <p>Editar a RETIFICAÇÃO (os horários) pela célula ainda não existe — a célula de
 * horários é só leitura, e o clique nela não abre nada.
 */
@Component({
  selector: 'app-grade-retificacoes',
  standalone: true,
  imports: [MesAnoSelectorComponent, ConfigurarTiposMarcacaoComponent],
  template: `
    <!-- B-3.1: combobox categoria · seletor mês/ano · catálogo · exportação -->
    <div class="barra">
      <select class="sel-cat" [value]="categoria()" (change)="onCategoria($event)" aria-label="Categoria">
        <option value="operadores">Operadores</option>
        <option value="tecnicos">Técnicos</option>
        <option value="administradores">Administradores</option>
      </select>

      <!-- O seletor compartilhado mantém a grade no range mensal válido do Ponto. -->
      <app-mes-ano-selector [anos]="anosSeletor" (mudou)="onMesAno($event)" />

      <!-- Cadastrar e excluir tipos é do admin master; esconder o botão não é a segurança (o backend recusa). -->
      @if (isMaster()) {
        <button type="button" class="btn-outline" (click)="abrirConfigurarTipos()">Configurar</button>
      }
      <!-- O download é sempre a tabela geral, então não depende da categoria exibida ter gente. -->
      <button type="button" class="btn-outline" (click)="baixarTabela()">Baixar tabela</button>
    </div>

    @if (erro()) {
      <div class="error-box">{{ erro() }}</div>
    } @else if (carregando()) {
      <p class="text-muted-sm">Carregando grade...</p>
    } @else if (funcionarios().length === 0) {
      <p class="empty-state">Nenhum funcionário nesta categoria.</p>
    } @else {
      <!-- B-3.9: paginação client-side de 8 colunas (coluna de dias repetida em cada página) -->
      @if (totalPaginas() > 1) {
        <div class="pag">
          <button type="button" class="nav-btn" (click)="paginaAnterior()" [disabled]="pagina() === 0" aria-label="Funcionários anteriores">‹</button>
          <span class="pag-info">Página {{ pagina() + 1 }} de {{ totalPaginas() }}</span>
          <button type="button" class="nav-btn" (click)="paginaSeguinte()" [disabled]="pagina() >= totalPaginas() - 1" aria-label="Próximos funcionários">›</button>
        </div>
      }

      <div class="table-container">
        <table class="grade">
          <thead>
            <tr>
              <th class="sticky-col corner"></th>
              @for (f of funcsPagina(); track f.id) {
                <th class="func-head" [title]="f.nome"><span class="nome-clamp">{{ f.nome }}</span></th>
              }
            </tr>
          </thead>
          <tbody>
            <!-- B-3.2: linha "Folgas" (COUNT de folgas APROVADAS por funcionário) -->
            <tr class="row-folgas">
              <td class="sticky-col rot-folgas">Folgas</td>
              @for (f of funcsPagina(); track f.id) {
                <td class="cel-folgas">{{ f.folgas }}</td>
              }
            </tr>

            <!-- B-3.3: uma linha por dia do mês; fim de semana pinta a linha inteira (B-3.4) -->
            @for (d of dias(); track d.dia) {
              <tr [class.fds]="d.fim_semana">
                @let diaAberto = diaClicavel(d);
                <td class="sticky-col rot-dia" [class.clicavel]="diaAberto"
                    [attr.aria-disabled]="diaAberto ? null : 'true'"
                    [title]="diaAberto ? 'Ocorrência para todos os funcionários' : null"
                    (click)="abrirNoDia(d, $event)">
                  {{ d.dia }}-{{ mesAbrev() }}
                  @if (salvandoEm() === chaveDia(d.dia)) { <span class="salvando" aria-label="Salvando"></span> }
                </td>
                @for (f of funcsPagina(); track f.id) {
                  @let c = celula(f.id, d.dia);
                  @let celulaAberta = celulaClicavel(f.id, d);
                  <td class="cel" [class.preenchida]="!!c" [class.vazia]="!c" [class.tem-obs]="c?.tem_obs"
                      [class.clicavel]="celulaAberta"
                      [attr.aria-disabled]="celulaAberta ? null : 'true'"
                      [title]="c?.obs || null"
                      (click)="abrirNaCelula(f, d, $event)">
                    @if (c) {
                      @if (c.tipo === 'horarios') {
                        @for (linha of horariosLinhas(c.texto); track $index) { <span class="hl">{{ linha }}</span> }
                      } @else {
                        {{ c.texto }}
                      }
                      @if (c.tem_obs) { <span class="marca-obs" aria-label="Possui observação"></span> }
                    }
                    @if (salvandoEm() === chaveCelula(f.id, d.dia)) { <span class="salvando" aria-label="Salvando"></span> }
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    <!-- Ocorrências: lista ancorada na célula/rótulo clicado, gravando na escolha -->
    @if (alvo(); as a) {
      <div class="popover" [class.acima]="a.acima" [style.left.px]="a.x" [style.top.px]="a.y"
           (click)="$event.stopPropagation()">
        @if (confirmacao(); as cf) {
          <!-- A ação do dia atinge todos os funcionários do mês: confirma antes de gravar -->
          <p class="pop-pergunta">{{ cf.pergunta }}</p>
          <div class="pop-acoes">
            <button type="button" class="btn-outline" (click)="cancelarConfirmacao()">Cancelar</button>
            <button type="button" class="btn-primary-custom" [disabled]="!!salvandoEm()" (click)="confirmar()">Confirmar</button>
          </div>
        } @else if (catalogoErro()) {
          <p class="pop-vazio">Não foi possível carregar os tipos de ocorrência.</p>
        } @else if (opcoes().length <= 1) {
          <p class="pop-vazio">Nenhum tipo cadastrado</p>
        } @else {
          <p class="pop-titulo">{{ a.escopo === 'dia' ? 'Todos os funcionários' : nomeDoAlvo() }} · {{ a.dia }}-{{ mesAbrev() }}</p>
          @for (o of opcoes(); track o.id) {
            <button type="button" class="pop-item" [class.atual]="o.id === a.atual"
                    [disabled]="!!salvandoEm()" (click)="escolher(o)">
              {{ o.rotulo }}
            </button>
          }
        }
      </div>
    }

    <!-- Modal "Configurar" — catálogo de tipos de ocorrência (admin master) -->
    @if (configurarTiposAberto()) {
      <app-configurar-tipos-marcacao
        (alterado)="onTiposAlterados()"
        (fechar)="configurarTiposAberto.set(false)" />
    }
  `,
  styles: [`
    :host { display: block; }
    .barra {
      display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
      justify-content: center; margin-bottom: 12px;
    }
    .barra app-mes-ano-selector { --sel-margin: 0; }
    .sel-cat {
      height: 40px; border: 1px solid var(--border); border-radius: 8px;
      background: #fff; color: var(--text); font-size: .95rem; padding: 0 10px; min-width: 160px;
    }

    .grade { border-collapse: separate; border-spacing: 0; font-size: .72rem; }
    .grade th, .grade td { border: 1px solid #cbd5e1; padding: 2px 4px; text-align: center; white-space: nowrap; }

    /* Colunas de funcionário com largura fixa: 8 cabem numa página sem scroll horizontal (B-3.9) */
    .func-head, .cel { width: 108px; min-width: 108px; max-width: 108px; }
    .hl { display: block; }   /* cada par Ent./Saí. numa linha, para a coluna caber estreita */

    /* Cabeçalho de funcionário: azul, branco, clamp 3 linhas, altura fixa (B-3.11/Q22, 7.2.2) */
    .func-head { background: #0B5394; color: #fff; font-weight: 600; height: 66px; vertical-align: middle; }
    .nome-clamp {
      display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
      overflow: hidden; white-space: normal; line-height: 1.15;
    }
    .cel.tem-obs { cursor: help; }

    /* 1ª coluna fixa (sticky) — precisa de background sólido e z-index (gotcha 7) */
    .sticky-col { position: sticky; left: 0; z-index: 2; width: 58px; min-width: 58px; max-width: 58px; }
    thead .sticky-col { z-index: 3; }
    .corner { background: #FF0000; }
    .rot-folgas { background: #434343; color: #fff; font-weight: 600; text-align: left; }
    .rot-dia { background: #DEEAF6; color: var(--text); font-weight: 500; text-align: left; position: relative; }

    .cel-folgas { background: #434343; color: #fff; font-weight: 600; }

    /* Grade (7.2.5): preenchida em dia útil = amarelo + vermelho; vazia = azul-claro */
    .cel { position: relative; }
    .cel.preenchida { background: #FFFF00; color: #FF0000; font-weight: 600; }
    .cel.vazia { background: #DEEAF6; }

    /* Onde a ocorrência pode ser marcada: afordância discreta, sem competir com o conteúdo */
    .clicavel { cursor: pointer; }
    .clicavel:hover { outline: 2px solid var(--primary); outline-offset: -2px; }

    /* Marca de observação: triângulo vermelho no canto superior direito (B-3.7/Q25) */
    .marca-obs {
      position: absolute; top: 0; right: 0; width: 0; height: 0;
      border-top: 7px solid #b91c1c; border-left: 7px solid transparent;
    }

    /* Gravação em voo naquela célula: enquanto ela existe, novos cliques ficam travados */
    .salvando {
      position: absolute; bottom: 1px; left: 1px; width: 6px; height: 6px;
      border-radius: 50%; background: var(--primary); animation: pulsar 1s ease-in-out infinite;
    }
    @keyframes pulsar { 0%, 100% { opacity: .25; } 50% { opacity: 1; } }

    /* B-3.4: fim de semana pinta a linha inteira de cinza, vencendo amarelo/azul-claro */
    tr.fds td { background: #434343 !important; color: #fff; }

    .pag {
      display: flex; align-items: center; justify-content: center; gap: 12px; margin-bottom: 12px;
    }
    .pag-info { font-size: .9rem; color: var(--muted); }
    .nav-btn {
      width: 40px; height: 40px; border-radius: 999px;
      border: 1px solid var(--border); background: #fff; color: var(--primary);
      font-size: 1.4rem; line-height: 1; cursor: pointer; padding: 0;
      display: flex; align-items: center; justify-content: center;
    }
    .nav-btn:hover:not(:disabled) { background: var(--row-hover); }
    .nav-btn:disabled { opacity: .35; cursor: default; }

    /* Popover de ocorrências: ancorado na célula, acima do sticky da 1ª coluna */
    .popover {
      position: fixed; z-index: 60; min-width: 180px; max-width: 260px;
      max-height: 280px; overflow-y: auto;
      background: #fff; border: 1px solid var(--border); border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0, 0, 0, .18); padding: 6px;
    }
    /* Ancorado no TOPO da célula quando não há espaço abaixo: a lista cresce para cima */
    .popover.acima { transform: translateY(-100%); }
    .pop-titulo {
      margin: 2px 6px 6px; font-size: .78rem; color: var(--muted);
      border-bottom: 1px solid var(--border); padding-bottom: 4px;
    }
    .pop-item {
      display: block; width: 100%; text-align: left; border: 0; background: transparent;
      padding: 7px 8px; border-radius: 6px; font-size: .88rem; color: var(--text); cursor: pointer;
    }
    .pop-item:hover:not(:disabled) { background: var(--row-hover); }
    .pop-item.atual { background: var(--primary); color: #fff; font-weight: 600; }
    .pop-item:disabled { opacity: .5; cursor: default; }
    .pop-vazio { margin: 6px 8px; font-size: .85rem; color: var(--muted); }
    .pop-pergunta { margin: 6px 8px; font-size: .88rem; color: var(--text); white-space: normal; }
    .pop-acoes { display: flex; gap: 8px; justify-content: flex-end; padding: 4px 4px 2px; }

    @media (max-width: 640px) {
      .barra { gap: 8px; }
      .sel-cat { min-width: 0; flex: 1; }
    }
  `],
})
export class GradeRetificacoesComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private auth = inject(AuthService);

  /** Só o master vê o botão "Configurar" — a permissão de verdade é a do backend. */
  isMaster = this.auth.isMaster;

  private hoje = new Date();
  categoria = signal<Categoria>('operadores');
  anoMes = signal<MesAno>({ ano: this.hoje.getFullYear(), mes: this.hoje.getMonth() + 1 });
  /** Anos desde a implantação do Ponto até o mês local corrente mais dois. */
  readonly anosSeletor = anosNavegaveis(this.hoje);

  grade = signal<GradeData | null>(null);
  carregando = signal(false);
  erro = signal('');
  pagina = signal(0);

  // ── Ocorrências na própria grade ─────────────────────────────

  /** Catálogo de tipos: a fonte das opções do popover. Carregado com a grade, não a cada clique. */
  tipos = signal<TipoMarcacao[]>([]);
  catalogoErro = signal(false);
  configurarTiposAberto = signal(false);

  /** Célula/rótulo com o popover aberto; nulo = nenhum. */
  alvo = signal<AlvoOcorrencia | null>(null);
  /** Ação coletiva aguardando confirmação dentro do próprio popover. */
  confirmacao = signal<{ pergunta: string; opcao: OpcaoOcorrencia } | null>(null);
  /** Chave da célula com gravação em voo — trava novos cliques e marca a célula. */
  salvandoEm = signal<string | null>(null);

  /**
   * Contexto (categoria + mês) da grade exibida. A resposta de uma gravação feita no
   * contexto anterior não pode agir sobre a tela nova: o usuário pode ter trocado de
   * mês enquanto o PUT estava em voo, e recarregar/avisar ali seria sobre outra grade.
   */
  private seqContexto = 0;

  /** Escopo do catálogo correspondente ao que está sendo marcado. */
  private tiposDoEscopo = computed(() => {
    const escopo = this.alvo()?.escopo === 'dia' ? 'GLOBAL' : 'INDIVIDUAL';
    return this.tipos().filter(t => t.escopo === escopo);
  });

  /** Opções do popover: "Nenhuma" (que desmarca) primeiro, depois os tipos do escopo. */
  opcoes = computed<OpcaoOcorrencia[]>(() => {
    const doEscopo = this.tiposDoEscopo();
    if (doEscopo.length === 0) return [];
    return [{ id: null, rotulo: 'Nenhuma' }, ...doEscopo.map(t => ({ id: t.id, rotulo: t.nome }))];
  });

  funcionarios = computed(() => this.grade()?.funcionarios ?? []);
  dias = computed(() => this.grade()?.dias ?? []);
  mesAbrev = computed(() => {
    const m = this.grade()?.mes ?? this.anoMes().mes;
    return MESES[m].slice(0, 3).toLowerCase();   // "1-set", "1-jun", …
  });

  totalPaginas = computed(() => Math.max(1, Math.ceil(this.funcionarios().length / PAGE_SIZE)));
  funcsPagina = computed(() => {
    const ini = this.pagina() * PAGE_SIZE;
    return this.funcionarios().slice(ini, ini + PAGE_SIZE);
  });

  ngOnInit(): void {
    this.carregar();
    this.carregarCatalogo();
  }

  /** Célula (funcionário, dia) já resolvida pela precedência no backend; ausente = vazia. */
  celula(pessoaId: string, dia: number): Celula | undefined {
    return this.grade()?.celulas?.[pessoaId]?.[dia];
  }

  /**
   * As batidas do dia agrupadas em pares Ent./Saí. — cada par numa linha, para a coluna caber
   * estreita. A batida que ninguém preencheu chega marcada, e é o que mantém cada horário na
   * própria posição quando o dia veio pela metade.
   */
  horariosLinhas(texto: string): string[] {
    const partes = texto.split(' ').filter(p => p);
    const linhas: string[] = [];
    for (let i = 0; i < partes.length; i += 2) linhas.push(partes.slice(i, i + 2).join(' '));
    return linhas.length ? linhas : [texto];
  }

  onCategoria(ev: Event): void {
    this.categoria.set((ev.target as HTMLSelectElement).value as Categoria);
    this.carregar();
  }

  onMesAno(m: MesAno): void {
    this.anoMes.set(m);
    this.carregar();
  }

  paginaAnterior(): void { this.pagina.update(p => Math.max(0, p - 1)); }
  paginaSeguinte(): void { this.pagina.update(p => Math.min(this.totalPaginas() - 1, p + 1)); }

  /**
   * Baixa o XLSX do mês — as três categorias de funcionários numa tabela única, em ordem
   * alfabética, independente da categoria exibida na tela.
   *
   * A falha do DOWNLOAD vai para o toast, não para o signal `erro` — que é o do primeiro
   * `@if` do template e responde pelas cargas da GRADE. Escrevendo nele, um 500 no XLSX trocava a
   * grade inteira (ainda carregada em memória) por uma caixa de erro, e só mudar de mês a trazia
   * de volta. Canais separados: o que falhou foi o arquivo, não a tela.
   */
  baixarTabela(): void {
    const { ano, mes } = this.anoMes();
    this.api.getBlob('/api/admin/ponto/retificacoes/grade/xlsx',
      { ano: String(ano), mes: String(mes) }).subscribe({
      next: blob => this.api.baixarBlob(blob, 'Ponto NUSP.xlsx'),
      error: () => this.toast.error('Erro ao baixar a tabela.'),
    });
  }

  abrirConfigurarTipos(): void {
    this.configurarTiposAberto.set(true);
  }

  /**
   * O catálogo mudou: excluir um tipo levou as marcações dele junto, então tanto a
   * grade exibida quanto a lista de tipos precisam ser relidas do servidor.
   */
  onTiposAlterados(): void {
    this.carregar();
    this.carregarCatalogo();
  }

  // ── Popover de ocorrências ───────────────────────────────────

  /** Fim de semana não recebe ocorrência pela tela (o cinza da linha esconderia a marca). */
  diaClicavel(d: DiaInfo): boolean {
    return !d.fim_semana;
  }

  /**
   * A célula do funcionário só aceita ocorrência quando não há nada que prevaleça
   * sobre ela: fim de semana, qualquer retificação, folga aprovada ou uma
   * ocorrência geral no dia — nesses casos a marcação ficaria gravada e invisível.
   */
  celulaClicavel(pessoaId: string, d: DiaInfo): boolean {
    if (d.fim_semana || d.marcacao_global_id) return false;
    const tipo = this.celula(pessoaId, d.dia)?.tipo;
    return tipo !== 'horarios' && tipo !== 'banco' && tipo !== 'ocorrencia';
  }

  chaveDia(dia: number): string { return `dia|${dia}`; }
  chaveCelula(pessoaId: string, dia: number): string { return `${pessoaId}|${dia}`; }

  /** Nome do funcionário do popover aberto — cabeçalho da lista de opções. */
  nomeDoAlvo(): string {
    const id = this.alvo()?.pessoaId;
    return this.funcionarios().find(f => f.id === id)?.nome ?? '';
  }

  abrirNoDia(d: DiaInfo, ev: MouseEvent): void {
    if (!this.diaClicavel(d) || this.salvandoEm()) return;
    this.abrir({
      escopo: 'dia', dia: d.dia, data: d.data, pessoaId: null,
      atual: d.marcacao_global_id, ...ancoraDoClique(ev),
    }, ev);
  }

  abrirNaCelula(f: Funcionario, d: DiaInfo, ev: MouseEvent): void {
    if (!this.celulaClicavel(f.id, d) || this.salvandoEm()) return;
    const c = this.celula(f.id, d.dia);
    this.abrir({
      escopo: 'pessoa', dia: d.dia, data: d.data, pessoaId: f.id,
      atual: c?.tipo === 'marcacao_pessoa' ? c.tipo_id ?? null : null, ...ancoraDoClique(ev),
    }, ev);
  }

  private abrir(alvo: AlvoOcorrencia, ev: MouseEvent): void {
    ev.stopPropagation();   // senão o mesmo clique chega ao document e fecha o que acabou de abrir
    this.confirmacao.set(null);
    this.alvo.set(alvo);
  }

  fecharPopover(): void {
    this.alvo.set(null);
    this.confirmacao.set(null);
  }

  @HostListener('document:click')
  onCliqueFora(): void {
    if (this.alvo()) this.fecharPopover();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.alvo()) this.fecharPopover();
  }

  /**
   * Escolher uma opção grava na hora. A do DIA vale para todos os funcionários do mês
   * (inclusive os das outras páginas), então passa por uma confirmação no próprio
   * popover — tanto para aplicar quanto para remover, o alcance é o mesmo.
   */
  escolher(o: OpcaoOcorrencia): void {
    const a = this.alvo();
    if (!a || this.salvandoEm()) return;
    if (o.id === a.atual) { this.fecharPopover(); return; }   // já é o estado da célula

    if (a.escopo === 'dia') {
      const nome = o.id ? o.rotulo : this.rotuloAtual(a);
      this.confirmacao.set({
        pergunta: o.id
          ? `Aplicar "${nome}" para todos os funcionários?`
          : `Remover "${nome}" de todos os funcionários?`,
        opcao: o,
      });
      return;
    }
    this.salvar(o);
  }

  confirmar(): void {
    const c = this.confirmacao();
    if (c) this.salvar(c.opcao);
  }

  cancelarConfirmacao(): void {
    this.confirmacao.set(null);
  }

  /** Nome do tipo hoje marcado no alvo — usado na pergunta da remoção coletiva. */
  private rotuloAtual(a: AlvoOcorrencia): string {
    return this.tipos().find(t => t.id === a.atual)?.nome ?? 'a ocorrência';
  }

  private salvar(o: OpcaoOcorrencia): void {
    const a = this.alvo();
    // A trava fica aqui, no único ponto que grava: o [disabled] do botão não impede o handler de rodar
    // (duplo clique impaciente no "Confirmar" mandaria dois lotes).
    if (!a || this.salvandoEm()) return;
    const chave = a.escopo === 'dia' ? this.chaveDia(a.dia) : this.chaveCelula(a.pessoaId!, a.dia);
    const body = a.escopo === 'dia' ? corpoGlobal(a.data, o.id)
                                    : corpoPessoal(a.data, o.id, a.pessoaId!, CATEGORIA_TIPO[this.categoria()]);

    const seq = this.seqContexto;
    this.salvandoEm.set(chave);
    this.api.put('/api/admin/ponto/marcacoes', body).subscribe({
      next: () => {
        if (seq !== this.seqContexto) return;   // outra grade está na tela: esta recarga não é dela
        this.salvandoEm.set(null);
        this.fecharPopover();
        this.carregar(true);                    // mesma lista de funcionários: a página exibida fica
      },
      error: err => {
        if (seq !== this.seqContexto) return;
        this.salvandoEm.set(null);
        this.fecharPopover();
        // Canal de AÇÃO: a grade continua na tela; o que falhou foi a gravação.
        this.toast.error(httpErrorMsg(err, 'Erro ao salvar a ocorrência.'));
      },
    });
  }

  /** Tipos do catálogo, buscados junto da grade — o popover abre sem esperar rede. */
  private carregarCatalogo(): void {
    this.api.get<any>('/api/admin/ponto/tipos-marcacao').subscribe({
      next: res => { this.tipos.set(res.data?.tipos ?? []); this.catalogoErro.set(false); },
      error: () => { this.tipos.set([]); this.catalogoErro.set(true); },
    });
  }

  /**
   * Relê a grade do servidor. `preservarPagina` vale para a recarga que vem de uma gravação: a
   * lista de funcionários é a mesma, e voltar para a primeira página tiraria o admin de onde ele
   * está marcando. Trocar de categoria/mês, ao contrário, troca a lista inteira.
   */
  private carregar(preservarPagina = false): void {
    const { ano, mes } = this.anoMes();
    const seq = ++this.seqContexto;   // daqui em diante, respostas anteriores (carga ou gravação) não valem
    this.salvandoEm.set(null);
    this.fecharPopover();
    this.carregando.set(true);
    this.erro.set('');
    if (!preservarPagina) this.pagina.set(0);
    // O catálogo é de sessão, mas se ele falhou o popover fica sem opções: cada recarga tenta de novo.
    if (this.catalogoErro()) this.carregarCatalogo();
    this.api.get<any>('/api/admin/ponto/retificacoes/grade', { categoria: this.categoria(), ano, mes }).subscribe({
      next: res => {
        if (seq !== this.seqContexto) return;   // uma carga mais nova está em voo: esta já é passado
        this.grade.set(res.data ?? null);
        this.carregando.set(false);
      },
      error: err => {
        if (seq !== this.seqContexto) return;
        this.grade.set(null);
        this.carregando.set(false);
        this.erro.set(httpErrorMsg(err, 'Erro ao carregar a grade.'));
      },
    });
  }
}

/** Colunas de funcionários por página (B-3.9/F#2: 8 exatos = 1 página). */
const PAGE_SIZE = 8;

const CATEGORIA_TIPO: Record<Categoria, string> = {
  operadores: 'OPERADOR', tecnicos: 'TECNICO', administradores: 'ADMINISTRADOR',
};

/** Lote de um item só: aplicar o tipo no dia de todos, ou desmarcá-lo. */
function corpoGlobal(data: string, tipoId: string | null): unknown {
  return tipoId ? { globais: { aplicar: [{ data, tipo_id: tipoId }] } }
                : { globais: { remover: [data] } };
}

/** Lote de um item só para um funcionário (o par pessoa_id/pessoa_tipo é polimórfico). */
function corpoPessoal(data: string, tipoId: string | null, pessoaId: string, pessoaTipo: string): unknown {
  const pessoa = { pessoa_id: pessoaId, pessoa_tipo: pessoaTipo };
  return tipoId ? { pessoais: { ...pessoa, aplicar: [{ data, tipo_id: tipoId }] } }
                : { pessoais: { ...pessoa, remover: [data] } };
}

