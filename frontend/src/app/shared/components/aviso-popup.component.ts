import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, NavigationEnd } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { filter } from 'rxjs/operators';
import { ApiService } from '../../core/services/api.service';
import { AuthService, AVISOS_CIENTES_SESSAO_KEY } from '../../core/services/auth.service';
import { CategoriaAviso, categoriaAviso, tituloComunicacao } from '../../core/helpers/aviso-categoria.helpers';
import { CategoriaSeloComponent } from './categoria-selo.component';
import { ToastService } from './toast.component';

interface AvisoPendente {
  cadastro_id: string;
  tipo: string;
  /** Categoria da comunicação (AVISO/COMUNICADO/MENSAGEM/NOTIFICACAO) — comanda rótulo e cores. */
  categoria: string;
  /** Contexto que qualifica a categoria no título; vazio quando a categoria basta. */
  titulo: string;
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
 * a cada navegação e periodicamente (mesmo parado). A CATEGORIA que o backend envia
 * comanda o título ("Comunicado — Agenda Legislativa"), o selo e as cores da caixa;
 * um aviso sem contexto sai só com o nome da categoria. Quem manda no botão é o
 * {@code exige_ciencia} do PAYLOAD — escolha do cadastro, não do tipo. Por aviso:
 *  - com ciência: botão "Estou ciente" → grava no banco e some; com "manter após
 *    ciência", o servidor continua devolvendo o aviso — a exibição é segurada por
 *    sessão de LOGIN (sessionStorage, sobrevive a F5; o AuthService limpa a chave no
 *    login) → o aviso confirmado só volta no próximo login;
 *  - sem ciência: o "visto" é registrado no servidor NA EXIBIÇÃO (1× por aviso,
 *    fire-and-forget) — o backend deixa de devolvê-lo para o usuário em qualquer
 *    sessão; o botão "Fechar" só tira a caixa da tela.
 * AGENDA só é consultado nas rotas de Agenda Legislativa.
 */
@Component({
  selector: 'app-aviso-popup',
  standalone: true,
  imports: [FormsModule, CategoriaSeloComponent],
  template: `
    @if (avisos()[0]; as a) {
      <div class="modal-overlay">
        <div class="card-custom modal-card modal-cat" [class]="categoria(a).classe">
          <app-categoria-selo [categoria]="a.categoria" />
          <h2 class="modal-title">{{ titulo(a) }}</h2>

          @for (m of a.mensagens; track m.ordem) {
            <div class="aviso-box"><p class="aviso-msg">{{ m.texto }}</p></div>
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
  styles: [`
    app-categoria-selo { margin-bottom: 8px; }
  `],
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
  /**
   * Avisos sem ciência fechados nesta sessão. Quem os tira de vez é o "visto" gravado no servidor;
   * esta memória cobre a janela até a próxima consulta — e o caso em que o POST falhou.
   */
  private dispensados = new Set<string>();
  /** Avisos cujo "visto" já foi disparado nesta sessão (evita repetir o POST no polling/navegação). */
  private vistoDisparado = new Set<string>();
  /**
   * Avisos "manter após ciência" confirmados nesta sessão de LOGIN. O servidor
   * continua devolvendo-os (é o que os faz voltar a cada login); sem esta memória, o popup
   * reabriria a CADA navegação/polling. Persistido em sessionStorage: F5 não reexibe; o
   * AuthService limpa a chave no login → volta a exibir 1× por sessão.
   */
  private cientesSessao = this.lerCientesSessao();

  /** Rótulo, cores e ícone da categoria do aviso. */
  categoria(a: AvisoPendente): CategoriaAviso {
    return categoriaAviso(a.categoria);
  }

  /** O contexto da comunicação ("Verificação"); sem ele, o nome da categoria. */
  titulo(a: AvisoPendente): string {
    return tituloComunicacao(a.categoria, a.titulo);
  }

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
        const fresh = (res?.data ?? [])
          .filter(a => !this.dispensados.has(a.cadastro_id) && !this.cientesSessao.has(a.cadastro_id));
        this.avisos.set(fresh);
        if (this.avisos()[0]?.cadastro_id !== topoAntes) this.ciente = false;
        this.marcarVistoTopo();
      },
      error: () => { /* silencioso: o aviso é acessório e não deve bloquear o uso */ },
    });
  }

  confirmarCiencia(a: AvisoPendente): void {
    if (!this.ciente || this.enviando()) return;
    this.enviando.set(true);
    this.api.post(`/api/avisos/${a.cadastro_id}/ciencia`, {}).subscribe({
      next: () => {
        // "Manter após ciência": o servidor vai continuar devolvendo o aviso — segura a
        // reexibição até o próximo login (senão o popup reabre a cada navegação/polling).
        if (a.manter_apos_ciencia) this.marcarCienteSessao(a.cadastro_id);
        this.remover(a.cadastro_id);
        this.enviando.set(false);
      },
      error: () => { this.toast.error('Erro ao registrar ciência.'); this.enviando.set(false); },
    });
  }

  fechar(a: AvisoPendente): void {
    // Sem ciência a registrar: o servidor já recebeu o "visto" na exibição — aqui só some da tela.
    this.dispensados.add(a.cadastro_id);
    this.remover(a.cadastro_id);
  }

  private remover(cadastroId: string): void {
    this.avisos.update(list => list.filter(x => x.cadastro_id !== cadastroId));
    this.ciente = false;
    this.marcarVistoTopo();   // o próximo da fila passou a ser exibido
  }

  /**
   * Comunicação sem ciência é "exibida no máximo 1× por usuário": no momento em que passa a ser
   * exibida (vira o topo da fila), registra o "visto" no servidor — fire-and-forget, falha
   * silenciosa (o aviso é acessório; se o registro falhar, ele só reaparece noutra sessão). O Set
   * evita repetir o POST a cada polling/navegação enquanto o aviso segue na tela.
   */
  private marcarVistoTopo(): void {
    const topo = this.avisos()[0];
    if (!topo || topo.exige_ciencia || this.vistoDisparado.has(topo.cadastro_id)) return;
    this.vistoDisparado.add(topo.cadastro_id);
    this.api.post(`/api/avisos/${topo.cadastro_id}/visto`, {}).subscribe({
      next: () => { /* nada a fazer: o servidor já filtra este aviso nas próximas consultas */ },
      error: () => { /* silencioso: o visto é acessório e não deve bloquear o uso */ },
    });
  }

  private lerCientesSessao(): Set<string> {
    try { return new Set(JSON.parse(sessionStorage.getItem(AVISOS_CIENTES_SESSAO_KEY) ?? '[]')); }
    catch { return new Set(); }   // storage indisponível/corrompido: degrada para memória
  }

  private marcarCienteSessao(cadastroId: string): void {
    this.cientesSessao.add(cadastroId);
    try { sessionStorage.setItem(AVISOS_CIENTES_SESSAO_KEY, JSON.stringify([...this.cientesSessao])); }
    catch { /* sem storage (modo privado/quota): fica só em memória — F5 pode reexibir */ }
  }
}
