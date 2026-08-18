import { Component, ElementRef, HostListener, OnInit, computed, inject, signal, viewChildren } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { HORA_RE, HoraMaskDirective } from '../directives/hora-mask.directive';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { periodoFolha } from '../../core/helpers/table.helpers';
import { AncoraPopover, ancoraDoClique } from '../../core/helpers/popover.helpers';
import { ErroCargaComponent } from './erro-carga.component';
import { AjudaChatComponent } from './ajuda-chat.component';
import { ToastService } from './toast.component';

/** Guia da tela quando o que diz o que é editável não carrega: sem ele, nada se edita. */
const GUIA_JANELA = 'Não foi possível concluir a operação.';

/** As quatro batidas do dia, na ordem impressa na folha. */
type CampoHora = 'ent1' | 'sai1' | 'ent2' | 'sai2';
const CAMPOS: { campo: CampoHora; rotulo: string }[] = [
  { campo: 'ent1', rotulo: 'Ent. 1' },
  { campo: 'sai1', rotulo: 'Saí. 1' },
  { campo: 'ent2', rotulo: 'Ent. 2' },
  { campo: 'sai2', rotulo: 'Saí. 2' },
];

/** Uma linha da folha publicada, como o servidor a imprime. */
interface LinhaFolha {
  dia: string;
  ent1: string; sai1: string; ent2: string; sai2: string;
}

interface DadosFolha {
  id: string;
  tipo: string;
  data_inicio: string;
  data_fim: string;
  linhas: LinhaFolha[];
}

/** O que o servidor diz de cada dia do período: se ainda aceita correção e o que vale para todos. */
interface DiaJanela {
  data: string;                     // YYYY-MM-DD
  aberto: boolean;
  marcacao_global?: string | null;
  /** A ocorrência que a administração marcou só para esta pessoa — vem apenas nos tipos visíveis. */
  marcacao_pessoal?: string | null;
}

/** A correção já gravada de um dia. Os horários e o tipo são excludentes. */
interface RetifSalva {
  id: string;
  data: string;
  ent1?: string | null; sai1?: string | null; ent2?: string | null; sai2?: string | null;
  tipo_id?: string | null;
  tipo_nome?: string | null;
  conta_folga?: boolean;
}

/** Ocorrência que o funcionário pode declarar para o dia inteiro. */
interface TipoDeclaravel { id: string; nome: string; }

/** Uma célula de horário já resolvida: o que exibir e se veio da correção dele. */
interface CelulaDia {
  campo: CampoHora;
  rotulo: string;
  valor: string;
  corrigido: boolean;
}

/**
 * A ocorrência que ocupa o dia inteiro: a que ele declarou (verde), a individual que a
 * administração marcou (tracejada — dá para retificar por cima) ou a geral (fixa, dia bloqueado).
 */
interface FaixaDia { texto: string; declarada: boolean; clicavel: boolean; }

/** Uma linha da tela: o que a folha trouxe, o que ele corrigiu e o que o dia aceita. */
interface LinhaDia {
  dia: string;
  data: string;
  fimDeSemana: boolean;
  editavel: boolean;
  celulas: CelulaDia[];
  faixa: FaixaDia | null;
  corrigido: boolean;
  /** O dia tem ocorrência individual marcada pela administração — limpável como uma correção. */
  marcadoPeloAdmin: boolean;
}

/** A célula em que o menu está aberto, com o ponto da tela onde ele nasce. */
interface AlvoCelula extends AncoraPopover {
  data: string;
  campo: CampoHora;
  titulo: string;
  podeLimpar: boolean;
}

/** A célula sendo digitada agora — uma por vez em toda a tela. */
interface EmEdicao { data: string; campo: CampoHora; }

/**
 * Retificação de ponto — a planilha que o funcionário preenchia à mão, agora na tela.
 *
 * <p><b>A célula é a unidade.</b> Cada batida se corrige sozinha: clicar abre um menu, "Editar
 * horário" transforma a célula num campo, e o horário completo grava na hora. O que ele não tocou
 * continua valendo o que a folha oficial trouxe. Não há botão Salvar, não há par obrigatório e não
 * há envio em lote — errou, corrige de novo.
 *
 * <p><b>Ou horários, ou uma ocorrência.</b> O mesmo menu declara uma ocorrência para o dia inteiro
 * ("Banco de horas"), que substitui os horários, e apaga a correção do dia, devolvendo-o à folha.
 *
 * <p><b>O que o dia aceita</b> vem do servidor: sábado e domingo nunca, o dia que o administrador
 * marcou para todos também não, e o resto fica aberto até a folha mensal definitiva do período ser
 * publicada. Sem essa resposta, NADA é editável: uma tela que deixasse digitar e recusasse na
 * gravação seria pior do que uma tela travada.
 *
 * <p><b>A ocorrência individual marcada pela administração</b> (quando o tipo é dos que o
 * funcionário declara) aparece como faixa do dia inteiro, tracejada: o dia continua editável, e
 * corrigir um horário ou declarar outra ocorrência é o caminho de quem discorda da marcação.
 */
@Component({
  selector: 'app-ponto-retificar',
  standalone: true,
  imports: [RouterLink, HoraMaskDirective, ErroCargaComponent, AjudaChatComponent],
  template: `
    <h1>Retificação de Ponto</h1>
    <div class="topo-bar">
      <a [routerLink]="voltarLink" class="back-link">&larr; Voltar</a>
    </div>

    @if (loading()) {
      <p class="text-muted-sm">Carregando folha...</p>
    } @else if (erro()) {
      <div class="error-box">{{ erro() }}</div>
    } @else {
      <p class="text-muted-sm periodo">Folha {{ tipoLabel() }} — {{ periodoFolhaLabel() }}</p>

      @if (carregandoJanela()) {
        <p class="text-muted-sm">Verificando o que ainda pode ser corrigido...</p>
      } @else if (erroJanela()) {
        <app-erro-carga [mensagem]="erroJanela()" (tentarNovamente)="recarregarJanela()" />
      }

      <!-- Desktop: a folha inteira em tabela, uma célula por batida -->
      <div class="table-container vista-desktop">
        <table class="data-table ponto-table">
          <thead><tr>
            <th>DIA</th>
            <th>ENT. 1</th><th>SAÍ. 1</th><th>ENT. 2</th><th>SAÍ. 2</th>
          </tr></thead>
          <tbody>
            @for (l of linhas(); track l.dia) {
              <tr [class.bloqueada]="!l.editavel" [class.editando]="editandoNoDia(l.data)">
                <td class="col-dia">
                  <strong>{{ l.dia }}</strong>
                  @if (!l.editavel) { <span class="cadeado" title="Dia não editável" aria-label="Dia não editável">🔒</span> }
                </td>

                <!-- Em edição, a faixa cede o lugar às células: é nelas que o campo de horário abre -->
                @if (!editandoNoDia(l.data) && l.faixa; as f) {
                  <td colspan="4" class="cel-faixa">
                    @if (f.clicavel && l.editavel) {
                      <button type="button" class="chip" [class.chip-editado]="f.declarada"
                              [class.chip-editavel]="!f.declarada"
                              [disabled]="salvandoNoDia(l.data)"
                              (click)="abrirMenuDoDia(l, $event)">{{ f.texto }}</button>
                    } @else {
                      <span class="chip" [class.chip-editado]="f.declarada" [class.chip-fixo]="!f.declarada">{{ f.texto }}</span>
                    }
                  </td>
                } @else {
                  @for (c of l.celulas; track c.campo) {
                    <td class="cel-hora">
                      @if (editandoCelula(l.data, c.campo)) {
                        <input #campoHora appHoraMask class="cel-input" [value]="rascunho()"
                               (horaChange)="aoDigitar(l, c, $event)"
                               (blur)="aoSair(l, c)" (keydown.escape)="cancelarEdicao()"
                               inputmode="numeric" maxlength="5" placeholder="HH:MM"
                               [attr.aria-label]="c.rotulo + ' de ' + l.dia">
                      } @else if (l.editavel) {
                        <button type="button" class="chip" [class.chip-editado]="c.corrigido"
                                [class.chip-editavel]="!c.corrigido"
                                [disabled]="salvandoNoDia(l.data)"
                                [attr.aria-label]="c.rotulo + ' de ' + l.dia"
                                (click)="abrirMenu(l, c, $event)">
                          {{ c.valor || '+' }}
                          @if (!c.corrigido) { <span class="lapis" aria-hidden="true">✎</span> }
                        </button>
                      } @else {
                        <span class="chip" [class.chip-editado]="c.corrigido" [class.chip-fixo]="!c.corrigido">{{ c.valor || '—' }}</span>
                      }
                    </td>
                  }
                }
              </tr>
            }
          </tbody>
        </table>
      </div>

      <!-- Celular: um card por dia, com as batidas em grade 2×2 -->
      <div class="vista-mobile">
        @for (l of linhas(); track l.dia) {
          <div class="dia-card" [class.bloqueado]="!l.editavel" [class.editando]="editandoNoDia(l.data)">
            <div class="card-topo">
              <strong>{{ l.dia }}</strong>
              @if (!l.editavel) { <span class="cadeado" aria-label="Dia não editável">🔒</span> }
            </div>

            @if (!editandoNoDia(l.data) && l.faixa; as f) {
              <div class="card-faixa">
                @if (f.clicavel && l.editavel) {
                  <button type="button" class="chip" [class.chip-editado]="f.declarada"
                          [class.chip-editavel]="!f.declarada"
                          [disabled]="salvandoNoDia(l.data)"
                          (click)="abrirMenuDoDia(l, $event)">{{ f.texto }}</button>
                } @else {
                  <span class="chip" [class.chip-editado]="f.declarada" [class.chip-fixo]="!f.declarada">{{ f.texto }}</span>
                }
              </div>
            } @else if (l.editavel || l.corrigido) {
              <div class="card-celulas">
                @for (c of l.celulas; track c.campo) {
                  <div class="cel-mobile">
                    <span class="lbl">{{ c.rotulo }}</span>
                    @if (editandoCelula(l.data, c.campo)) {
                      <input #campoHora appHoraMask class="cel-input" [value]="rascunho()"
                             (horaChange)="aoDigitar(l, c, $event)"
                             (blur)="aoSair(l, c)" (keydown.escape)="cancelarEdicao()"
                             inputmode="numeric" maxlength="5" placeholder="HH:MM"
                             [attr.aria-label]="c.rotulo + ' de ' + l.dia">
                    } @else if (l.editavel) {
                      <button type="button" class="chip" [class.chip-editado]="c.corrigido"
                              [class.chip-editavel]="!c.corrigido"
                              [disabled]="salvandoNoDia(l.data)"
                              [attr.aria-label]="c.rotulo + ' de ' + l.dia"
                              (click)="abrirMenu(l, c, $event)">
                        {{ c.valor || '+' }}
                        @if (!c.corrigido) { <span class="lapis" aria-hidden="true">✎</span> }
                      </button>
                    } @else {
                      <span class="chip" [class.chip-editado]="c.corrigido" [class.chip-fixo]="!c.corrigido">{{ c.valor || '—' }}</span>
                    }
                  </div>
                }
              </div>
            }
          </div>
        }
      </div>

      <div class="legenda">
        <span class="legenda-item"><span class="chip chip-editado amostra"></span> editado</span>
        <span class="legenda-item"><span class="chip chip-fixo amostra"></span> não editável</span>
      </div>
    }

    <!-- Menu da célula: as opções nascem ancoradas nela -->
    @if (menu(); as m) {
      <div class="popover" [class.acima]="m.acima" [style.left.px]="m.x" [style.top.px]="m.y"
           (click)="$event.stopPropagation()">
        <p class="pop-titulo">{{ m.titulo }}</p>
        <button type="button" class="pop-item" [disabled]="salvandoNoDia(m.data)" (click)="editarHorario()">
          Editar horário
        </button>
        @for (t of tipos(); track t.id) {
          <button type="button" class="pop-item" [disabled]="salvandoNoDia(m.data)" (click)="declarar(t)">
            {{ t.nome }}
          </button>
        }
        @if (m.podeLimpar) {
          <button type="button" class="pop-item pop-limpar" [disabled]="salvandoNoDia(m.data)" (click)="limpar()">
            Limpar retificação
          </button>
        }
      </div>
    }

    <!-- Chat de ajuda com IA — mesmo manual do /ponto; se auto-esconde sem a flag -->
    <app-ajuda-chat pagina="ponto-banco" titulo="Ajuda — Retificação de Ponto" />
  `,
  styles: [`
    .periodo { margin: 0 0 16px; }
    .topo-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .topo-bar .back-link { margin-bottom: 0; }
    .error-box { margin-top: 0; }

    .ponto-table td { font-variant-numeric: tabular-nums; vertical-align: middle; }
    .ponto-table .col-dia { white-space: nowrap; }
    .cadeado { margin-left: 6px; font-size: .8rem; opacity: .55; }
    .cel-hora, .cel-faixa { text-align: center; padding: 4px 6px; }

    /* A linha que não aceita edição fica visivelmente fora do jogo */
    tr.bloqueada td { background: #f1f5f9; color: #64748b; }
    tr.editando td { background: #eff6ff; }

    /* A célula que aceita edição: tracejado discreto + lápis; vazia mostra o "+" */
    .chip {
      display: inline-flex; align-items: center; justify-content: center; gap: 4px;
      min-width: 62px; min-height: 28px; padding: 2px 8px; border-radius: 6px;
      font-size: .875rem; font-variant-numeric: tabular-nums; line-height: 1.2;
      background: transparent; color: var(--text); border: 1px solid transparent;
    }
    .chip-editavel {
      border: 1px dashed #94a3b8; color: var(--text); cursor: pointer;
    }
    .chip-editavel:hover:not(:disabled) { background: #f8fafc; border-color: var(--primary); }
    .lapis { font-size: .7rem; opacity: .5; }
    .chip-editado {
      background: #ecfdf5; border: 1px solid #009739; color: #047857; font-weight: 500; cursor: pointer;
    }
    .chip-editado:disabled, .chip-editavel:disabled { opacity: .5; cursor: default; }
    .chip-fixo { color: #64748b; }
    .cel-faixa .chip, .card-faixa .chip { min-width: 140px; }

    /* Em edição: o campo toma o lugar da célula, sem botões de confirmar */
    .cel-input {
      width: 72px; height: 30px; text-align: center; font-variant-numeric: tabular-nums;
      border: 2px solid #003b63; border-radius: 6px; padding: 0 4px; font-size: .9rem;
    }

    .legenda {
      display: flex; gap: 18px; align-items: center; flex-wrap: wrap;
      margin: 14px 2px 0; font-size: .8rem; color: var(--muted);
    }
    .legenda-item { display: inline-flex; align-items: center; gap: 6px; }
    .amostra { min-width: 26px; min-height: 18px; padding: 0; }
    .legenda .amostra.chip-fixo { background: #f1f5f9; border: 1px solid #e2e8f0; }

    /* Menu da célula — mesmo idioma do popover de ocorrências da grade */
    .popover {
      position: fixed; z-index: 60; min-width: 190px; max-width: 260px;
      max-height: 280px; overflow-y: auto;
      background: #fff; border: 1px solid var(--border); border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0, 0, 0, .18); padding: 6px;
    }
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
    .pop-item:disabled { opacity: .5; cursor: default; }
    .pop-limpar { color: #b91c1c; }

    /* ───── Responsivo: tabela no desktop, um card por dia no celular ───── */
    .vista-mobile { display: none; }
    @media (max-width: 640px) {
      .vista-desktop { display: none; }
      .vista-mobile { display: flex; flex-direction: column; gap: 8px; }

      .dia-card {
        display: flex; flex-direction: column; gap: 8px;
        border: 1px solid var(--border); border-radius: 10px; padding: 10px;
      }
      .dia-card.bloqueado { background: #f1f5f9; border-color: #e2e8f0; }
      .dia-card.editando { background: #eff6ff; border-color: var(--primary); }
      .card-topo { display: flex; align-items: center; }
      .card-topo strong { font-size: .85rem; color: var(--primary); }
      .dia-card.bloqueado .card-topo strong { color: #64748b; }

      .card-celulas { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
      .cel-mobile { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
      .cel-mobile .lbl { font-size: .62rem; font-weight: 600; color: #64748b; }
      .cel-mobile .chip { width: 100%; }
      .cel-input { width: 100%; box-sizing: border-box; }
    }
  `],
})
export class PontoRetificarComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private toast = inject(ToastService);

  /**
   * O campo em edição. A folha é desenhada duas vezes — tabela e cards —, e só uma delas está à
   * vista; o foco procura a que o leitor está usando.
   */
  private readonly camposHora = viewChildren<ElementRef<HTMLInputElement>>('campoHora');

  /** Todos (inclusive o admin, via card "Meu Ponto e Banco") chegam aqui pelo /ponto. */
  readonly voltarLink = '/ponto';

  folha = signal<DadosFolha | null>(null);
  loading = signal(true);
  erro = signal('');

  /** O que o servidor respondeu sobre os dias, as correções já feitas e as ocorrências ofertadas. */
  private janela = signal<Record<string, DiaJanela>>({});
  private correcoes = signal<Record<string, RetifSalva>>({});
  tipos = signal<TipoDeclaravel[]>([]);
  /** A resposta chegou: é o que destrava a edição. Sem ela, a folha é só leitura. */
  private janelaCarregada = signal(false);
  carregandoJanela = signal(false);
  erroJanela = signal('');

  /** Célula com o menu aberto; nula = nenhum. */
  menu = signal<AlvoCelula | null>(null);
  /** Célula sendo digitada; nula = nenhuma. */
  private edicao = signal<EmEdicao | null>(null);
  /** O que está no campo agora — o valor de partida vem da folha ou da correção. */
  rascunho = signal('');
  /** Dias com gravação em voo: enquanto ela existe, AQUELE dia não aceita novo gesto (os outros sim). */
  private salvandoEm = signal<ReadonlySet<string>>(new Set());

  /**
   * Token de recência: uma carga nova invalida o que estiver em voo. Sem ele, a resposta de um
   * "Tentar novamente" antigo poderia desfazer o que a carga mais nova já mostrou.
   */
  private seq = 0;

  /** A folha como a tela a desenha: o que veio impresso, o que ele corrigiu e o que o dia aceita. */
  linhas = computed<LinhaDia[]>(() => {
    const folha = this.folha();
    if (!folha) return [];
    const janela = this.janela();
    const correcoes = this.correcoes();
    const podeEditar = this.janelaCarregada();

    return (folha.linhas || []).map(l => {
      const data = dataISO(l.dia);
      const dia = janela[data];
      const correcao = correcoes[data];
      const fimDeSemana = ehFimDeSemana(data);
      const marcacao = dia?.marcacao_global ?? null;
      const marcacaoPessoal = dia?.marcacao_pessoal ?? null;
      const editavel = podeEditar && !!dia?.aberto && !fimDeSemana && !marcacao;

      const celulas = CAMPOS.map(({ campo, rotulo }) => {
        const corrigido = !!correcao && !correcao.tipo_id && !!correcao[campo];
        return {
          campo,
          rotulo,
          valor: corrigido ? correcao[campo]! : (l[campo] || ''),
          corrigido,
        };
      });

      // A ocorrência declarada ocupa o dia inteiro. As marcações do administrador também, mas só
      // enquanto ele não tiver corrigido horário nenhum ali: a correção vence a marcação na grade,
      // e escondê-la aqui faria a tela contar uma história diferente da que a chefia enxerga.
      // A geral bloqueia o dia; a individual não — quem discorda dela retifica por cima.
      const semCorrecao = !celulas.some(c => c.corrigido);
      const faixa: FaixaDia | null = correcao?.tipo_nome
        ? { texto: correcao.tipo_nome, declarada: true, clicavel: true }
        : marcacao && semCorrecao ? { texto: marcacao, declarada: false, clicavel: false }
          : marcacaoPessoal && semCorrecao
            ? { texto: marcacaoPessoal, declarada: false, clicavel: true } : null;

      return {
        dia: l.dia, data, fimDeSemana, editavel, celulas, faixa,
        corrigido: !!correcao,
        marcadoPeloAdmin: !!marcacaoPessoal,
      };
    });
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('paginaId');
    if (!id) { this.erro.set('Folha não informada.'); this.loading.set(false); return; }
    this.api.get<any>(`/api/ponto/folha/${id}/dados`).subscribe({
      next: res => {
        this.folha.set(res.data);
        this.loading.set(false);
        this.carregarJanela(id);
      },
      error: err => {
        this.erro.set(httpErrorMsg(err, 'Não foi possível carregar a folha.', ['error', 'message']));
        this.loading.set(false);
      },
    });
  }

  /** Retry da caixa de erro — o único gesto que devolve a edição depois de uma falha de carga. */
  recarregarJanela(): void {
    const id = this.folha()?.id ?? this.route.snapshot.paramMap.get('paginaId');
    if (id) this.carregarJanela(id);
  }

  /**
   * Traz o que o servidor sabe do período: quais dias aceitam correção, o que já foi corrigido e
   * quais ocorrências ele pode declarar.
   *
   * <p>Enquanto isto não chega — ou se falhar — a folha fica só leitura. Deixar digitar sem saber
   * o que o dia aceita produziria a pior tela possível: a que aceita e depois recusa.
   */
  private carregarJanela(paginaId: string): void {
    const seq = ++this.seq;
    this.fecharMenu();
    this.edicao.set(null);
    this.salvandoEm.set(new Set());
    this.erroJanela.set('');
    this.janelaCarregada.set(false);
    this.carregandoJanela.set(true);
    this.api.get<any>(`/api/ponto/folha/${paginaId}/retificacoes`).subscribe({
      next: res => {
        if (seq !== this.seq) return;
        const d = res.data || {};
        this.janela.set(porData<DiaJanela>(d.dias || []));
        this.correcoes.set(porData<RetifSalva>(d.retificacoes || []));
        this.tipos.set(d.tipos || []);
        this.carregandoJanela.set(false);
        this.janelaCarregada.set(true);
      },
      error: err => {
        if (seq !== this.seq) return;
        this.carregandoJanela.set(false);
        this.erroJanela.set(erroCargaMsg(err, GUIA_JANELA));
      },
    });
  }

  tipoLabel(): string { return this.folha()?.tipo === 'MENSAL' ? 'mensal' : 'semanal'; }

  /** Período do cabeçalho: mensal = "Junho/2026"; semanal = "dd/mm/aaaa a dd/mm/aaaa". */
  periodoFolhaLabel(): string {
    const f = this.folha();
    return f ? periodoFolha(f.tipo, f.data_inicio, f.data_fim, ' a ') : '';
  }

  editandoCelula(data: string, campo: CampoHora): boolean {
    const e = this.edicao();
    return !!e && e.data === data && e.campo === campo;
  }

  editandoNoDia(data: string): boolean {
    return this.edicao()?.data === data;
  }

  salvandoNoDia(data: string): boolean {
    return this.salvandoEm().has(data);
  }

  // ══════════════════════════════════════════════════════════════
  // O menu da célula
  // ══════════════════════════════════════════════════════════════

  /**
   * Abre o menu ancorado na célula. O gesto é sempre o mesmo — em célula vazia, em célula com
   * horário e na faixa da ocorrência declarada —, e é dele que saem os três caminhos: digitar um
   * horário, declarar uma ocorrência para o dia ou apagar a correção.
   */
  abrirMenu(l: LinhaDia, c: { campo: CampoHora; rotulo: string }, ev: MouseEvent): void {
    ev.stopPropagation();   // senão o mesmo clique chega ao document e fecha o que acabou de abrir
    if (!l.editavel || this.salvandoNoDia(l.data)) return;
    this.edicao.set(null);
    this.menu.set({
      data: l.data,
      campo: c.campo,
      titulo: `${diaCurto(l.data)} · ${c.rotulo}`,
      // a marcação da administração se limpa como uma correção — é uma declaração por outra mão
      podeLimpar: l.corrigido || l.marcadoPeloAdmin,
      ...ancoraDoClique(ev),
    });
  }

  /**
   * O mesmo menu, aberto pela faixa da ocorrência declarada: o alvo é o dia inteiro, e "Editar
   * horário" entra pela primeira batida — digitar um horário é o que desfaz a declaração.
   */
  abrirMenuDoDia(l: LinhaDia, ev: MouseEvent): void {
    this.abrirMenu(l, { campo: 'ent1', rotulo: 'Dia' }, ev);
  }

  fecharMenu(): void {
    this.menu.set(null);
  }

  @HostListener('document:click')
  onCliqueFora(): void {
    if (this.menu()) this.fecharMenu();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.menu()) this.fecharMenu();
    else if (this.edicao()) this.cancelarEdicao();
  }

  /** Primeira opção do menu: a célula vira campo, já com o valor que está à vista. */
  editarHorario(): void {
    const m = this.menu();
    if (!m) return;
    const celula = this.celulaDe(m.data, m.campo);
    this.fecharMenu();
    this.rascunho.set(celula && HORA_RE.test(celula.valor) ? celula.valor : '');
    this.edicao.set({ data: m.data, campo: m.campo });
    setTimeout(() => this.focarCampo());
  }

  /** Declara a ocorrência para o dia inteiro — os horários do dia saem junto. */
  declarar(t: TipoDeclaravel): void {
    const m = this.menu();
    if (!m) return;
    this.fecharMenu();
    this.gravar(m.data, () => this.api.put<any>(`${this.rota()}/tipo`, { data: m.data, tipo_id: t.id }));
  }

  /** Apaga a correção do dia — e a marcação da administração, se era ela que o ocupava. */
  limpar(): void {
    const m = this.menu();
    if (!m) return;
    this.fecharMenu();
    const data = m.data;
    this.emVoo(data, () => this.api.delete<any>(`${this.rota()}/${data}`), () => {
      this.correcoes.update(atuais => {
        const resto = { ...atuais };
        delete resto[data];
        return resto;
      });
      this.retirarMarcacaoLocal(data);
    });
  }

  // ══════════════════════════════════════════════════════════════
  // Digitação da célula
  // ══════════════════════════════════════════════════════════════

  /**
   * Cada tecla passa por aqui. O horário completo grava sozinho — é o quarto dígito que fecha a
   * conta, e esperar por um botão que não existe seria pedir um gesto a mais.
   */
  aoDigitar(l: LinhaDia, c: CelulaDia, valor: string): void {
    this.rascunho.set(valor);
    if (HORA_RE.test(valor)) this.gravarCelula(l, c, valor);
  }

  /**
   * Sair do campo com o horário completo grava; com ele pela metade, descarta em silêncio. O que
   * está incompleto não é uma correção — avisar sobre um "08:0" que ninguém terminou de digitar
   * seria transformar hesitação em erro.
   */
  aoSair(l: LinhaDia, c: CelulaDia): void {
    const valor = this.rascunho();
    if (!this.editandoCelula(l.data, c.campo)) return;   // a gravação pelo 4º dígito já fechou o campo
    this.edicao.set(null);
    if (HORA_RE.test(valor)) this.gravarCelula(l, c, valor);
  }

  cancelarEdicao(): void {
    this.edicao.set(null);
    this.rascunho.set('');
  }

  private gravarCelula(l: LinhaDia, c: CelulaDia, valor: string): void {
    this.edicao.set(null);   // fecha o campo ANTES do envio: o blur seguinte não grava de novo
    this.rascunho.set('');
    // Nada mudou: o campo abre com o horário que está à vista, e abrir para desistir não é
    // correção — gravar aqui criaria uma retificação que repete a folha, palavra por palavra.
    if (valor === c.valor) return;
    this.gravar(l.data, () => this.api.put<any>(`${this.rota()}/celula`,
      { data: l.data, campo: c.campo, valor }));
  }

  /** Gravação que devolve a correção do dia — a resposta substitui o que a tela mostrava. */
  private gravar(data: string, req: () => Observable<any>): void {
    this.emVoo(data, req, (res: any) => {
      const salva: RetifSalva = res?.data;
      if (salva?.data) this.correcoes.update(m => ({ ...m, [salva.data]: salva }));
      this.retirarMarcacaoLocal(data);
    });
  }

  /** O gesto assumiu o dia no servidor: a marcação da administração não existe mais lá. */
  private retirarMarcacaoLocal(data: string): void {
    this.janela.update(dias => {
      const dia = dias[data];
      if (!dia?.marcacao_pessoal) return dias;
      return { ...dias, [data]: { ...dia, marcacao_pessoal: null } };
    });
  }

  /**
   * O envio de um dia, com a fila e o desfecho no mesmo lugar: enquanto ele voa, aquele dia não
   * aceita outro gesto — dois salvamentos do mesmo dia chegariam juntos ao servidor e um deles se
   * perderia. Dias diferentes seguem livres: a espera de um não trava a folha inteira.
   *
   * <p>A requisição chega como receita e não como pedido já feito: o dia barrado pela fila não deve
   * nem montar a chamada. Erro não mexe na tela — a célula continua com o que o servidor tem, e o
   * aviso vai pelo toast.
   */
  private emVoo(data: string, req: () => Observable<any>, aoConcluir: (res: any) => void): void {
    if (this.salvandoNoDia(data)) return;
    const seq = this.seq;
    this.entrarNaFila(data);
    req().subscribe({
      next: (res: any) => {
        if (seq !== this.seq) return;   // a folha foi recarregada: esta resposta é de outra tela
        this.sairDaFila(data);
        aoConcluir(res);
      },
      error: (err: any) => {
        if (seq !== this.seq) return;
        this.sairDaFila(data);
        this.toast.error(httpErrorMsg(err, 'Não foi possível salvar a correção.'));
      },
    });
  }

  private entrarNaFila(data: string): void {
    this.salvandoEm.update(fila => new Set(fila).add(data));
  }

  private sairDaFila(data: string): void {
    this.salvandoEm.update(fila => {
      const resto = new Set(fila);
      resto.delete(data);
      return resto;
    });
  }

  private rota(): string {
    return `/api/ponto/folha/${this.folha()!.id}/retificacoes`;
  }

  /** O campo que está à vista — no celular, a tabela existe no documento, mas escondida. */
  private focarCampo(): void {
    const campos = this.camposHora().map(ref => ref.nativeElement);
    (campos.find(el => el.offsetParent !== null) ?? campos[0])?.focus();
  }

  private celulaDe(data: string, campo: CampoHora): CelulaDia | undefined {
    return this.linhas().find(l => l.data === data)?.celulas.find(c => c.campo === campo);
  }
}

/** Indexa por dia o que o servidor manda em lista. */
function porData<T extends { data: string }>(itens: T[]): Record<string, T> {
  const out: Record<string, T> = {};
  for (const item of itens) out[item.data] = item;
  return out;
}

/** "dd/mm/aa - diasem" → "20aa-mm-dd"; vazio quando o rótulo não traz data legível. */
function dataISO(dia: string): string {
  const m = (dia || '').match(/^(\d{2})\/(\d{2})\/(\d{2})/);
  return m ? `20${m[3]}-${m[2]}-${m[1]}` : '';
}

/** "2026-08-06" → "06/08" — o dia como o cabeçalho do menu o nomeia. */
function diaCurto(data: string): string {
  const m = (data || '').match(/^\d{4}-(\d{2})-(\d{2})$/);
  return m ? `${m[2]}/${m[1]}` : data;
}

/**
 * Sábado ou domingo? A data é montada em partes, no fuso local: `new Date('2026-08-08')` é meia-noite
 * UTC e, aqui, ainda é o dia anterior — o fim de semana sairia deslocado.
 */
function ehFimDeSemana(data: string): boolean {
  const m = (data || '').match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return false;
  const dow = new Date(+m[1], +m[2] - 1, +m[3]).getDay();
  return dow === 0 || dow === 6;
}
