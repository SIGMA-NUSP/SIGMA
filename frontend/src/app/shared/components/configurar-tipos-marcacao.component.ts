import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ErroCargaComponent } from './erro-carga.component';
import { ToastService } from './toast.component';

/** Um tipo de ocorrência do catálogo. */
export interface TipoMarcacao {
  id: string;
  nome: string;
  badge: string;
  escopo: EscopoTipo;
  /** Tipo que o funcionário declara na própria folha: o administrador não marca nem exclui. */
  visivel_funcionario: boolean;
}
export type EscopoTipo = 'GLOBAL' | 'INDIVIDUAL';

/** Tipo ainda não salvo, aguardando o Aplicar. */
interface TipoPendente { nome: string; badge: string; }

/** O que a exclusão de um tipo vai apagar, contado no banco. */
interface PreviewExclusaoTipo {
  tipo: TipoMarcacao;
  marcacoes: number;
  /** Dias que funcionários declararam com esse tipo na retificação da própria folha. */
  retificacoes: number;
  pessoas_afetadas: number;
  pessoas: string[];
}

/**
 * Modal "Configurar" do card Retificações — cadastro e exclusão dos tipos de
 * ocorrência, só para o admin master (o backend recusa qualquer outro com 403).
 *
 * <p>Os dois modos são excludentes e o rascunho é descartado a cada troca de
 * radio: incluir vários tipos de uma vez, ou marcar UM para excluir. A exclusão
 * nunca acontece direto — o preview conta as marcações que morrem junto e o
 * segundo modal exige a confirmação.
 */
@Component({
  selector: 'app-configurar-tipos-marcacao',
  standalone: true,
  imports: [ErroCargaComponent],
  template: `
    <div class="modal-overlay" (click)="tentarFechar()">
      <div class="modal-card card-custom" (click)="$event.stopPropagation()">
        <!-- Sem mês no título: o catálogo é único e vale para todos os meses. -->
        <h2 class="modal-title">Configurar ocorrências</h2>

        @if (carregando()) {
          <p class="text-muted-sm">Carregando tipos...</p>
        } @else if (erroCarga()) {
          <app-erro-carga [mensagem]="erroCarga()" (tentarNovamente)="carregar()" />
        } @else {
          <div class="campo">
            <span class="rot" id="rot-escopo">Tipo</span>
            <div class="radios" role="radiogroup" aria-labelledby="rot-escopo">
              <label class="opcao"><input type="radio" name="escopo" value="GLOBAL"
                            [checked]="escopo() === 'GLOBAL'" (change)="trocarEscopo('GLOBAL')"><span>Geral</span></label>
              <label class="opcao"><input type="radio" name="escopo" value="INDIVIDUAL"
                            [checked]="escopo() === 'INDIVIDUAL'" (change)="trocarEscopo('INDIVIDUAL')"><span>Individual</span></label>
            </div>
          </div>

          <div class="campo">
            <div class="radios" role="radiogroup" aria-label="Operação">
              <label class="opcao"><input type="radio" name="operacao" value="incluir"
                            [checked]="operacao() === 'incluir'" (change)="trocarOperacao('incluir')"><span>Incluir novo tipo</span></label>
              <label class="opcao"><input type="radio" name="operacao" value="excluir"
                            [checked]="operacao() === 'excluir'" (change)="trocarOperacao('excluir')"><span>Excluir tipo existente</span></label>
            </div>
          </div>

          @if (operacao() === 'incluir') {
            <div class="novo">
              <label class="rot">Novo:</label>
              <input type="text" [value]="nomeNovo()" (input)="nomeNovo.set($any($event.target).value)"
                     maxlength="20" placeholder="Nome (até 20)" aria-label="Nome do tipo">
              <input type="text" class="in-badge" [value]="badgeNovo()" (input)="badgeNovo.set($any($event.target).value)"
                     maxlength="3" placeholder="Badge" aria-label="Badge do tipo">
              <button type="button" class="btn-add" (click)="adicionarPendente()"
                      [disabled]="salvando() || !nomeNovo().trim() || !badgeNovo().trim()"
                      title="Adicionar à lista">+</button>
            </div>
          }

          <div class="lista">
            @if (tiposDoEscopo().length === 0 && pendentes().length === 0) {
              <p class="empty-state">Nenhum tipo cadastrado</p>
            }
            @for (t of tiposDoEscopo(); track t.id) {
              <button type="button" class="chip" [class.excluir]="marcadoId() === t.id"
                      [class.inerte]="operacao() !== 'excluir'"
                      (click)="marcarParaExcluir(t)">{{ t.nome }} <span class="badge">{{ t.badge }}</span></button>
            }
            @for (p of pendentes(); track $index) {
              <span class="chip pendente">{{ p.nome }} <span class="badge">{{ p.badge }}</span>
                <button type="button" class="btn-remover" (click)="removerPendente($index)"
                        [attr.aria-label]="'Retirar ' + p.nome">✕</button>
              </span>
            }
          </div>

          @if (erro()) { <div class="error-box">{{ erro() }}</div> }

          <div class="modal-actions" style="gap:8px">
            <button type="button" class="btn-outline" [disabled]="salvando()" (click)="tentarFechar()">Cancelar</button>
            <button type="button" class="btn-primary-custom" [disabled]="salvando() || !podeAplicar()"
                    (click)="aplicar()">{{ salvando() ? 'Aplicando...' : 'Aplicar' }}</button>
          </div>
        }
      </div>
    </div>

    <!-- Confirmação da exclusão: o que morre junto com o tipo, contado no banco -->
    @if (preview(); as pv) {
      <div class="modal-overlay" (click)="fecharConfirmacao()">
        <div class="modal-card card-custom" (click)="$event.stopPropagation()">
          <h2 class="modal-title">Excluir este tipo?</h2>

          <p class="alvo">
            <strong>{{ pv.tipo.nome }}</strong>&ngsp;<span class="text-muted-sm">({{ pv.tipo.escopo === 'GLOBAL' ? 'geral' : 'individual' }} — badge {{ pv.tipo.badge }})</span>
          </p>

          <p class="consequencias-titulo">O que será apagado:</p>
          <ul class="consequencias">
            <li>o tipo, que deixa de aparecer nas ocorrências</li>
            <li>{{ pv.marcacoes }} marcação(ões) feita(s) com ele</li>
            @if (pv.retificacoes) {
              <li>{{ pv.retificacoes }} dia(s) retificado(s) por funcionários com ele</li>
            }
            @if (pv.tipo.escopo === 'INDIVIDUAL') {
              <li>
                {{ pv.pessoas_afetadas }} funcionário(s) afetado(s)
                @if (pv.pessoas.length) {
                  <span class="text-muted-sm">({{ pv.pessoas.join(', ') }})</span>
                }
              </li>
            }
          </ul>

          <p class="irreversivel"><strong>Esta ação é irreversível.</strong></p>

          @if (erroExclusao()) { <div class="error-box">{{ erroExclusao() }}</div> }

          <div class="modal-actions" style="gap:8px">
            <button type="button" class="btn-outline" [disabled]="excluindo()" (click)="fecharConfirmacao()">Cancelar</button>
            <button type="button" class="btn-danger" [disabled]="excluindo()" (click)="confirmarExclusao()">
              {{ excluindo() ? 'Excluindo...' : 'Excluir definitivamente' }}
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .campo { margin-bottom: 14px; }
    .rot { display: block; font-weight: 600; margin-bottom: 6px; }

    .radios { display: flex; flex-wrap: wrap; gap: 8px 22px; }
    .opcao {
      display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
      min-height: 32px;
      white-space: nowrap;   /* o rótulo inteiro numa linha só, nunca palavra por palavra */
    }
    /* O círculo tem tamanho próprio: as linhas de formulário globais esticam todo input a 100%,
       e um radio esticado desenha a bolinha no meio do rótulo. */
    .opcao input[type="radio"] {
      flex: none; width: 18px; height: 18px; margin: 0; accent-color: var(--primary);
    }

    .novo { display: flex; align-items: center; gap: 8px; margin: 10px 0; flex-wrap: wrap; }
    .novo .rot { margin-bottom: 0; }
    .novo input { flex: 1; min-width: 120px; }
    .novo .in-badge { flex: 0 0 90px; min-width: 70px; }
    .btn-add {
      width: 44px; height: 44px; border-radius: 8px; border: none; cursor: pointer;
      background: var(--color-green); color: #fff; font-size: 1.4rem; line-height: 1;
    }
    .btn-add:disabled { opacity: .5; cursor: default; }

    .lista { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
    .chip {
      display: inline-flex; align-items: center; gap: 8px;
      border: 1px solid var(--border); background: #fff; border-radius: 999px;
      padding: 6px 8px 6px 14px; font-size: .9rem; color: var(--text); cursor: pointer;
      min-height: 36px;
    }
    /* Fora do modo Excluir os tipos existentes são apenas exibidos. */
    .chip.inerte { cursor: default; }
    .chip.excluir { background: var(--color-red); color: #fff; border-color: var(--color-red); }
    .chip.pendente { border-style: dashed; border-color: var(--color-green); color: var(--color-green); }
    /* A badge é o rótulo curto que aparece sob o número do dia no calendário — aqui ela vem
       destacada para o master conferir o que vai ver depois na tela de ocorrências. */
    .badge {
      font-weight: 700; font-size: .78rem; line-height: 1;
      padding: 4px 8px; border-radius: 999px;
      background: var(--accordion-bg); color: var(--primary);
    }
    .chip.excluir .badge { background: rgba(255, 255, 255, .25); color: #fff; }
    .chip.pendente .badge { background: #ecfdf5; color: var(--color-green); }
    .btn-remover {
      background: none; border: none; cursor: pointer; padding: 0;
      color: inherit; font-size: 1rem; line-height: 1;
      width: 28px; height: 28px; border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center;
    }
    .btn-remover:hover { background: rgba(0, 0, 0, .06); }

    .btn-danger {
      background: var(--color-red); color: #fff; border: none; border-radius: 6px;
      padding: 8px 16px; font-weight: 600; cursor: pointer;
    }
    .btn-danger:disabled { opacity: .6; cursor: default; }
    .alvo { margin: 0 0 12px; }
    .consequencias-titulo { margin: 10px 0 4px; font-weight: 600; }
    .consequencias { margin: 0; padding-left: 20px; }
    .consequencias li { margin-bottom: 4px; }
    .irreversivel { margin: 12px 0 0; color: var(--color-red); }

    @media (max-width: 640px) {
      /* Uma opção por linha: no estreito, duas opções lado a lado espremem os rótulos. */
      .radios { flex-direction: column; gap: 10px; }
      .novo { gap: 6px; }
      .novo input, .novo .in-badge { flex: 1 1 100%; }
      .btn-add { width: 100%; }
    }
  `],
})
export class ConfigurarTiposMarcacaoComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  /** Fecha o modal. */
  fechar = output<void>();
  /** O catálogo mudou: quem abriu precisa recarregar chips e grade. */
  alterado = output<void>();

  tipos = signal<TipoMarcacao[]>([]);
  carregando = signal(false);
  erroCarga = signal('');

  escopo = signal<EscopoTipo>('GLOBAL');
  operacao = signal<'' | 'incluir' | 'excluir'>('');
  nomeNovo = signal('');
  badgeNovo = signal('');
  pendentes = signal<TipoPendente[]>([]);
  marcadoId = signal('');
  erro = signal('');
  salvando = signal(false);

  preview = signal<PreviewExclusaoTipo | null>(null);
  erroExclusao = signal('');
  excluindo = signal(false);

  // O tipo que o funcionário declara não é gerido por aqui: ele não se cadastra nem se exclui pela
  // tela, e listá-lo só ofereceria uma exclusão que o servidor recusa.
  tiposDoEscopo = computed(() =>
    this.tipos().filter(t => t.escopo === this.escopo() && !t.visivel_funcionario));
  podeAplicar = computed(() =>
    this.operacao() === 'incluir' ? this.pendentes().length > 0
      : this.operacao() === 'excluir' ? !!this.marcadoId()
        : false);

  ngOnInit(): void {
    this.carregar();
  }

  carregar(): void {
    this.carregando.set(true);
    this.erroCarga.set('');
    this.api.get<any>('/api/admin/ponto/tipos-marcacao').subscribe({
      next: res => {
        this.tipos.set(res.data?.tipos ?? []);
        this.carregando.set(false);
      },
      error: err => {
        this.carregando.set(false);
        this.erroCarga.set(erroCargaMsg(err, 'Erro ao carregar os tipos de ocorrência.'));
      },
    });
  }

  /** Trocar de radio zera o rascunho: o que estava pendente não vale para o outro escopo/modo. */
  trocarEscopo(novo: EscopoTipo): void {
    if (novo === this.escopo()) return;
    this.escopo.set(novo);
    this.zerarRascunho();
  }

  trocarOperacao(nova: 'incluir' | 'excluir'): void {
    if (nova === this.operacao()) return;
    this.operacao.set(nova);
    this.zerarRascunho();
  }

  private zerarRascunho(): void {
    this.pendentes.set([]);
    this.marcadoId.set('');
    this.nomeNovo.set('');
    this.badgeNovo.set('');
    this.erro.set('');
  }

  /**
   * Guarda o tipo digitado na lista de pendentes. A recusa por nome/badge repetido
   * também acontece no servidor — aqui ela é imediata e não gasta requisição.
   */
  adicionarPendente(): void {
    const nome = this.nomeNovo().trim().replace(/\s+/g, ' ');
    const badge = this.badgeNovo().trim();
    if (!nome || !badge) return;
    if (nome.length > 20) { this.erro.set('O nome do tipo deve ter no máximo 20 caracteres.'); return; }
    if (badge.length > 3) { this.erro.set('A badge deve ter no máximo 3 caracteres.'); return; }

    const nomeNorm = normalizar(nome);
    const badgeNorm = normalizar(badge);
    // Nome e badge são únicos em TODO o catálogo: o conflito pode ser com um tipo do outro escopo,
    // que não está na lista à vista — por isso a recusa diz onde o tipo conflitante vive.
    const jaExiste: { nome: string; badge: string; escopo?: EscopoTipo }[] = [
      ...this.tipos(),
      ...this.pendentes(),
    ];
    const porNome = jaExiste.find(t => normalizar(t.nome) === nomeNorm);
    if (porNome) {
      this.erro.set(`Já existe um tipo com o nome "${porNome.nome}"${ondeVive(porNome, this.escopo())}.`);
      return;
    }
    const porBadge = jaExiste.find(t => normalizar(t.badge) === badgeNorm);
    if (porBadge) {
      this.erro.set(`Já existe um tipo com a badge "${porBadge.badge}"${ondeVive(porBadge, this.escopo())}.`);
      return;
    }

    this.pendentes.update(lista => [...lista, { nome, badge }]);
    this.nomeNovo.set('');
    this.badgeNovo.set('');
    this.erro.set('');
  }

  removerPendente(indice: number): void {
    this.pendentes.update(lista => lista.filter((_, i) => i !== indice));
  }

  /** Só um tipo por vez: clicar em outro troca a marcação; clicar no mesmo a desfaz. */
  marcarParaExcluir(tipo: TipoMarcacao): void {
    if (this.operacao() !== 'excluir') return;
    this.marcadoId.set(this.marcadoId() === tipo.id ? '' : tipo.id);
  }

  aplicar(): void {
    if (this.salvando() || !this.podeAplicar()) return;
    if (this.operacao() === 'incluir') this.salvarPendentes();
    else this.abrirConfirmacao();
  }

  private salvarPendentes(): void {
    const escopo = this.escopo();
    // O lote é congelado aqui: é ele que vai ao servidor e é dele que o aviso de sucesso fala,
    // ainda que a lista da tela mude enquanto a requisição está em voo.
    const lote = this.pendentes();
    const body = { tipos: lote.map(p => ({ nome: p.nome, badge: p.badge, escopo })) };
    this.salvando.set(true);
    this.erro.set('');
    this.api.post<any>('/api/admin/ponto/tipos-marcacao', body).subscribe({
      next: () => {
        this.salvando.set(false);
        this.toast.success(lote.length === 1 ? 'Tipo cadastrado.' : 'Tipos cadastrados.');
        this.alterado.emit();
        this.fechar.emit();
      },
      // O cadastro é tudo ou nada: nada foi salvo, e o rascunho continua na tela para correção.
      error: err => {
        this.salvando.set(false);
        this.erro.set(httpErrorMsg(err, 'Erro ao cadastrar os tipos.'));
      },
    });
  }

  /** O preview vem antes do modal: falhou o preview, nada é perguntado nem excluído. */
  private abrirConfirmacao(): void {
    const id = this.marcadoId();
    this.salvando.set(true);
    this.erro.set('');
    this.api.get<any>(`/api/admin/ponto/tipos-marcacao/${id}/exclusao/preview`).subscribe({
      next: res => {
        this.salvando.set(false);
        this.erroExclusao.set('');
        this.preview.set(res.data ?? null);
      },
      error: err => {
        this.salvando.set(false);
        this.erro.set(erroCargaMsg(err, 'Não foi possível concluir a operação.'));
      },
    });
  }

  confirmarExclusao(): void {
    const pv = this.preview();
    if (!pv || this.excluindo()) return;
    this.excluindo.set(true);
    this.erroExclusao.set('');
    this.api.delete<any>(`/api/admin/ponto/tipos-marcacao/${pv.tipo.id}`).subscribe({
      next: () => {
        this.excluindo.set(false);
        this.preview.set(null);
        this.toast.success('Tipo excluído.');
        this.alterado.emit();
        this.fechar.emit();
      },
      // A recusa (403/404) fica no modal: quem não é master não descobre isso por um toast que some.
      error: err => {
        this.excluindo.set(false);
        this.erroExclusao.set(httpErrorMsg(err, 'Não foi possível excluir o tipo.'));
      },
    });
  }

  fecharConfirmacao(): void {
    if (this.excluindo()) return;
    this.preview.set(null);
    this.erroExclusao.set('');
  }

  tentarFechar(): void {
    if (this.salvando() || this.preview()) return;
    if (this.temRascunho() && !confirm('Fechar e descartar as alterações realizadas?')) return;
    this.fechar.emit();
  }

  private temRascunho(): boolean {
    return this.pendentes().length > 0 || !!this.marcadoId();
  }
}

/** " (em Individual)" quando o tipo conflitante é do outro escopo — e nada quando está à vista. */
function ondeVive(tipo: { escopo?: EscopoTipo }, escopoExibido: EscopoTipo): string {
  if (!tipo.escopo || tipo.escopo === escopoExibido) return '';
  return tipo.escopo === 'GLOBAL' ? ' (em Geral)' : ' (em Individual)';
}

/** A mesma forma que o servidor usa para decidir se dois nomes (ou badges) são o mesmo. */
function normalizar(texto: string): string {
  return texto.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .trim().replace(/\s+/g, ' ').toUpperCase();
}
