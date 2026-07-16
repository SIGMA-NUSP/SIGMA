import { Component, computed, inject, Input, OnInit, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';
import { MiniCalendarioComponent } from './mini-calendario.component';
import { sameDay, toISODate } from '../../core/helpers/date.helpers';

interface Reuniao {
  tipo?: 'comissao' | 'cessao';
  codigo?: string;
  titulo: string;
  horario: string;
  local: string;
  situacao?: string;
  comissao_sigla?: string;
  comissao_nome?: string;
  tipo_descricao?: string;
  tipo_presenca?: string;
  observacao_horario?: string;
  descricao?: string;
  sala_id?: number;
}

interface SalaAgenda {
  id: number | null;  // null = plenário principal
  nome: string;
  plenario: boolean;
  /** Se true, só renderiza o card quando há ao menos uma reunião agendada. */
  eventual?: boolean;
}

@Component({
  selector: 'app-agenda-legislativa-base',
  standalone: true,
  imports: [RouterLink, MiniCalendarioComponent],
  template: `
    @if (voltarComoBotao) {
      <div class="page-header">
        <h1>Agenda Legislativa</h1>
        <a [routerLink]="voltarRoute" class="btn-voltar">{{ voltarLabel }}</a>
      </div>
    } @else {
      <h1>Agenda Legislativa</h1>
      <a [routerLink]="voltarRoute" class="back-link">{{ voltarLabel }}</a>
    }

    <div class="info-data">
      <div class="data-atual">{{ rotuloDataSelecionada() }}</div>
      @if (ehHoje()) {
        <div class="status-bar" [class.conectado]="sseConectado()" [class.desconectado]="!sseConectado()">
          <span class="status-dot"></span>
          {{ sseConectado() ? 'Conectado — atualizações em tempo real' : 'Reconectando...' }}
          @if (ultimaAtualizacao()) {
            <span class="last-update">Última atualização: {{ ultimaAtualizacao() }}</span>
          }
        </div>
      }
    </div>

    <div class="calendario-wrapper">
      <app-mini-calendario
        [valorSelecionado]="dataSelecionada()"
        [min]="minData()"
        [max]="maxData()"
        (dataSelecionada)="onDataMudou($event)" />
    </div>

    @for (sala of salasVisiveis(); track sala.nome) {
      <section class="agenda-section">
        <h2 class="section-title">
          <span class="section-icon">&#9679;</span>
          {{ sala.nome }}
        </h2>
        @if (exibirOperadores && !sala.plenario && getOperadoresSala(sala.id!)) {
          <div class="reuniao-comissao" style="margin-bottom:10px">Operadores: {{ getOperadoresSala(sala.id!) }}</div>
        }
        @if (getReunioes(sala).length === 0) {
          <div class="card-custom">
            <p class="text-muted-sm">Nenhuma {{ sala.plenario ? 'sessão plenária agendada' : 'reunião agendada' }}.</p>
          </div>
        } @else {
          @for (r of getReunioes(sala); track $index) {
            <div class="card-custom reuniao-card" [class.cessao]="r.tipo === 'cessao'">
              <div class="reuniao-header">
                <span class="reuniao-horario">{{ r.horario }}</span>
                @if (r.situacao) {
                  <span class="reuniao-status" [class]="statusClass(r.situacao)">{{ r.situacao }}</span>
                }
              </div>
              <div class="reuniao-titulo">
                @if (r.comissao_sigla) {
                  <span class="reuniao-sigla">{{ r.comissao_sigla }}</span>
                }
                @if (r.tipo === 'cessao') {
                  <span class="reuniao-sigla cessao">Cessão</span>
                }
                {{ r.titulo }}
              </div>
              @if (r.comissao_nome) {
                <div class="reuniao-comissao">{{ r.comissao_nome }}</div>
              }
              @if (r.descricao) {
                <div class="reuniao-descricao">{{ r.descricao }}</div>
              }
              @if (r.observacao_horario) {
                <div class="reuniao-descricao">{{ r.observacao_horario }}</div>
              }
              <div class="reuniao-meta">
                @if (r.tipo_descricao) { <span>{{ r.tipo_descricao }}</span> }
                @if (r.tipo_presenca) { <span>{{ r.tipo_presenca }}</span> }
                <span>{{ r.local }}</span>
              </div>
            </div>
          }
        }
      </section>
    }
  `,
  styles: [`
    .page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; }
    .btn-voltar {
      background:var(--card); color:var(--text); border:1px solid var(--border);
      border-radius:999px; padding:8px 20px; font-weight:600; text-decoration:none;
      font-size:.9rem; transition:background .15s;
      &:hover { background:var(--row-hover); }
    }

    .info-data { margin-bottom:16px; max-width:520px; }
    .data-atual {
      font-size:1rem; font-weight:600; margin-bottom:8px;
      text-transform:capitalize;
    }
    .calendario-wrapper {
      display:flex; justify-content:center; margin-bottom:24px;
    }

    .status-bar {
      display:flex; align-items:center; gap:8px; padding:8px 16px;
      border-radius:var(--radius);
      font-size:.85rem; font-weight:500;
      &.conectado { background:#f0fdf4; color:#166534; border:1px solid #bbf7d0; }
      &.desconectado { background:#fef2f2; color:#991b1b; border:1px solid #fecaca; }
    }
    .status-dot {
      width:8px; height:8px; border-radius:50%; flex-shrink:0;
      .conectado & { background:#22c55e; }
      .desconectado & { background:#ef4444; }
    }
    .last-update { margin-left:auto; font-size:.8rem; opacity:.7; }

    .agenda-section { margin-bottom:24px; }
    .section-title {
      display:flex; align-items:center; gap:8px;
      font-size:1.1rem; margin-bottom:12px;
    }
    .section-icon { color:var(--primary); font-size:.6rem; }

    .reuniao-card {
      margin-bottom:10px; padding:16px 20px;
      transition:border-color .15s;
      &:hover { border-color:var(--primary); }
      &.cessao .reuniao-horario { color:#92400e; }
    }
    .reuniao-header {
      display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;
    }
    .reuniao-horario {
      font-size:1.3rem; font-weight:700; color:var(--primary);
      font-variant-numeric: tabular-nums;
    }
    .reuniao-status {
      padding:3px 12px; border-radius:999px; font-size:.8rem; font-weight:600;
      &.status-agendada { background:#dbeafe; color:#1e40af; }
      &.status-aberta, &.status-em-andamento { background:#fef3c7; color:#92400e; }
      &.status-realizada, &.status-encerrada { background:#d1fae5; color:#065f46; }
      &.status-cancelada, &.status-adiada { background:#fee2e2; color:#991b1b; }
      &.status-suspensa { background:#fef3c7; color:#92400e; }
    }
    .reuniao-titulo { font-size:1rem; font-weight:600; margin-bottom:4px; }
    .reuniao-sigla {
      background:var(--primary); color:#fff; padding:2px 8px;
      border-radius:4px; font-size:.8rem; margin-right:6px;
      &.cessao { background:#fef3c7; color:#92400e; }
    }
    .reuniao-comissao { font-size:.85rem; color:var(--muted); margin-bottom:4px; }
    .reuniao-descricao { font-size:.85rem; color:var(--muted); font-style:italic; margin-bottom:4px; }
    .reuniao-meta {
      display:flex; flex-wrap:wrap; gap:8px; font-size:.8rem; color:var(--muted);
      span { background:var(--table-header-bg); padding:2px 8px; border-radius:4px; }
    }
  `],
})
export class AgendaLegislativaBaseComponent implements OnInit, OnDestroy {
  @Input() voltarRoute = '/home';
  @Input() voltarLabel = 'Voltar';
  @Input() voltarComoBotao = true;
  @Input() exibirOperadores = false;

  private api = inject(ApiService);
  private auth = inject(AuthService);

  // Plenário Principal + 8 numerados + Auditório Petrônio Portella + Demais Salas
  salas: SalaAgenda[] = [
    { id: null, nome: 'Plenário Principal', plenario: true },
    { id: 3,  nome: 'Plenário 02', plenario: false },
    { id: 4,  nome: 'Plenário 03', plenario: false },
    { id: 5,  nome: 'Plenário 06', plenario: false },
    { id: 6,  nome: 'Plenário 07', plenario: false },
    { id: 7,  nome: 'Plenário 09', plenario: false },
    { id: 8,  nome: 'Plenário 13', plenario: false },
    { id: 9,  nome: 'Plenário 15', plenario: false },
    { id: 10, nome: 'Plenário 19', plenario: false },
    { id: 1,  nome: 'Auditório Petrônio Portella', plenario: false, eventual: true },
    { id: 11, nome: 'Demais Salas', plenario: false, eventual: true },
  ];

  reunioesComissoes = signal<Reuniao[]>([]);
  reunioesPlenario = signal<Reuniao[]>([]);
  operadoresPorSala = signal<Record<string, string[]>>({});
  sseConectado = signal(false);
  ultimaAtualizacao = signal('');

  // Janela de 3 meses: mês anterior, atual e próximo.
  // Min = primeiro dia do mês anterior; Max = último dia do próximo mês.
  dataSelecionada = signal<Date>(new Date());
  minData = signal<Date>(this.calcularMin());
  maxData = signal<Date>(this.calcularMax());

  ehHoje = computed(() => sameDay(this.dataSelecionada(), new Date()));

  rotuloDataSelecionada = computed(() =>
    this.dataSelecionada().toLocaleDateString('pt-BR', {
      weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric',
    })
  );

  private eventSources: EventSource[] = [];

  ngOnInit(): void {
    this.carregarDadosDaData(this.dataSelecionada());
    if (this.exibirOperadores) {
      this.api.get<any>('/api/admin/escala/operadores-hoje').subscribe({
        next: (res: any) => this.operadoresPorSala.set(res.data || {}),
      });
    }
    this.conectarSSE(null, true);
    this.conectarSSE(null, false);
  }

  ngOnDestroy(): void {
    this.fecharSSE();
  }

  onDataMudou(d: Date): void {
    this.dataSelecionada.set(d);
    this.carregarDadosDaData(d);
    if (this.ehHoje()) {
      this.conectarSSE(null, true);
      this.conectarSSE(null, false);
    } else {
      this.fecharSSE();
      this.sseConectado.set(false);
    }
  }

  private carregarDadosDaData(d: Date): void {
    const param = toISODate(d);
    this.api.get<any>(`/api/agenda/plenario?data=${param}`).subscribe({
      next: (res: any) => this.reunioesPlenario.set(res.data || []),
    });
    this.api.get<any>(`/api/agenda/hoje?data=${param}`).subscribe({
      next: (res: any) => this.reunioesComissoes.set(res.data || []),
    });
  }

  getOperadoresSala(salaId: number): string {
    const ops = this.operadoresPorSala()[String(salaId)];
    return ops?.length ? ops.join(', ') : '';
  }

  getReunioes(sala: SalaAgenda): Reuniao[] {
    if (sala.plenario) return this.reunioesPlenario();
    return this.reunioesComissoes().filter(r => r.sala_id === sala.id);
  }

  /** Salas eventuais (Auditório Petrônio Portella, Demais Salas) só aparecem quando têm reuniões. */
  salasVisiveis(): SalaAgenda[] {
    return this.salas.filter(s => !s.eventual || this.getReunioes(s).length > 0);
  }

  conectarSSE(_salaId: number | null, plenarioPrincipal: boolean): void {
    const token = this.auth.getToken();
    let url = `${environment.apiBaseUrl}/api/agenda/stream?`;
    if (plenarioPrincipal) url += `plenario_principal=true&`;
    url += `token=${token}`;

    const es = new EventSource(url);
    this.eventSources.push(es);

    es.addEventListener('agenda', (event: any) => {
      if (!this.ehHoje()) return;  // ignora updates SSE quando visualizando outra data
      const data = JSON.parse(event.data);
      if (data.tipo === 'plenario_principal') {
        this.reunioesPlenario.set(data.reunioes || []);
      } else if (data.tipo === 'todas') {
        this.reunioesComissoes.set(data.reunioes || []);
      }
      this.sseConectado.set(true);
      this.ultimaAtualizacao.set(this.formatarHora(data.atualizado_em));
    });

    es.onopen = () => this.sseConectado.set(true);
    es.onerror = () => this.sseConectado.set(false);
  }

  private fecharSSE(): void {
    for (const es of this.eventSources) es.close();
    this.eventSources = [];
  }

  statusClass(situacao: string): string {
    if (!situacao) return '';
    return `status-${situacao.toLowerCase().replace(/\s+/g, '-')}`;
  }

  private formatarHora(iso: string): string {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch { return iso; }
  }

  private calcularMin(): Date {
    const hoje = new Date();
    return new Date(hoje.getFullYear(), hoje.getMonth() - 1, 1);
  }

  private calcularMax(): Date {
    const hoje = new Date();
    return new Date(hoje.getFullYear(), hoje.getMonth() + 2, 0);  // último dia do próximo mês
  }
}
