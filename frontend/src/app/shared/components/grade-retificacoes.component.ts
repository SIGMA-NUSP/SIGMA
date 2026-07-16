import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { MESES } from '../../core/helpers/table.helpers';
import { MesAno, MesAnoSelectorComponent, anosNavegaveis } from './mes-ano-selector.component';
import { DiaEstado, MiniCalendarioComponent } from './mini-calendario.component';
import { ErroCargaComponent } from './erro-carga.component';
import { ToastService } from './toast.component';

/** Categoria exibida no combobox (B-3.1) — casa com o PESSOA_TIPO no backend. */
type Categoria = 'operadores' | 'tecnicos' | 'administradores';

/** Célula já resolvida pela precedência do §1 no backend (E10 passo 1). */
interface Celula {
  tipo: 'horarios' | 'banco' | 'marcacao_pessoa' | 'marcacao_global';
  texto: string;
  tem_obs: boolean;
  obs?: string;
}
interface DiaInfo {
  dia: number;
  data: string;          // YYYY-MM-DD
  dow: number;           // 1=segunda … 7=domingo
  fim_semana: boolean;
  marcacao_global: string | null;
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
 * Card admin "Retificações" (E10, B-2/B-3): grade mensal dia × funcionário. O
 * backend entrega o payload já com a precedência do §1 resolvida por célula
 * (horários → banco → marcação pessoa-dia → marcação global → vazia); aqui é só
 * apresentação. Paleta da referência 7.2.5: célula preenchida em dia útil =
 * amarelo + fonte vermelha, vazia = azul-claro, linha de fim de semana = cinza
 * (independentemente do conteúdo — B-3.4). 1ª coluna fixa (sticky — B-3.10,
 * gotcha 7) e paginação client-side de 8 colunas de funcionários (B-3.9/F#2).
 *
 * TODO(E10.3/Q1 — fora da v1): clicar numa célula para abrir/editar a
 * retificação daquele (funcionário, dia); edição de retificação existente.
 */
@Component({
  selector: 'app-grade-retificacoes',
  standalone: true,
  imports: [MesAnoSelectorComponent, MiniCalendarioComponent, ErroCargaComponent],
  template: `
    <!-- B-3.1: combobox categoria · seletor mês/ano · Configurar -->
    <div class="barra">
      <select class="sel-cat" [value]="categoria()" (change)="onCategoria($event)" aria-label="Categoria">
        <option value="operadores">Operadores</option>
        <option value="tecnicos">Técnicos</option>
        <option value="administradores">Administradores</option>
      </select>

      <!-- [F37]: em janeiro a grade precisa alcançar dezembro (folha publicada, prazo correndo) -->
      <app-mes-ano-selector [anos]="anosSeletor" (mudou)="onMesAno($event)" />

      <button type="button" class="btn-outline" (click)="abrirConfigurar()">Configurar</button>
      <button type="button" class="btn-outline" (click)="baixarTabela()" [disabled]="funcionarios().length === 0">Baixar tabela</button>
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
                <td class="sticky-col rot-dia">{{ d.dia }}-{{ mesAbrev() }}</td>
                @for (f of funcsPagina(); track f.id) {
                  @let c = celula(f.id, d.dia);
                  <td class="cel" [class.preenchida]="!!c" [class.vazia]="!c" [class.tem-obs]="c?.tem_obs"
                      [title]="c?.obs || null">
                    @if (c) {
                      @if (c.tipo === 'horarios') {
                        @for (linha of horariosLinhas(c.texto); track $index) { <span class="hl">{{ linha }}</span> }
                      } @else {
                        {{ c.texto }}
                      }
                      @if (c.tem_obs) { <span class="marca-obs" aria-label="Possui observação"></span> }
                    }
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    <!-- B-4: modal "Configurar" (Alternativa 1 da análise) — marcações globais/pessoa-dia (Q36) -->
    @if (configurarAberto()) {
      <div class="modal-overlay" (click)="fecharConfigurar()">
        <div class="modal-card card-custom" (click)="$event.stopPropagation()">
          <h2 class="modal-title">Configurar dias — {{ tituloMesAno() }}</h2>

          @if (carregandoConfig()) {
            <p class="text-muted-sm">Carregando marcações...</p>
          } @else {
            <!-- F#4: escopo "Todos" (globais) | um funcionário (pessoa-dia) -->
            <div class="form-row">
              <label>Funcionário</label>
              <select [value]="escopo()" (change)="onEscopo($event)">
                <option value="todos">Todos (Feriado / P. Facultativo)</option>
                @for (f of funcionarios(); track f.id) {
                  <option [value]="f.id">{{ f.nome }}</option>
                }
              </select>
            </div>

            <!-- Modos: dependem do escopo -->
            <div class="modos">
              @for (m of modosDisponiveis(); track m.valor) {
                <button type="button" class="chip" [class.active]="modo() === m.valor" (click)="modo.set(m.valor)">{{ m.rotulo }}</button>
              }
            </div>

            <!-- Calendário estendido do E4 (multi-seleção + badges por dia) -->
            <div class="cfg-cal">
              <app-mini-calendario
                [multiSelecao]="true"
                [estadoDia]="estadoDiaConfig"
                [valorSelecionado]="primeiroDiaMes()"
                [min]="primeiroDiaMes()"
                [max]="ultimoDiaMes()"
                (dataSelecionada)="onDiaConfig($event)" />
            </div>

            <p class="legenda text-muted-sm">
              Selecione um modo e clique nos dias para aplicar/remover (clicar de novo no mesmo tipo remove). Fins de semana ficam desabilitados.
            </p>

            @if (erroConfig()) {
              @if (configCarregado()) {
                <!-- erro do APLICAR: o retry dele é repetir o Aplicar, que segue habilitado -->
                <div class="error-box">{{ erroConfig() }}</div>
              } @else {
                <!-- erro da CARGA: com o Aplicar travado (F41), sem retry aqui o modal seria um beco
                     sem saída — o usuário teria de adivinhar que fechar e reabrir recarrega. -->
                <app-erro-carga [mensagem]="erroConfig()" (tentarNovamente)="carregarMarcacoes()" />
              }
            }

            <div class="modal-actions" style="gap:8px">
              <button type="button" class="btn-outline" (click)="fecharConfigurar()">Cancelar</button>
              <!-- F41: sem a carga do modal bem-sucedida, o Aplicar não age — ele calcula um diff
                   contra as marcações do mês, e um diff sobre estado desconhecido REMOVE dias que
                   ninguém pediu para remover. (O erro do PUT NÃO desabilita: repetir é o retry.) -->
              <button type="button" class="btn-primary-custom"
                      [disabled]="aplicandoConfig() || !configCarregado()" (click)="aplicarConfig()">
                {{ aplicandoConfig() ? 'Aplicando...' : 'Aplicar' }}
              </button>
            </div>
          }
        </div>
      </div>
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
    .rot-dia { background: #DEEAF6; color: var(--text); font-weight: 500; text-align: left; }

    .cel-folgas { background: #434343; color: #fff; font-weight: 600; }

    /* Grade (7.2.5): preenchida em dia útil = amarelo + vermelho; vazia = azul-claro */
    .cel { position: relative; }
    .cel.preenchida { background: #FFFF00; color: #FF0000; font-weight: 600; }
    .cel.vazia { background: #DEEAF6; }

    /* Marca de observação: triângulo vermelho no canto superior direito (B-3.7/Q25) */
    .marca-obs {
      position: absolute; top: 0; right: 0; width: 0; height: 0;
      border-top: 7px solid #b91c1c; border-left: 7px solid transparent;
    }

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

    /* Configurar (B-4) */
    .modos { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
    .chip {
      border: 1px solid var(--border); background: #fff; border-radius: 999px;
      padding: 5px 12px; font-size: .85rem; cursor: pointer; color: var(--text);
    }
    .chip.active { background: var(--primary); color: #fff; border-color: var(--primary); }
    .cfg-cal { display: flex; justify-content: center; margin: 6px 0; }
    .legenda { text-align: center; margin: 8px 0 0; }

    @media (max-width: 640px) {
      .barra { gap: 8px; }
      .sel-cat { min-width: 0; flex: 1; }
    }
  `],
})
export class GradeRetificacoesComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  private hoje = new Date();
  categoria = signal<Categoria>('operadores');
  anoMes = signal<MesAno>({ ano: this.hoje.getFullYear(), mes: this.hoje.getMonth() + 1 });
  /** Anos ofertados no seletor (F37/C14): a virada do ano só se abre em dezembro e janeiro. */
  readonly anosSeletor = anosNavegaveis(this.hoje);

  grade = signal<GradeData | null>(null);
  carregando = signal(false);
  erro = signal('');
  pagina = signal(0);

  // ── Configurar (B-4): modal de marcações globais/pessoa-dia ──
  configurarAberto = signal(false);
  carregandoConfig = signal(false);
  aplicandoConfig = signal(false);
  erroConfig = signal('');
  /**
   * A carga do modal SUCEDEU para o mês/escopo exibido (F41). Gate do Aplicar: ele monta um diff
   * (aplicar/remover) contra `marcacoesOriginal`, e um diff sobre estado que não veio do backend
   * manda remoções fantasmas. Signal PRÓPRIO (e não `!erroConfig()`): o `erroConfig` também
   * carrega o erro do PUT, e o Aplicar precisa continuar clicável nesse ramo — é o retry dele.
   */
  configCarregado = signal(false);
  escopo = signal<string>('todos');                 // 'todos' (globais) | pessoaId (pessoa-dia)
  modo = signal<string>('FERIADO');                 // tipo ativo | '__limpar'
  private globaisRaw = signal<{ data: string; tipo: string }[]>([]);
  private pessoaisRaw = signal<{ pessoa_id: string; data: string; tipo: string }[]>([]);
  private marcacoesOriginal = signal<Map<number, string>>(new Map());
  marcacoesEscopo = signal<Map<number, string>>(new Map());
  /**
   * Token da SESSÃO do modal (F61/idioma C8): incrementa a cada abertura e vale para as DUAS
   * requisições do Configurar — o GET das marcações e o PUT do Aplicar. Sem ele, a resposta de uma
   * sessão anterior age sobre a sessão nova: a do GET repovoa o calendário com o mês abandonado; a
   * do PUT **fecha o modal que o usuário acabou de abrir** (no `next`) ou pinta um erro fantasma de
   * uma ação que ele não repetiu (no `error`).
   */
  private seqModal = 0;

  primeiroDiaMes = computed(() => new Date(this.anoMes().ano, this.anoMes().mes - 1, 1));
  ultimoDiaMes = computed(() => new Date(this.anoMes().ano, this.anoMes().mes, 0));
  tituloMesAno = computed(() => `${MESES[this.anoMes().mes]} de ${this.anoMes().ano}`);
  modosDisponiveis = computed(() => this.escopo() === 'todos' ? MODOS_GLOBAIS : MODOS_PESSOAIS);

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
  }

  /** Célula (funcionário, dia) já resolvida pela precedência no backend; ausente = vazia. */
  celula(pessoaId: string, dia: number): Celula | undefined {
    return this.grade()?.celulas?.[pessoaId]?.[dia];
  }

  /** Horários (2/4) agrupados em pares Ent./Saí. — cada par numa linha, para a coluna caber estreita. */
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
   * Baixa o XLSX do mês/categoria (B-5.1) — nome ponto_{categoria}_{AAMM}.xlsx (Q31).
   *
   * F38: a falha do DOWNLOAD vai para o toast, não para o signal `erro` — que é o do primeiro
   * `@if` do template e responde pelas cargas da GRADE. Escrevendo nele, um 500 no XLSX trocava a
   * grade inteira (ainda carregada em memória) por uma caixa de erro, e só mudar de mês a trazia
   * de volta. Canais separados: o que falhou foi o arquivo, não a tela.
   */
  baixarTabela(): void {
    const { ano, mes } = this.anoMes();
    const cat = this.categoria();
    this.api.getBlob('/api/admin/ponto/retificacoes/grade/xlsx',
      { categoria: cat, ano: String(ano), mes: String(mes) }).subscribe({
      next: blob => {
        const aamm = String(ano % 100).padStart(2, '0') + String(mes).padStart(2, '0');
        this.api.baixarBlob(blob, `ponto_${cat}_${aamm}.xlsx`);
      },
      error: () => this.toast.error('Erro ao baixar a tabela.'),
    });
  }

  // ── Configurar (B-4) ──────────────────────────────────────────

  /**
   * Estado por-dia do calendário do Configurar. Referência estável (arrow) que lê
   * marcacoesEscopo/anoMes: esses signals são rastreados pelo computed do
   * MiniCalendario, então (des)marcar um dia re-renderiza sem trocar a função.
   */
  readonly estadoDiaConfig = (d: Date): DiaEstado | null => {
    const am = this.anoMes();
    if (d.getFullYear() !== am.ano || d.getMonth() !== am.mes - 1) return { desabilitado: true };
    const dow = d.getDay();
    if (dow === 0 || dow === 6) return { desabilitado: true };   // fim de semana desabilitado (Alternativa 1)
    const tipo = this.marcacoesEscopo().get(d.getDate());
    if (tipo) return { selecionado: true, badge: BADGE[tipo], rotulo: ROTULO[tipo] };
    return null;
  };

  /**
   * Abre o modal ZERANDO todo o estado antes de carregar (F41). Antes, só o ramo de SUCESSO da
   * carga repovoava o calendário (via `aplicarEscopo`): abrir em junho, fechar, ir para julho e
   * reabrir com o GET falhando deixava as marcações de JUNHO na tela — e o Aplicar montava o ISO
   * com o mês CORRENTE, de modo que um feriado de 05/06 virava `remover: ['2026-07-05']`.
   */
  abrirConfigurar(): void {
    this.seqModal++;                    // nova sessão: respostas (GET e PUT) da anterior morrem aqui
    this.globaisRaw.set([]);
    this.pessoaisRaw.set([]);
    this.marcacoesOriginal.set(new Map());
    this.marcacoesEscopo.set(new Map());
    this.escopo.set('todos');
    this.modo.set('FERIADO');
    this.configCarregado.set(false);
    this.aplicandoConfig.set(false);    // senão o modal reabre com o Aplicar preso em "Aplicando..."
    this.erroConfig.set('');
    this.configurarAberto.set(true);
    this.carregarMarcacoes();
  }

  fecharConfigurar(): void {
    this.configurarAberto.set(false);
  }

  onEscopo(ev: Event): void {
    const sel = ev.target as HTMLSelectElement;
    const novo = sel.value;
    if (novo === this.escopo()) return;
    if (this.temMudancasPendentes() &&
        !confirm('Há marcações não aplicadas. Trocar de funcionário vai descartá-las. Continuar?')) {
      sel.value = this.escopo();
      return;
    }
    this.aplicarEscopo(novo);
  }

  /** Aplica/remove o modo ativo no dia clicado (toggle: mesmo tipo → remove). */
  onDiaConfig(d: Date): void {
    const dia = d.getDate();
    const mapa = new Map(this.marcacoesEscopo());
    const modo = this.modo();
    if (modo === '__limpar') mapa.delete(dia);
    else if (mapa.get(dia) === modo) mapa.delete(dia);
    else mapa.set(dia, modo);
    this.marcacoesEscopo.set(mapa);
  }

  /** Envia o diff (aplicar/remover) do escopo atual ao PUT do E6 e recarrega a grade. */
  aplicarConfig(): void {
    // F41: sem carga bem-sucedida não há diff possível. O botão já está desabilitado; este guard é a
    // 2ª camada (lição do C9: o [disabled] do template não garante que o handler não roda).
    if (!this.configCarregado()) return;
    const orig = this.marcacoesOriginal();
    const edit = this.marcacoesEscopo();
    const { ano, mes } = this.anoMes();
    const iso = (dia: number) => `${ano}-${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`;
    const aplicar: { data: string; tipo: string }[] = [];
    const remover: string[] = [];
    for (const [dia, tipo] of edit) if (orig.get(dia) !== tipo) aplicar.push({ data: iso(dia), tipo });
    for (const [dia] of orig) if (!edit.has(dia)) remover.push(iso(dia));
    if (aplicar.length === 0 && remover.length === 0) { this.fecharConfigurar(); return; }

    const body = this.escopo() === 'todos'
      ? { globais: { aplicar, remover } }
      : { pessoais: { pessoa_id: this.escopo(), pessoa_tipo: CATEGORIA_TIPO[this.categoria()], aplicar, remover } };

    const seq = this.seqModal;   // a resposta só vale para ESTA sessão do modal
    this.aplicandoConfig.set(true);
    this.erroConfig.set('');
    this.api.put('/api/admin/ponto/marcacoes', body).subscribe({
      next: () => {
        if (seq !== this.seqModal) { this.carregar(); return; }   // o PUT gravou: a grade recarrega,
        this.aplicandoConfig.set(false);                          // mas ele não fecha o modal novo
        this.fecharConfigurar();
        this.carregar();
      },
      error: err => {
        if (seq !== this.seqModal) return;   // nada de erro fantasma numa sessão que não pediu o PUT
        this.aplicandoConfig.set(false);
        this.erroConfig.set(httpErrorMsg(err, 'Erro ao aplicar as marcações.'));
      },
    });
  }

  /** Recarrega as marcações do modal — usada na abertura e no retry da caixa de erro. */
  carregarMarcacoes(): void {
    const { ano, mes } = this.anoMes();
    const seq = this.seqModal;
    this.carregandoConfig.set(true);
    this.erroConfig.set('');
    this.api.get<any>('/api/admin/ponto/marcacoes', { ano, mes }).subscribe({
      next: res => {
        if (seq !== this.seqModal) return;    // obsoleta: uma reabertura mais nova está em voo
        const d = res.data ?? {};
        this.globaisRaw.set(d.globais ?? []);
        this.pessoaisRaw.set(d.pessoais ?? []);
        this.carregandoConfig.set(false);
        this.aplicarEscopo('todos');
        this.configCarregado.set(true);       // só agora o Aplicar sabe contra o que diferenciar
      },
      error: err => {
        if (seq !== this.seqModal) return;
        this.carregandoConfig.set(false);
        this.erroConfig.set(httpErrorMsg(err, 'Erro ao carregar as marcações.'));
      },
    });
  }

  /** Popula o mapa dia→tipo do escopo (globais ou pessoa) a partir do que veio do E6. */
  private aplicarEscopo(novo: string): void {
    this.escopo.set(novo);
    const mapa = new Map<number, string>();
    if (novo === 'todos') {
      for (const g of this.globaisRaw()) mapa.set(diaDeIso(g.data), g.tipo);
      this.modo.set('FERIADO');
    } else {
      for (const p of this.pessoaisRaw()) if (p.pessoa_id === novo) mapa.set(diaDeIso(p.data), p.tipo);
      this.modo.set('A_DISPOSICAO');
    }
    this.marcacoesOriginal.set(new Map(mapa));
    this.marcacoesEscopo.set(new Map(mapa));
  }

  private temMudancasPendentes(): boolean {
    const o = this.marcacoesOriginal(), e = this.marcacoesEscopo();
    if (o.size !== e.size) return true;
    for (const [dia, tipo] of e) if (o.get(dia) !== tipo) return true;
    return false;
  }

  private carregar(): void {
    const { ano, mes } = this.anoMes();
    this.carregando.set(true);
    this.erro.set('');
    this.pagina.set(0);
    this.api.get<any>('/api/admin/ponto/retificacoes/grade', { categoria: this.categoria(), ano, mes }).subscribe({
      next: res => { this.grade.set(res.data ?? null); this.carregando.set(false); },
      error: err => { this.grade.set(null); this.carregando.set(false); this.erro.set(httpErrorMsg(err, 'Erro ao carregar a grade.')); },
    });
  }
}

/** Colunas de funcionários por página (B-3.9/F#2: 8 exatos = 1 página). */
const PAGE_SIZE = 8;

// ── Configurar (B-4): modos por escopo, rótulos e badges das marcações ──
const MODOS_GLOBAIS = [
  { valor: 'FERIADO', rotulo: 'Feriado' },
  { valor: 'PONTO_FACULTATIVO', rotulo: 'P. Facultativo' },
  { valor: '__limpar', rotulo: 'Limpar' },
];
const MODOS_PESSOAIS = [
  { valor: 'A_DISPOSICAO', rotulo: 'À disposição' },
  { valor: 'ATESTADO', rotulo: 'Atestado' },
  { valor: 'FERIAS', rotulo: 'Férias' },
  { valor: 'RECESSO', rotulo: 'Recesso' },
  { valor: 'LICENCA_MEDICA', rotulo: 'Lic. médica' },
  { valor: '__limpar', rotulo: 'Limpar' },
];
/** Texto completo (title do dia) por tipo de marcação. */
const ROTULO: Record<string, string> = {
  FERIADO: 'Feriado', PONTO_FACULTATIVO: 'P. Facultativo',
  A_DISPOSICAO: 'À disposição', ATESTADO: 'Atestado', FERIAS: 'Férias',
  RECESSO: 'Recesso', LICENCA_MEDICA: 'Lic. médica',
};
/** Rótulo curto exibido sob o número no calendário. */
const BADGE: Record<string, string> = {
  FERIADO: 'Fer', PONTO_FACULTATIVO: 'P.Fac',
  A_DISPOSICAO: 'À disp', ATESTADO: 'Atest', FERIAS: 'Fér',
  RECESSO: 'Rec', LICENCA_MEDICA: 'Lic',
};
const CATEGORIA_TIPO: Record<Categoria, string> = {
  operadores: 'OPERADOR', tecnicos: 'TECNICO', administradores: 'ADMINISTRADOR',
};
/** Dia do mês (número) a partir de uma data ISO 'YYYY-MM-DD'. */
function diaDeIso(data: string): number { return parseInt(data.slice(8, 10), 10); }
