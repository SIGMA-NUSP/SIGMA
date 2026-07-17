import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ErroCargaComponent } from './erro-carga.component';

/**
 * ErroCargaComponent (shared): a caixa de erro com retry que dá corpo ao canal de erro das
 * telas. É o ponto do canal que RENDERIZA — os specs das telas provam o estado (signal de
 * erro preenchido, retry re-dispara a carga) chamando os métodos direto; este spec prova que
 * o botão "Tentar novamente" de fato emite o evento que aciona aquele retry, e que a caixa
 * se anuncia com role="alert".
 */
describe('ErroCargaComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ErroCargaComponent] }).compileComponents();
  });

  function criar(mensagem: string) {
    const fixture = TestBed.createComponent(ErroCargaComponent);
    fixture.componentRef.setInput('mensagem', mensagem);
    fixture.detectChanges();
    return fixture;
  }

  it('exibe a mensagem recebida', () => {
    const fixture = criar('Não foi possível carregar as solicitações.');
    const texto = fixture.debugElement.query(By.css('.erro-carga-msg')).nativeElement.textContent.trim();
    expect(texto).toBe('Não foi possível carregar as solicitações.');
  });

  it('o botão "Tentar novamente" emite o evento de retry a cada clique', () => {
    const fixture = criar('Falhou.');
    const comp = fixture.componentInstance;
    const retries: void[] = [];
    comp.tentarNovamente.subscribe(() => retries.push(undefined));

    const botao = fixture.debugElement.query(By.css('button')).nativeElement as HTMLButtonElement;
    expect(botao.textContent?.trim()).toBe('Tentar novamente');
    expect(botao.type).toBe('button');   // dentro de <form> não pode submeter nada

    botao.click();
    botao.click();

    expect(retries).toHaveLength(2);     // o gatilho de recuperação continua disponível após a 1ª tentativa
  });

  it('anuncia-se como alerta (a falha não pode passar por estado vazio)', () => {
    const fixture = criar('Falhou.');
    const caixa = fixture.debugElement.query(By.css('.error-box')).nativeElement as HTMLElement;
    expect(caixa.getAttribute('role')).toBe('alert');
  });
});
