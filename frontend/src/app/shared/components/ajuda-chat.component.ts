import { Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { FeatureFlagService } from '../../core/services/feature-flags.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { ErroCargaComponent } from './erro-carga.component';

/** Mensagem no formato do contrato de POST /api/ajuda/chat ({de, texto}). */
export interface MensagemChat {
  de: 'usuario' | 'assistente';
  texto: string;
}

/** O backend só repassa as últimas 6 ao provedor — não adianta enviar mais. */
const HISTORICO_MAX = 6;

/**
 * Chat de ajuda com IA sobre a página atual: botão flutuante + janelinha.
 * O componente se auto-esconde quando a flag `ajudaIa` está desligada, então o
 * host só precisa declarar `<app-ajuda-chat pagina="..." />` — quando o botão
 * virar global (pós-aprovação §4.3), basta mover a tag para o layout.
 *
 * A conversa vive em memória do componente (some ao navegar) e NADA de prompt
 * ou chave passa por aqui: o front envia só pergunta + histórico + id da página.
 */
@Component({
  selector: 'app-ajuda-chat',
  standalone: true,
  imports: [FormsModule, ErroCargaComponent],
  template: `
    @if (flags.isEnabled('ajudaIa')) {
      <button type="button" class="ajuda-fab" #fab (click)="alternar()"
              [attr.aria-expanded]="aberto()"
              [attr.aria-label]="aberto() ? 'Fechar chat de ajuda' : 'Abrir chat de ajuda'"
              [title]="aberto() ? 'Fechar chat de ajuda' : 'Abrir chat de ajuda'">
        @if (aberto()) {
          <span class="ajuda-fab__x" aria-hidden="true">×</span>
        } @else {
          <!-- Balão de conversa com reticências: lê como "chat de dúvidas".
               Tudo em currentColor — o vazio do balão mostra o fundo do botão. -->
          <svg class="ajuda-fab__icone" viewBox="0 0 24 24" fill="none"
               stroke="currentColor" stroke-width="2" stroke-linecap="round"
               stroke-linejoin="round" aria-hidden="true" focusable="false">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            <circle cx="8" cy="10" r="1.1" fill="currentColor" stroke="none" />
            <circle cx="12" cy="10" r="1.1" fill="currentColor" stroke="none" />
            <circle cx="16" cy="10" r="1.1" fill="currentColor" stroke="none" />
          </svg>
        }
      </button>

      @if (aberto()) {
        <div class="ajuda-janela" role="dialog" aria-label="Assistente de ajuda"
             (keydown.escape)="fecharPorEsc()">
          <div class="ajuda-cabecalho">
            <strong>{{ titulo() }}</strong>
            <button type="button" class="ajuda-fechar" (click)="alternar()" aria-label="Fechar">×</button>
          </div>

          <div class="ajuda-mensagens" #listaMensagens aria-live="polite">
            <p class="ajuda-aviso">Tire suas dúvidas sobre o uso desta página. Não digite dados pessoais</p>
            @for (m of mensagens(); track $index) {
              <div class="ajuda-msg" [class.de-usuario]="m.de === 'usuario'">{{ m.texto }}</div>
            }
            @if (enviando()) {
              <div class="ajuda-msg ajuda-digitando">digitando…</div>
            }
            @if (erro(); as mensagemErro) {
              <app-erro-carga [mensagem]="mensagemErro" (tentarNovamente)="tentarNovamente()" />
            }
          </div>

          <form class="ajuda-envio" (ngSubmit)="enviar()">
            <!-- readonly (não disabled): binding de disabled não coopera com ngModel e o
                 guard de enviar() já impede submissão dupla — aqui é só feedback visual -->
            <input name="pergunta" #campoPergunta [(ngModel)]="rascunho" maxlength="2000"
                   placeholder="Escreva sua dúvida…" autocomplete="off" [readonly]="enviando()" />
            <button type="submit" [disabled]="enviando() || !rascunho.trim()">Enviar</button>
          </form>
        </div>
      }
    }
  `,
  styles: [`
    /* Cor do botão (definida com o Douglas): balão claro + ícone azul-Senado — contrasta
       com a faixa azul do rodapé e com o fundo claro. Para trocar, mudar só estas 2 vars. */
    .ajuda-fab {
      --fab-bg: #fff;
      --fab-fg: var(--senado-azul);
      /* Sobe acima da faixa fixa do rodapé (32px) com folga visível — não some mais no azul. */
      position: fixed; right: 20px; bottom: 48px; width: 52px; height: 52px;
      border-radius: 50%; background: var(--fab-bg); color: var(--fab-fg);
      border: 1px solid var(--border); cursor: pointer; z-index: 900;
      display: flex; align-items: center; justify-content: center;
      box-shadow: 0 3px 10px rgba(0, 0, 0, .28);
      transition: transform .12s, box-shadow .12s;
      &:hover { box-shadow: 0 5px 14px rgba(0, 0, 0, .32); transform: translateY(-1px); }
      &:focus-visible { outline: 3px solid var(--primary); outline-offset: 2px; }
    }
    .ajuda-fab__icone { width: 27px; height: 27px; }
    .ajuda-fab__x { font-size: 1.7rem; line-height: 1; }
    .ajuda-janela {
      position: fixed; right: 20px; bottom: 112px;
      width: min(360px, calc(100vw - 32px)); height: min(480px, 70vh);
      background: #fff; border: 1px solid var(--border); border-radius: 12px;
      box-shadow: 0 8px 24px rgba(0, 0, 0, .2); overflow: hidden;
      display: flex; flex-direction: column; z-index: 900;
    }
    .ajuda-cabecalho {
      background: var(--senado-azul); color: #fff; padding: 10px 14px;
      display: flex; justify-content: space-between; align-items: center; gap: 8px;
      strong { font-size: .95rem; }
    }
    .ajuda-fechar { background: none; border: none; color: #fff; font-size: 1.2rem; cursor: pointer; line-height: 1; }
    .ajuda-mensagens { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    .ajuda-aviso { color: var(--muted); font-size: .75rem; margin: 0 0 4px; }
    .ajuda-msg {
      max-width: 85%; padding: 8px 10px; border-radius: 10px; background: #f1f5f9;
      font-size: .9rem; white-space: pre-wrap; overflow-wrap: break-word; align-self: flex-start;
    }
    .ajuda-msg.de-usuario { background: var(--senado-azul); color: #fff; align-self: flex-end; }
    .ajuda-digitando { color: var(--muted); font-style: italic; }
    .ajuda-envio { display: flex; gap: 8px; padding: 10px; border-top: 1px solid var(--border); }
    .ajuda-envio input { flex: 1; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: .9rem; }
    .ajuda-envio button {
      padding: 8px 14px; background: var(--primary); color: #fff; border: none;
      border-radius: 6px; cursor: pointer; font-size: .9rem;
      &:disabled { opacity: .5; cursor: not-allowed; }
      &:hover:not(:disabled) { background: var(--primary-hover); }
    }
  `],
})
export class AjudaChatComponent {
  protected readonly flags = inject(FeatureFlagService);
  private readonly api = inject(ApiService);

  /** Identificador da página no mapa de manuais do backend (ex.: 'ponto-banco'). */
  pagina = input.required<string>();
  titulo = input('Ajuda');

  aberto = signal(false);
  mensagens = signal<MensagemChat[]>([]);
  enviando = signal(false);
  erro = signal<string | null>(null);
  rascunho = '';

  /** Última tentativa que falhou — o "Tentar novamente" reenvia sem duplicar a bolha do usuário. */
  private pendente: { pergunta: string; historico: MensagemChat[] } | null = null;

  private readonly listaMensagens = viewChild<ElementRef<HTMLElement>>('listaMensagens');
  private readonly campoPergunta = viewChild<ElementRef<HTMLInputElement>>('campoPergunta');
  private readonly fab = viewChild<ElementRef<HTMLButtonElement>>('fab');

  alternar(): void {
    const abrindo = !this.aberto();
    this.aberto.set(abrindo);
    if (abrindo) {
      // A conversa pode ter crescido (ou falhado) com a janela fechada: reabrir precisa
      // mostrar o FIM da lista — e o padrão ARIA de diálogo pede o foco dentro dele.
      this.rolarParaFim();
      setTimeout(() => this.campoPergunta()?.nativeElement.focus());
    }
  }

  fecharPorEsc(): void {
    this.aberto.set(false);
    this.fab()?.nativeElement.focus();
  }

  enviar(): void {
    const pergunta = this.rascunho.trim();
    if (!pergunta || this.enviando()) return;
    this.rascunho = '';
    // O histórico é capturado ANTES de anexar a pergunta — ela vai no campo próprio.
    const historico = this.mensagens().slice(-HISTORICO_MAX);
    this.mensagens.update(lista => [...lista, { de: 'usuario', texto: pergunta }]);
    this.disparar({ pergunta, historico });
  }

  tentarNovamente(): void {
    if (this.pendente && !this.enviando()) this.disparar(this.pendente);
  }

  private disparar(tentativa: { pergunta: string; historico: MensagemChat[] }): void {
    this.pendente = tentativa;
    this.erro.set(null);
    this.enviando.set(true);
    this.rolarParaFim();
    this.api.post<{ ok: boolean; resposta: string }>('/api/ajuda/chat', {
      pergunta: tentativa.pergunta,
      pagina: this.pagina(),
      historico: tentativa.historico,
    }).subscribe({
      next: r => {
        this.enviando.set(false);
        this.pendente = null;
        this.mensagens.update(lista => [...lista, { de: 'assistente', texto: r.resposta }]);
        this.rolarParaFim();
      },
      error: err => {
        this.enviando.set(false);
        this.erro.set(httpErrorMsg(err,
          'Não foi possível falar com o assistente agora. Tente novamente.', ['error']));
        this.rolarParaFim();
      },
    });
  }

  /** Mantém a última mensagem visível (após o render — por isso o setTimeout). */
  private rolarParaFim(): void {
    setTimeout(() => {
      const el = this.listaMensagens()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
