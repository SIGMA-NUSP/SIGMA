import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';

type Detalhe = Record<string, any>;

/** Linha normalizada da tabela de ciência/exibição (uniforme entre os tipos). */
interface LinhaCiencia {
  nome: string;
  col2: string;               // Local (Verificação) / Plenário (Escala) / Função (Agenda, Pessoal)
  cienteEm: string | null;    // null = pendente (ainda não deu ciência)
  marcador: string;           // "(fora da escala atual)" / "(não é destinatário)" — vazio quando no público
}

/**
 * Detalhe de um cadastro de aviso (somente leitura). Aberto por duplo-clique numa linha da tabela
 * "Avisos Cadastrados" (query param `id`), consome GET /api/admin/avisos/{id}/detalhe. O card de
 * conteúdo (Identificação / Vigência / Destino / Mensagens) e a tabela de ciência/exibição se
 * adaptam ao TIPO do aviso; o subtipo só muda rótulos. Grupo (GERAL) termina no card, sem tabela.
 */
@Component({
  selector: 'app-admin-aviso-detalhe',
  standalone: true,
  imports: [RouterLink, FmtDatePipe, ErroCargaComponent],
  template: `
    <h1>Detalhe do Aviso</h1>
    <a routerLink="/admin/avisos-sala" class="back-link">&larr; Voltar</a>

    @if (loading()) {
      <p class="text-muted-sm">Carregando...</p>
    } @else if (erro()) {
      <app-erro-carga [mensagem]="erro()!" (tentarNovamente)="carregar()" />
    } @else if (naoEncontrado()) {
      <div class="card-custom detalhe-card">
        <p class="text-muted-sm">Aviso não encontrado.</p>
      </div>
    } @else if (d(); as dd) {
      <div class="card-custom detalhe-card">
        <div class="ro-row"><span class="badge-readonly">APENAS LEITURA</span></div>

        <!-- 1) Identificação -->
        <h3>1) Identificação</h3>
        <div class="field-row grid-2">
          <div class="field">
            <label>Cadastro nº</label>
            <div class="field-value">{{ dd['numero'] }}</div>
          </div>
          <div class="field">
            <label>Tipo de Aviso</label>
            <div class="field-value">{{ dd['tipo_tabela'] }}</div>
          </div>
        </div>
        <div class="field-row grid-2">
          <div class="field">
            <label>Status</label>
            <div class="field-value">
              @if (dd['status'] !== '—') { <span class="status-dot" [attr.data-status]="dd['status']"></span> }
              {{ dd['status'] }}
            </div>
          </div>
          <div class="field">
            <label>Cadastrado por</label>
            <div class="field-value">{{ dd['criado_por'] }}</div>
          </div>
        </div>
        <div class="field">
          <label>Criado em</label>
          <div class="field-value">{{ dd['criado_em'] | fmtDate }} {{ hora(dd['criado_em']) }}</div>
        </div>

        <!-- 2) Vigência (o efeito para o usuário, não o campo interno) -->
        <h3>2) Vigência</h3>
        @switch (tipo()) {
          @case ('ESCALA') {
            <div class="field">
              <label>Vigência</label>
              <div class="field-value">Período da escala — {{ dd['escala']?.['data_inicio'] | fmtDate }} a {{ dd['escala']?.['data_fim'] | fmtDate }}</div>
            </div>
          }
          @case ('AGENDA') {
            <div class="field">
              <label>Exibição</label>
              <div class="field-value">Exibição única por usuário — o aviso deixa de aparecer para cada pessoa depois de exibido 1 vez.</div>
            </div>
          }
          @default {
            <div class="field">
              <label>{{ dd['permanente'] ? 'Permanência' : 'Duração' }}</label>
              <div class="field-value">
                @if (dd['permanente']) {
                  Permanente: Sim
                } @else {
                  Duração: {{ dd['duracao_dias'] }} dias — expira em {{ dd['expira_em'] | fmtDate }} {{ hora(dd['expira_em']) }}
                }
              </div>
            </div>
          }
        }
        @if (temCiencia()) {
          <div class="field">
            <label>Manter após ciência</label>
            <div class="field-value">{{ dd['manter_apos_ciencia'] ? 'Sim' : 'Não' }}</div>
          </div>
        }

        <!-- 3) Destino -->
        <h3>3) Destino</h3>
        @switch (tipo()) {
          @case ('VERIFICACAO') {
            <div class="field">
              <label>Locais</label>
              <div class="chips">
                @for (s of salasAlvo(); track $index) { <span class="chip">{{ s }}</span> }
                @if (!salasAlvo().length) { <span class="text-muted-sm">—</span> }
              </div>
            </div>
          }
          @case ('ESCALA') {
            <div class="field">
              <label>Escala</label>
              <div class="field-value">{{ dd['escala']?.['data_inicio'] | fmtDate }} — {{ dd['escala']?.['data_fim'] | fmtDate }}</div>
            </div>
            <div class="field">
              <label>Plenários</label>
              <div class="chips">
                @for (p of plenarios(); track $index) { <span class="chip">{{ p }}</span> }
              </div>
            </div>
            <p class="nota">Os destinatários são os operadores vinculados a estes plenários <strong>na escala</strong> — trocas de operador mudam quem vê o aviso automaticamente.</p>
          }
          @case ('AGENDA') {
            <div class="field">
              <label>Público</label>
              <div class="field-value">Todos os operadores e técnicos, nas telas de Agenda Legislativa.</div>
            </div>
          }
          @case ('PESSOAL') {
            @if (pessoasPorPapel().operadores.length) {
              <div class="field">
                <label>Operadores</label>
                <div class="chips">@for (n of pessoasPorPapel().operadores; track $index) { <span class="chip">{{ n }}</span> }</div>
              </div>
            }
            @if (pessoasPorPapel().tecnicos.length) {
              <div class="field">
                <label>Técnicos</label>
                <div class="chips">@for (n of pessoasPorPapel().tecnicos; track $index) { <span class="chip">{{ n }}</span> }</div>
              </div>
            }
            @if (pessoasPorPapel().admins.length) {
              <div class="field">
                <label>Administradores</label>
                <div class="chips">@for (n of pessoasPorPapel().admins; track $index) { <span class="chip">{{ n }}</span> }</div>
              </div>
            }
          }
          @case ('GERAL') {
            <div class="field">
              <label>Destinatários</label>
              <div class="field-value">{{ grupoDescricao() }}</div>
            </div>
          }
        }

        <!-- 4) Mensagens -->
        <h3>4) Mensagens</h3>
        @for (msg of mensagens(); track msg['ordem']; let i = $index) {
          <div class="field">
            <label>{{ ordinal(i + 1) }} Aviso</label>
            <div class="field-value obs-value">{{ msg['texto'] }}</div>
          </div>
        }
      </div>

      <!-- Tabela de ciência/exibição — varia por tipo; Grupo (GERAL) não tem (termina no card) -->
      @if (temTabela()) {
        <section class="tabela-ciencia">
          @if (resumo()) { <p class="resumo">{{ resumo() }}</p> }
          <div class="table-container">
            <table class="data-table">
              <thead><tr>
                <th>Destinatário</th>
                <th>{{ colDois() }}</th>
                <th>{{ colData() }}</th>
                <th>{{ colHora() }}</th>
              </tr></thead>
              <tbody>
                @for (l of linhas(); track $index) {
                  <tr [class.fora]="l.marcador">
                    <td>{{ l.nome }}@if (l.marcador) { <span class="marca">{{ l.marcador }}</span> }</td>
                    <td>{{ l.col2 }}</td>
                    <td>{{ l.cienteEm ? (l.cienteEm | fmtDate) : '—' }}</td>
                    <td>{{ l.cienteEm ? hora(l.cienteEm) : '—' }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="empty-state">{{ vazioMsg() }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    }
  `,
  styles: [`
    .ro-row { display: flex; justify-content: flex-end; margin-bottom: 8px; }
    h3 { font-size: .95rem; margin: 24px 0 8px; color: var(--text); }
    .grid-2 { grid-template-columns: 1fr 1fr; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { background: #eef2f7; border: 1px solid var(--border); border-radius: 14px; padding: 3px 10px; font-size: .82rem; }
    .nota { font-size: .82rem; color: var(--muted); margin: 6px 0 0; line-height: 1.4; }
    .tabela-ciencia { max-width: 700px; margin: 20px auto 0; }
    .resumo { font-size: .85rem; color: var(--muted); font-weight: 600; margin: 0 0 8px; }
    .marca { font-size: .75rem; color: var(--muted); font-style: italic; margin-left: 6px; }
    tr.fora td { color: var(--muted); background: #fafafa; }
    /* mesmo CSS da bolinha de status da listagem (admin-avisos-sala) */
    .status-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }
    .status-dot[data-status="Ativo"]      { background: var(--color-green, #16a34a); }
    .status-dot[data-status="Pendente"]   { background: #f59e0b; }
    .status-dot[data-status="Expirado"]   { background: #9ca3af; }
    .status-dot[data-status="Desativado"] { background: #111827; }
    @media (max-width: 640px) {
      .grid-2 { grid-template-columns: 1fr; }
    }
  `],
})
export class AdminAvisoDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);

  private avisoId = '';
  loading = signal(true);
  erro = signal<string | null>(null);
  naoEncontrado = signal(false);
  d = signal<Detalhe | null>(null);

  tipo = computed<string | undefined>(() => this.d()?.['tipo']);
  temCiencia = computed<boolean>(() => {
    const t = this.tipo();
    return t === 'VERIFICACAO' || t === 'ESCALA' || t === 'PESSOAL';
  });
  temTabela = computed<boolean>(() => !!this.tipo() && this.tipo() !== 'GERAL');

  mensagens = computed<Detalhe[]>(() => this.d()?.['mensagens'] ?? []);

  // ── Destino ──
  salasAlvo = computed<string[]>(() =>
    (this.d()?.['alvos'] ?? []).filter((a: any) => a['alvo_tipo'] === 'SALA').map((a: any) => a['descricao']));
  plenarios = computed<string[]>(() =>
    (this.d()?.['escala']?.['plenarios'] ?? []).map((p: any) => p['sala_nome']));
  pessoasPorPapel = computed<{ operadores: string[]; tecnicos: string[]; admins: string[] }>(() => {
    const alvos = this.d()?.['alvos'] ?? [];
    const nomes = (tp: string) => alvos.filter((a: any) => a['alvo_tipo'] === tp).map((a: any) => a['descricao']);
    return { operadores: nomes('OPERADOR'), tecnicos: nomes('TECNICO'), admins: nomes('ADMIN') };
  });
  grupoDescricao = computed<string>(() => (this.d()?.['alvos'] ?? [])[0]?.['descricao'] ?? '');

  // ── Tabela (§4) ──
  colDois = computed<string>(() => {
    switch (this.tipo()) {
      case 'VERIFICACAO': return 'Local';
      case 'ESCALA': return 'Plenário';
      default: return 'Função';   // AGENDA / PESSOAL
    }
  });
  colData = computed<string>(() => this.tipo() === 'AGENDA' ? 'Exibido em (data)' : 'Ciência (data)');
  colHora = computed<string>(() => this.tipo() === 'AGENDA' ? 'Exibido em (hora)' : 'Ciência (hora)');
  vazioMsg = computed<string>(() => {
    switch (this.tipo()) {
      case 'AGENDA': return 'Ainda não exibido para ninguém.';
      case 'VERIFICACAO': return 'Nenhuma ciência registrada.';
      default: return 'Nenhum destinatário.';
    }
  });

  /** Linhas da tabela normalizadas por tipo, já ordenadas (§4.6: pendentes → cientes → fora do público). */
  linhas = computed<LinhaCiencia[]>(() => {
    const dd = this.d();
    if (!dd) return [];
    let raw: LinhaCiencia[];
    switch (this.tipo()) {
      case 'VERIFICACAO':
        raw = (dd['cientes'] ?? []).map((c: any) => ({
          nome: c['nome'], col2: c['sala_nome'] ?? '—', cienteEm: c['ciente_em'], marcador: '',
        }));
        break;
      case 'AGENDA':
        raw = (dd['exibido_para'] ?? []).map((c: any) => ({
          nome: c['nome'], col2: c['papel'], cienteEm: c['ciente_em'], marcador: '',
        }));
        break;
      case 'ESCALA':
        raw = (dd['destinatarios'] ?? []).map((r: any) => ({
          nome: r['nome'], col2: (r['plenarios'] ?? []).join(', ') || '—', cienteEm: r['ciente_em'],
          marcador: r['fora_do_publico'] ? '(fora da escala atual)' : '',
        }));
        break;
      case 'PESSOAL':
        raw = (dd['destinatarios'] ?? []).map((r: any) => ({
          nome: r['nome'], col2: r['papel'], cienteEm: r['ciente_em'],
          marcador: r['fora_do_publico'] ? '(não é destinatário)' : '',
        }));
        break;
      default:
        raw = [];
    }
    return raw.sort((a, b) => this.grupo(a) - this.grupo(b) || this.desempate(a, b));
  });

  /** 0 = pendente (topo), 1 = ciente/exibido, 2 = fora do público (fim). */
  private grupo(l: LinhaCiencia): number {
    if (l.marcador) return 2;
    return l.cienteEm ? 1 : 0;
  }

  private desempate(a: LinhaCiencia, b: LinhaCiencia): number {
    if (this.grupo(a) === 0) return a.nome.localeCompare(b.nome, 'pt-BR');   // pendentes: alfabético
    return String(a.cienteEm ?? '').localeCompare(String(b.cienteEm ?? ''));  // demais: por data crescente
  }

  resumo = computed<string>(() => {
    const dd = this.d();
    if (!dd) return '';
    if (this.tipo() === 'AGENDA') {
      const n = (dd['exibido_para'] ?? []).length;
      return `Exibido para ${n} ${n === 1 ? 'pessoa' : 'pessoas'}`;
    }
    if (this.tipo() === 'ESCALA' || this.tipo() === 'PESSOAL') {
      const publico = (dd['destinatarios'] ?? []).filter((r: any) => !r['fora_do_publico']);
      const cientes = publico.filter((r: any) => r['ciente_em']).length;
      return `${cientes} de ${publico.length} deram ciência`;
    }
    return '';   // Verificação: público aberto, sem denominador → sem resumo
  });

  /** HH:MM da parte de hora de um datetime ISO ('2026-07-20T15:30:45' → '15:30'). O fmtTime do projeto
   *  só formata horários 'HH:MM:SS' (VARCHAR2), não um datetime — por isso a extração é local. */
  hora(iso: unknown): string {
    const t = String(iso ?? '').split('T')[1];
    return t ? t.substring(0, 5) : '—';
  }

  ordinal(n: number): string { return `${n}º`; }

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('id');
    if (!id) { this.loading.set(false); this.naoEncontrado.set(true); return; }
    this.avisoId = id;
    this.carregar();
  }

  carregar(): void {
    this.loading.set(true);
    this.erro.set(null);
    this.naoEncontrado.set(false);
    this.api.get<any>(`/api/admin/avisos/${this.avisoId}/detalhe`).subscribe({
      next: (res: any) => { this.d.set(res?.data ?? res); this.loading.set(false); },
      error: (err: any) => {
        this.loading.set(false);
        // Erro ≠ não encontrado (idioma C7/C13b): 404 tem mensagem própria; falha de rede/500 vira caixa com retry.
        if (err?.status === 404) this.naoEncontrado.set(true);
        else this.erro.set('Não foi possível carregar o aviso. Tente novamente.');
      },
    });
  }
}
