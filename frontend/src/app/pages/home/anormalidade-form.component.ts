import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';
import { SALA_DEMAIS_SALAS_ID, focusFirst } from '../../core/helpers/form.helpers';
import { httpErrorMsg } from '../../core/helpers/http.helpers';

@Component({
  selector: 'app-anormalidade-form',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card-custom" style="max-width:850px; margin:0 auto">
      <h1>Registro de Anormalidade</h1>
      <p class="hint-text">Preencha as informações referentes à ocorrência. Campos marcados com <span class="req">*</span> são obrigatórios.</p>
      @if (registroId) {
        <p class="hint-text" style="margin-bottom:20px">Vinculado ao registro de operação nº {{ registroId }}</p>
      }

      @if (loading()) {
        <p style="color:var(--muted)">Carregando...</p>
      } @else {
        <form (ngSubmit)="onSubmit()">

          <!-- 1) Dados do Evento -->
          <h3 class="section-title">1) Dados do Evento</h3>
          <div class="form-grid-3">
            <div class="form-row">
              <label>Data</label>
              <input type="date" [(ngModel)]="data" name="data" readonly class="field-ro">
            </div>
            <div class="form-row">
              <label>Local do evento</label>
              <select [(ngModel)]="salaId" name="sala_id" disabled class="field-ro" style="width:100%">
                <option value="">—</option>
                @for (s of lookup.salas(); track s.id) {
                  <option [value]="s.id">{{ s.nome }}</option>
                }
              </select>
            </div>
            <div class="form-row">
              <label>Nome do evento</label>
              <input [(ngModel)]="nomeEvento" name="nome_evento" readonly class="field-ro">
            </div>
          </div>

          @if (isDemaisSalas()) {
            <div class="form-row">
              <label>Nome da Sala</label>
              <input [(ngModel)]="nomeDemaisSalas" name="nome_demais_salas" readonly class="field-ro">
            </div>
          }

          <!-- 2) Responsáveis -->
          <h3 class="section-title">2) Responsáveis</h3>
          <div class="form-row">
            <label>Nome do Secretário da Comissão, da Mesa ou do responsável pelo evento <span class="req">*</span></label>
            <input [(ngModel)]="responsavelEvento" name="responsavel_evento" placeholder="Ex: Secretário da Mesa, Assessor da Comissão...">
          </div>

          <!-- 3) Detalhes da Anormalidade -->
          <h3 class="section-title">3) Detalhes da Anormalidade</h3>
          <div class="form-row">
            <label>Horário do início da anormalidade <span class="req">*</span></label>
            <input type="time" [(ngModel)]="horaInicio" name="hora_inicio_anormalidade" step="60" style="max-width:180px">
          </div>
          <div class="form-row">
            <label>Descrição da anormalidade <span class="req">*</span></label>
            <textarea [(ngModel)]="descricao" name="descricao_anormalidade" rows="4" placeholder="Descreva resumidamente o que aconteceu..."></textarea>
          </div>

          <!-- Prejuízo -->
          <div class="form-grid-2-toggle">
            <div class="form-row">
              <label>Houve suspensão, adiamento ou cancelamento do evento?</label>
              <div class="radio-row">
                <label><input type="radio" [(ngModel)]="houvePrejuizo" name="houve_prejuizo" value="false" (ngModelChange)="onToggle('prejuizo')"> Não</label>
                <label><input type="radio" [(ngModel)]="houvePrejuizo" name="houve_prejuizo" value="true" (ngModelChange)="onToggle('prejuizo')"> Sim</label>
              </div>
            </div>
            @if (houvePrejuizo === 'true') {
              <div class="form-row">
                <label>Duração do adiamento ou cancelamento do evento <span class="req">*</span></label>
                <textarea [(ngModel)]="descricaoPrejuizo" name="descricao_prejuizo" rows="3"></textarea>
              </div>
            }
          </div>

          <!-- Reclamação -->
          <div class="form-grid-2-toggle">
            <div class="form-row">
              <label>Houve reclamação?</label>
              <div class="radio-row">
                <label><input type="radio" [(ngModel)]="houveReclamacao" name="houve_reclamacao" value="false" (ngModelChange)="onToggle('reclamacao')"> Não</label>
                <label><input type="radio" [(ngModel)]="houveReclamacao" name="houve_reclamacao" value="true" (ngModelChange)="onToggle('reclamacao')"> Sim</label>
              </div>
            </div>
            @if (houveReclamacao === 'true') {
              <div class="form-row">
                <label>Autor(es) e conteúdo resumido da reclamação <span class="req">*</span></label>
                <textarea [(ngModel)]="autoresReclamacao" name="autores_conteudo_reclamacao" rows="3"></textarea>
              </div>
            }
          </div>

          <!-- Manutenção -->
          <div class="form-grid-2-toggle">
            <div class="form-row">
              <label>Foi necessário acionar a manutenção?</label>
              <div class="radio-row">
                <label><input type="radio" [(ngModel)]="acionouManutencao" name="acionou_manutencao" value="false" (ngModelChange)="onToggle('manutencao')"> Não</label>
                <label><input type="radio" [(ngModel)]="acionouManutencao" name="acionou_manutencao" value="true" (ngModelChange)="onToggle('manutencao')"> Sim</label>
              </div>
            </div>
            @if (acionouManutencao === 'true') {
              <div class="form-row">
                <label>Horário do acionamento da manutenção <span class="req">*</span></label>
                <input type="time" [(ngModel)]="horaManutencao" name="hora_acionamento_manutencao" step="60" style="max-width:180px">
              </div>
            }
          </div>

          <!-- Resolvida -->
          <div class="form-grid-2-toggle">
            <div class="form-row">
              <label>A anormalidade foi resolvida pela própria operação?</label>
              <div class="radio-row">
                <label><input type="radio" [(ngModel)]="resolvidaOperador" name="resolvida_pelo_operador" value="false" (ngModelChange)="onToggle('resolvida')"> Não</label>
                <label><input type="radio" [(ngModel)]="resolvidaOperador" name="resolvida_pelo_operador" value="true" (ngModelChange)="onToggle('resolvida')"> Sim</label>
              </div>
            </div>
            @if (resolvidaOperador === 'true') {
              <div class="form-row">
                <label>Descrição do procedimento adotado <span class="req">*</span></label>
                <textarea [(ngModel)]="procedimentos" name="procedimentos_adotados" rows="3" placeholder="O que foi feito?"></textarea>
              </div>
            }
          </div>

          <!-- Ações -->
          <div class="form-actions">
            <a routerLink="/home" class="btn-secondary-custom">Voltar</a>
            <button type="submit" class="btn-primary-custom" [disabled]="saving()">
              {{ saving() ? 'Salvando...' : 'Salvar registro' }}
            </button>
          </div>
        </form>
      }
    </div>
  `,
  styles: [`
    .req { color: var(--color-red); }
    .radio-row { display: flex; gap: 24px; align-items: center; }
    .field-ro { background: #f9fafb !important; color: #6b7280; cursor: not-allowed; }
    .form-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; margin-bottom: 8px; }
    .form-grid-2-toggle { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: start; margin-bottom: 4px; }
    @media (max-width: 600px) {
      .form-grid-3, .form-grid-2-toggle { grid-template-columns: 1fr; }
      .form-actions { flex-direction: column; }
    }
    .section-title { font-weight: 600; font-size: .95rem; color: #374151; margin: 20px 0 8px; }
    .form-actions {
      display: flex; justify-content: flex-end; gap: 12px;
      margin-top: 24px; padding-top: 16px; border-top: 1px solid #f3f4f6;
    }
  `],
})
export class AnormalidadeFormComponent implements OnInit {
  private api = inject(ApiService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private toast = inject(ToastService);
  lookup = inject(LookupService);

  loading = signal(true);
  saving = signal(false);

  registroId = '';
  entradaId = '';
  salaId = '';
  data = '';
  nomeEvento = '';
  nomeDemaisSalas = '';
  responsavelEvento = '';
  horaInicio = '';
  descricao = '';
  houvePrejuizo = 'false';
  descricaoPrejuizo = '';
  houveReclamacao = 'false';
  autoresReclamacao = '';
  acionouManutencao = 'false';
  horaManutencao = '';
  resolvidaOperador = 'false';
  procedimentos = '';
  anomId = '';

  isDemaisSalas(): boolean {
    return Number(this.salaId) === SALA_DEMAIS_SALAS_ID;
  }

  ngOnInit(): void {
    this.lookup.loadAll();

    this.route.queryParams.subscribe(params => {
      this.registroId = params['registro_id'] || '';
      this.entradaId = params['entrada_id'] || '';
      const modo = params['modo'] || 'novo';

      if (this.registroId) {
        this.loadLookupData(modo);
      } else {
        this.loading.set(false);
      }
    });
  }

  private loadLookupData(modo: string): void {
    const lookupParams: any = { id: this.registroId };
    if (this.entradaId) lookupParams.entrada_id = this.entradaId;

    this.api.get<any>('/api/forms/lookup/registro-operacao', lookupParams).subscribe({
      next: (res: any) => {
        if (res.ok && res.data) {
          const d = res.data;
          this.data = d.data ? String(d.data).substring(0, 10) : '';
          this.salaId = d.sala_id ? String(d.sala_id) : '';
          this.nomeEvento = d.nome_evento || '';
          this.nomeDemaisSalas = d.nome_demais_salas || '';
          this.responsavelEvento = d.responsavel_evento || '';
        }

        // Se modo edição ou se já existe RAOA vinculada, carrega
        if (modo === 'edicao' || (modo === 'novo' && this.entradaId)) {
          this.loadExisting();
        } else {
          this.loading.set(false);
        }
      },
      error: () => { this.loading.set(false); },
    });
  }

  private loadExisting(): void {
    this.api.get<any>('/api/operacao/anormalidade/registro', { entrada_id: this.entradaId }).subscribe({
      next: (res: any) => {
        if (res.ok && res.data) {
          const d = res.data;
          this.anomId = d.id || '';
          this.responsavelEvento = d.responsavel_evento || this.responsavelEvento;
          this.horaInicio = d.hora_inicio_anormalidade || '';
          this.descricao = d.descricao_anormalidade || '';
          this.houvePrejuizo = d.houve_prejuizo ? 'true' : 'false';
          this.descricaoPrejuizo = d.descricao_prejuizo || '';
          this.houveReclamacao = d.houve_reclamacao ? 'true' : 'false';
          this.autoresReclamacao = d.autores_conteudo_reclamacao || '';
          this.acionouManutencao = d.acionou_manutencao ? 'true' : 'false';
          this.horaManutencao = d.hora_acionamento_manutencao || '';
          this.resolvidaOperador = d.resolvida_pelo_operador ? 'true' : 'false';
          this.procedimentos = d.procedimentos_adotados || '';
        }
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  onToggle(field: string): void {
    // Limpar campo condicional quando toggle volta para "Não"
    switch (field) {
      case 'prejuizo': if (this.houvePrejuizo === 'false') this.descricaoPrejuizo = ''; break;
      case 'reclamacao': if (this.houveReclamacao === 'false') this.autoresReclamacao = ''; break;
      case 'manutencao': if (this.acionouManutencao === 'false') this.horaManutencao = ''; break;
      case 'resolvida': if (this.resolvidaOperador === 'false') this.procedimentos = ''; break;
    }
  }

  onSubmit(): void {
    if (!this.responsavelEvento) { focusFirst('responsavel_evento'); return; }
    if (!this.horaInicio) { focusFirst('hora_inicio_anormalidade'); return; }
    if (!this.descricao) { focusFirst('descricao_anormalidade'); return; }
    if (this.houvePrejuizo === 'true' && !this.descricaoPrejuizo) { focusFirst('descricao_prejuizo'); return; }
    if (this.houveReclamacao === 'true' && !this.autoresReclamacao) { focusFirst('autores_conteudo_reclamacao'); return; }
    if (this.acionouManutencao === 'true' && !this.horaManutencao) { focusFirst('hora_acionamento_manutencao'); return; }
    if (this.resolvidaOperador === 'true' && !this.procedimentos) { focusFirst('procedimentos_adotados'); return; }

    this.saving.set(true);

    const payload: any = {
      registro_id: this.registroId,
      entrada_id: this.entradaId || null,
      data: this.data,
      sala_id: this.salaId,
      nome_evento: this.nomeEvento,
      responsavel_evento: this.responsavelEvento,
      hora_inicio_anormalidade: this.horaInicio,
      descricao_anormalidade: this.descricao,
      houve_prejuizo: this.houvePrejuizo,
      descricao_prejuizo: this.houvePrejuizo === 'true' ? this.descricaoPrejuizo : '',
      houve_reclamacao: this.houveReclamacao,
      autores_conteudo_reclamacao: this.houveReclamacao === 'true' ? this.autoresReclamacao : '',
      acionou_manutencao: this.acionouManutencao,
      hora_acionamento_manutencao: this.acionouManutencao === 'true' ? this.horaManutencao : '',
      resolvida_pelo_operador: this.resolvidaOperador,
      procedimentos_adotados: this.resolvidaOperador === 'true' ? this.procedimentos : '',
    };

    if (this.anomId) payload.id = this.anomId;

    this.api.post<any>('/api/operacao/anormalidade/registro', payload).subscribe({
      next: (res: any) => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Registro de anormalidade salvo com sucesso!');
          this.router.navigate(['/home']);
        } else {
          this.toast.error(res.error || 'Erro desconhecido');
        }
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error('Erro ao salvar: ' + httpErrorMsg(err, 'Erro de conexão', ['error']));
      },
    });
  }
}
