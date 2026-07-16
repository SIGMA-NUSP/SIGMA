import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, NavigationEnd } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { filter } from 'rxjs/operators';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { ToastService } from './toast.component';

interface AvisoPendente {
  cadastro_id: string;
  tipo: string;
  exige_ciencia: boolean;
  manter_apos_ciencia: boolean;
  mensagens: { ordem: number; texto: string }[];
}

/** Rotas onde avisos de AGENDA também aparecem (além de ESCALA/PESSOAL/GERAL). */
const ROTAS_AGENDA = ['/agenda', '/admin/agenda', '/tecnico/agenda'];
const POLL_MS = 60_000;

/**
 * Modal global de avisos. Montado uma vez no MainLayout (como o <app-toast>),
 * aparece para qualquer papel logado em QUALQUER página. Reconsulta os pendentes
 * a cada navegação e periodicamente (mesmo parado). Por aviso:
 *  - exige_ciencia (ESCALA/PESSOAL): botão "Estou ciente" → grava no banco e some;
 *  - sem ciência (GERAL/AGENDA): botão "Fechar" → dispensa só nesta sessão.
 * AGENDA só é consultado nas rotas de Agenda Legislativa.
 */
@Component({
  selector: 'app-aviso-popup',
  standalone: true,
  imports: [FormsModule],
  template: `
    @if (avisos()[0]; as a) {
      <div class="modal-overlay">
        <div class="card-custom modal-card">
          <h2 class="modal-title">
            {{ avisos().length === 1 ? 'Você tem um aviso' : 'Você tem ' + avisos().length + ' avisos' }}
          </h2>

          @if (a.mensagens.length === 1) {
            <div class="aviso-box"><p class="aviso-msg">{{ a.mensagens[0].texto }}</p></div>
          } @else {
            @for (m of a.mensagens; track m.ordem) {
              <div class="aviso-box">
                <div class="aviso-header">Aviso nº {{ m.ordem }}</div>
                <p class="aviso-msg">{{ m.texto }}</p>
              </div>
            }
          }

          @if (a.exige_ciencia) {
            <label class="aviso-ciente">
              <input type="checkbox" [(ngModel)]="ciente"> Estou ciente
            </label>
            <div class="modal-actions">
              <button class="btn-primary-custom" [disabled]="!ciente || enviando()" (click)="confirmarCiencia(a)">
                Confirmar
              </button>
            </div>
          } @else {
            <div class="modal-actions">
              <button class="btn-primary-custom" (click)="fechar(a)">Fechar</button>
            </div>
          }
        </div>
      </div>
    }
  `,
})
export class AvisoPopupComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private toast = inject(ToastService);
  private router = inject(Router);

  avisos = signal<AvisoPendente[]>([]);
  ciente = false;
  enviando = signal(false);

  private subs = new Subscription();
  /** Avisos sem ciência (GERAL/AGENDA) dispensados nesta sessão (memória; reaparecem no próximo login). */
  private dispensados = new Set<string>();

  ngOnInit(): void {
    // Reconsulta a cada mudança de página e periodicamente (vê o aviso mesmo parado).
    this.subs.add(
      this.router.events.pipe(filter(e => e instanceof NavigationEnd)).subscribe(() => this.recarregar())
    );
    this.subs.add(interval(POLL_MS).subscribe(() => this.recarregar()));
    this.recarregar();
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  private recarregar(): void {
    if (!this.auth.isLoggedIn()) { this.avisos.set([]); return; }
    const path = this.router.url.split('?')[0];
    const contexto = ROTAS_AGENDA.includes(path) ? 'agenda' : 'geral';
    this.api.get<{ ok: boolean; data: AvisoPendente[] }>('/api/avisos/pendentes', { contexto }).subscribe({
      next: res => {
        const topoAntes = this.avisos()[0]?.cadastro_id;
        const fresh = (res?.data ?? []).filter(a => !this.dispensados.has(a.cadastro_id));
        this.avisos.set(fresh);
        if (this.avisos()[0]?.cadastro_id !== topoAntes) this.ciente = false;
      },
      error: () => { /* silencioso: o aviso é acessório e não deve bloquear o uso */ },
    });
  }

  confirmarCiencia(a: AvisoPendente): void {
    if (!this.ciente || this.enviando()) return;
    this.enviando.set(true);
    this.api.post(`/api/avisos/${a.cadastro_id}/ciencia`, {}).subscribe({
      next: () => { this.remover(a.cadastro_id); this.enviando.set(false); },
      error: () => { this.toast.error('Erro ao registrar ciência.'); this.enviando.set(false); },
    });
  }

  fechar(a: AvisoPendente): void {
    // GERAL/AGENDA: dispensa só nesta sessão (reaparece no próximo login).
    this.dispensados.add(a.cadastro_id);
    this.remover(a.cadastro_id);
  }

  private remover(cadastroId: string): void {
    this.avisos.update(list => list.filter(x => x.cadastro_id !== cadastroId));
    this.ciente = false;
  }
}
