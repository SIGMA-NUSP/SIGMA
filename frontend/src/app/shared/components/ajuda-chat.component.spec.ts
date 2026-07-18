import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { AjudaChatComponent, MensagemChat } from './ajuda-chat.component';
import { ApiService } from '../../core/services/api.service';
import { FeatureFlagService } from '../../core/services/feature-flags.service';

/**
 * AjudaChatComponent (shared): o chat de ajuda com IA do teste piloto. O spec prova o
 * contrato com o backend (POST /api/ajuda/chat com pergunta + id da página + histórico
 * limitado) e os estados da janelinha: fail-safe da flag, "digitando…", erro com retry
 * que não duplica a bolha do usuário. A qualidade da RESPOSTA é do backend/provedor —
 * aqui só o transporte e a UI.
 */
describe('AjudaChatComponent', () => {
  let apiPost: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    apiPost = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AjudaChatComponent],
      providers: [{ provide: ApiService, useValue: { post: apiPost } }],
    }).compileComponents();
  });

  function criar(flagLigada = true) {
    TestBed.inject(FeatureFlagService).setFlags(flagLigada ? { ajudaIa: true } : {});
    const fixture = TestBed.createComponent(AjudaChatComponent);
    fixture.componentRef.setInput('pagina', 'ponto-banco');
    fixture.componentRef.setInput('titulo', 'Ajuda — Ponto e Banco');
    fixture.detectChanges();
    return fixture;
  }

  function abrir(fixture: ReturnType<typeof criar>) {
    fixture.debugElement.query(By.css('.ajuda-fab')).nativeElement.click();
    fixture.detectChanges();
  }

  function perguntar(fixture: ReturnType<typeof criar>, texto: string) {
    fixture.componentInstance.rascunho = texto;
    fixture.componentInstance.enviar();
    fixture.detectChanges();
  }

  it('fail-safe: sem a flag ajudaIa nem o botão flutuante renderiza', () => {
    const fixture = criar(false);
    expect(fixture.debugElement.query(By.css('.ajuda-fab'))).toBeNull();
  });

  it('com a flag: o botão abre a janelinha com o aviso de privacidade; o × fecha', () => {
    const fixture = criar();
    expect(fixture.debugElement.query(By.css('.ajuda-janela'))).toBeNull();

    abrir(fixture);
    expect(fixture.debugElement.query(By.css('.ajuda-janela'))).not.toBeNull();
    expect(fixture.debugElement.query(By.css('.ajuda-aviso')).nativeElement.textContent)
      .toContain('Não digite dados pessoais');

    fixture.debugElement.query(By.css('.ajuda-fechar')).nativeElement.click();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.ajuda-janela'))).toBeNull();
  });

  it('envia pergunta + id da página (histórico vazio na 1ª) e exibe as duas bolhas', () => {
    apiPost.mockReturnValue(of({ ok: true, resposta: 'Folgas só de amanhã em diante.' }));
    const fixture = criar();
    abrir(fixture);

    perguntar(fixture, '  Posso marcar hoje?  ');

    expect(apiPost).toHaveBeenCalledWith('/api/ajuda/chat', {
      pergunta: 'Posso marcar hoje?',
      pagina: 'ponto-banco',
      historico: [],
    });
    const bolhas = fixture.debugElement.queryAll(By.css('.ajuda-msg'));
    expect(bolhas).toHaveLength(2);
    expect(bolhas[0].nativeElement.textContent.trim()).toBe('Posso marcar hoje?');
    expect(bolhas[0].nativeElement.classList).toContain('de-usuario');
    expect(bolhas[1].nativeElement.textContent.trim()).toBe('Folgas só de amanhã em diante.');
  });

  it('pergunta vazia (ou só espaços) não chama a API', () => {
    const fixture = criar();
    abrir(fixture);
    // sem detectChanges: o rascunho não-enviado permanece no campo (e o ngModel
    // re-sincronizaria só com digitação real — setá-lo direto dispararia NG0100)
    fixture.componentInstance.rascunho = '   ';
    fixture.componentInstance.enviar();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('histórico acompanha as perguntas seguintes, cortado nas últimas 6 mensagens', () => {
    let n = 0;
    apiPost.mockImplementation(() => of({ ok: true, resposta: `resposta${++n}` }));
    const fixture = criar();
    abrir(fixture);

    for (let i = 1; i <= 4; i++) perguntar(fixture, `pergunta${i}`);

    // Na 4ª pergunta já existem 6 mensagens (3 pares) — todas seguem
    const quarta = apiPost.mock.calls[3][1];
    expect(quarta.historico).toHaveLength(6);

    perguntar(fixture, 'pergunta5');
    // Na 5ª existem 8 mensagens; só as 6 últimas seguem (o backend descartaria o resto)
    const quinta = apiPost.mock.calls[4][1];
    expect(quinta.historico).toHaveLength(6);
    expect(quinta.historico[0]).toEqual({ de: 'usuario', texto: 'pergunta2' } satisfies MensagemChat);
    expect(quinta.historico[5]).toEqual({ de: 'assistente', texto: 'resposta4' } satisfies MensagemChat);
  });

  it('enquanto aguarda: "digitando…" visível e campo/botão desabilitados', () => {
    const pendura = new Subject<{ ok: boolean; resposta: string }>();
    apiPost.mockReturnValue(pendura.asObservable());
    const fixture = criar();
    abrir(fixture);

    perguntar(fixture, 'Oi?');

    expect(fixture.debugElement.query(By.css('.ajuda-digitando'))).not.toBeNull();
    expect(fixture.debugElement.query(By.css('.ajuda-envio input')).nativeElement.readOnly).toBe(true);
    expect(fixture.debugElement.query(By.css('.ajuda-envio button')).nativeElement.disabled).toBe(true);

    pendura.next({ ok: true, resposta: 'pronto' });
    pendura.complete();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.ajuda-digitando'))).toBeNull();
    expect(fixture.debugElement.query(By.css('.ajuda-envio input')).nativeElement.readOnly).toBe(false);
  });

  it('erro: mostra a mensagem do backend ({error}) no <app-erro-carga> e o retry reenvia sem duplicar a bolha', () => {
    apiPost.mockReturnValueOnce(throwError(() =>
      ({ error: { ok: false, error: 'Muitas perguntas em pouco tempo.' } })));
    const fixture = criar();
    abrir(fixture);

    perguntar(fixture, 'Oi?');

    const caixa = fixture.debugElement.query(By.css('app-erro-carga'));
    expect(caixa.nativeElement.textContent).toContain('Muitas perguntas em pouco tempo.');
    expect(fixture.debugElement.queryAll(By.css('.ajuda-msg.de-usuario'))).toHaveLength(1);

    apiPost.mockReturnValueOnce(of({ ok: true, resposta: 'agora foi' }));
    caixa.query(By.css('button')).nativeElement.click();
    fixture.detectChanges();

    // mesma pergunta e mesmo histórico da tentativa original
    expect(apiPost).toHaveBeenCalledTimes(2);
    expect(apiPost.mock.calls[1][1]).toEqual(apiPost.mock.calls[0][1]);
    expect(fixture.debugElement.query(By.css('app-erro-carga'))).toBeNull();
    expect(fixture.debugElement.queryAll(By.css('.ajuda-msg.de-usuario'))).toHaveLength(1);
    const bolhas = fixture.debugElement.queryAll(By.css('.ajuda-msg'));
    expect(bolhas[bolhas.length - 1].nativeElement.textContent.trim()).toBe('agora foi');
  });

  it('erro sem corpo do backend cai no fallback amigável', () => {
    apiPost.mockReturnValue(throwError(() => ({ status: 0 })));
    const fixture = criar();
    abrir(fixture);

    perguntar(fixture, 'Oi?');

    expect(fixture.debugElement.query(By.css('app-erro-carga')).nativeElement.textContent)
      .toContain('Não foi possível falar com o assistente agora.');
  });

  it('guard de voo: enviar() e tentarNovamente() com requisição pendente não disparam 2º POST', () => {
    const pendura = new Subject<{ ok: boolean; resposta: string }>();
    apiPost.mockReturnValue(pendura.asObservable());
    const fixture = criar();
    abrir(fixture);

    perguntar(fixture, 'Oi?');
    // Enter repetido durante o voo (input readonly ainda submete o form);
    // sem detectChanges — o rascunho seguiria dessincronizado do ngModel (NG0100)
    fixture.componentInstance.rascunho = 'Oi de novo?';
    fixture.componentInstance.enviar();
    fixture.componentInstance.tentarNovamente();

    expect(apiPost).toHaveBeenCalledTimes(1);
  });

  it('Esc fecha a janelinha', () => {
    const fixture = criar();
    abrir(fixture);

    fixture.debugElement.query(By.css('.ajuda-janela')).nativeElement
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.ajuda-janela'))).toBeNull();
  });
});
