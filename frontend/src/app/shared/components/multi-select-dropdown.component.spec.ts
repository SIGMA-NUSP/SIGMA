import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MultiSelectDropdownComponent, MultiSelectOption } from './multi-select-dropdown.component';

/**
 * MultiSelectDropdownComponent: TestBed + `fixture.componentRef.setInput` para os
 * signal inputs (`input.required`). É um componente CONTROLADO: `onToggle` NÃO muta
 * o input — emite uma lista nova e a seleção efetiva só muda quando o host re-passa
 * `selected`.
 */
describe('MultiSelectDropdownComponent', () => {
  const OPCOES: MultiSelectOption[] = [
    { id: 'a', label: 'Ana' },
    { id: 'b', label: 'Bruno' },
    { id: 'c', label: 'Carla' },
  ];

  let fixture: ComponentFixture<MultiSelectDropdownComponent>;
  let comp: MultiSelectDropdownComponent;

  async function montar(selected: string[], extra?: { locked?: string[]; placeholder?: string }) {
    await TestBed.configureTestingModule({
      imports: [MultiSelectDropdownComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(MultiSelectDropdownComponent);
    comp = fixture.componentInstance;
    fixture.componentRef.setInput('options', OPCOES);
    fixture.componentRef.setInput('selected', selected);
    if (extra?.locked) fixture.componentRef.setInput('lockedIds', extra.locked);
    if (extra?.placeholder) fixture.componentRef.setInput('placeholder', extra.placeholder);
    fixture.detectChanges();
  }

  // Evento sintético de checkbox `change` com o estado `checked` desejado.
  function change(checked: boolean): Event {
    return { target: { checked } } as unknown as Event;
  }

  it('onToggle emite lista NOVA e não muta o input (componente controlado)', async () => {
    const selecionadoInput = ['a'];
    await montar(selecionadoInput);
    let emitido: string[] | undefined;
    comp.selectionChange.subscribe(v => (emitido = v));

    comp.onToggle('b', change(true)); // marca 'b'
    expect(emitido).toEqual(['a', 'b']);
    expect(selecionadoInput).toEqual(['a']);    // array original do host intacto
    expect(comp.selected()).toEqual(['a']);     // signal ainda reflete o input (controlado)
    expect(emitido).not.toBe(selecionadoInput); // é uma referência nova, não o mesmo array
  });

  it('onToggle desmarcando remove o id e emite o restante', async () => {
    await montar(['a', 'b']);
    let emitido: string[] | undefined;
    comp.selectionChange.subscribe(v => (emitido = v));
    comp.onToggle('a', change(false));
    expect(emitido).toEqual(['b']);
  });

  it('onToggle marcando id já presente não duplica', async () => {
    await montar(['a']);
    let emitido: string[] | undefined;
    comp.selectionChange.subscribe(v => (emitido = v));
    comp.onToggle('a', change(true));
    expect(emitido).toEqual(['a']);
  });

  it('displayLabel: vazio mostra o placeholder informado', async () => {
    await montar([], { placeholder: 'Escolha as salas' });
    expect(comp.displayLabel()).toBe('Escolha as salas');
  });

  it('displayLabel: placeholder default quando nenhum é informado', async () => {
    await montar([]);
    expect(comp.displayLabel()).toBe('Selecione...');
  });

  it('displayLabel: junta os rótulos dos selecionados', async () => {
    await montar(['a', 'c']);
    expect(comp.displayLabel()).toBe('Ana, Carla');
  });

  it('displayLabel: id sem correspondência cai no próprio id', async () => {
    await montar(['a', 'zzz']);
    expect(comp.displayLabel()).toBe('Ana, zzz');
  });

  it('toggle alterna open; onDocClick fora fecha, dentro mantém', async () => {
    await montar(['a']);
    expect(comp.open()).toBe(false);
    comp.toggle();
    expect(comp.open()).toBe(true);
    // clique DENTRO do componente não fecha
    comp.onDocClick({ target: fixture.nativeElement } as unknown as MouseEvent);
    expect(comp.open()).toBe(true);
    // clique FORA fecha
    comp.onDocClick({ target: document.body } as unknown as MouseEvent);
    expect(comp.open()).toBe(false);
  });

  it('lockedIds: o checkbox travado renderiza disabled (impede o toggle no DOM)', async () => {
    await montar(['a'], { locked: ['a'] });
    comp.toggle();          // abre o dropdown para os .ms-option renderizarem
    fixture.detectChanges();
    const labels = fixture.nativeElement.querySelectorAll('.ms-option') as NodeListOf<HTMLElement>;
    // opções na ordem de OPCOES: a (locked), b, c
    const inputA = labels[0].querySelector('input') as HTMLInputElement;
    const inputB = labels[1].querySelector('input') as HTMLInputElement;
    expect(labels[0].classList.contains('ms-locked')).toBe(true);
    expect(inputA.disabled).toBe(true);
    expect(inputB.disabled).toBe(false);
  });
});
