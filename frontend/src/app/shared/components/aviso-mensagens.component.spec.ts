import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AvisoMensagensComponent } from './aviso-mensagens.component';

/**
 * AvisoMensagensComponent: bloco de 1..10 mensagens. `model()` signal setado por
 * `componentRef.setInput`. As operações emitem uma lista NOVA (não mutam a anterior).
 */
describe('AvisoMensagensComponent', () => {
  let fixture: ComponentFixture<AvisoMensagensComponent>;
  let comp: AvisoMensagensComponent;

  async function montar(mensagens: string[] = ['']) {
    await TestBed.configureTestingModule({ imports: [AvisoMensagensComponent] }).compileComponents();
    fixture = TestBed.createComponent(AvisoMensagensComponent);
    comp = fixture.componentInstance;
    fixture.componentRef.setInput('mensagens', mensagens);
    fixture.detectChanges();
  }

  it('atualizar troca o texto no índice e mantém o resto', async () => {
    await montar(['a', 'b']);
    comp.atualizar(1, 'B!');
    expect(comp.mensagens()).toEqual(['a', 'B!']);
  });

  it('add acrescenta uma mensagem vazia, respeitando o teto de 10', async () => {
    await montar(['x']);
    comp.add();
    expect(comp.mensagens()).toEqual(['x', '']);

    comp.mensagens.set(Array.from({ length: 10 }, (_, i) => 'm' + i));
    comp.add();
    expect(comp.mensagens().length).toBe(10);   // teto respeitado
  });

  it('remover tira a última, nunca abaixo de uma', async () => {
    await montar(['a', 'b']);
    comp.remover();
    expect(comp.mensagens()).toEqual(['a']);
    comp.remover();
    expect(comp.mensagens()).toEqual(['a']);     // a última nunca some
  });

  it('renderiza um textarea por mensagem; "Remover" só aparece com 2+ mensagens', async () => {
    await montar(['a', 'b']);
    expect(fixture.nativeElement.querySelectorAll('textarea')).toHaveLength(2);
    const botoes = () => Array.from(
      fixture.nativeElement.querySelectorAll('.msg-actions button') as NodeListOf<HTMLButtonElement>)
      .map(b => b.textContent?.trim());
    expect(botoes()).toContain('+ Novo Aviso');
    expect(botoes()).toContain('Remover');

    fixture.componentRef.setInput('mensagens', ['só uma']);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('textarea')).toHaveLength(1);
    expect(botoes()).not.toContain('Remover');   // 1 mensagem: sem "Remover"
  });
});
