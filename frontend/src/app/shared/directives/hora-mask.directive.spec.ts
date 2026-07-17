import { TestBed } from '@angular/core/testing';
import { HORA_RE, HoraMaskDirective } from './hora-mask.directive';

/**
 * HoraMaskDirective e `HORA_RE`: digitação progressiva (':' após o 2º dígito), colar
 * 4 dígitos, backspace após ':' e truncamento > 4 dígitos. `onInput` é exercitado com
 * um Event sintético `{ target:{value}, inputType }` e a emissão capturada por espião
 * em `horaChange.emit` — sem fixture/host component.
 * Armadilha: `horaChange = output<string>()` (signal-based) chama
 * `assertInInjectionContext` no inicializador — `new HoraMaskDirective()` cru lança
 * NG0203; instanciar via `TestBed.runInInjectionContext`.
 */
function makeDirective(): HoraMaskDirective {
  return TestBed.runInInjectionContext(() => new HoraMaskDirective());
}

describe('HORA_RE', () => {
  it('aceita horários válidos 00:00–23:59', () => {
    for (const ok of ['00:00', '09:05', '12:34', '23:59', '19:00', '20:59']) {
      expect(HORA_RE.test(ok)).toBe(true);
    }
  });

  it('rejeita hora > 23 e minuto > 59', () => {
    for (const bad of ['24:00', '25:10', '12:60', '00:99', '23:99', '99:99']) {
      expect(HORA_RE.test(bad)).toBe(false);
    }
  });

  it('rejeita formatos malformados (sem dois-pontos, dígito faltando, texto)', () => {
    for (const bad of ['1234', '1:23', '12:3', '12-34', '', 'ab:cd', '123:45', '1:2']) {
      expect(HORA_RE.test(bad)).toBe(false);
    }
  });
});

describe('HoraMaskDirective.onInput', () => {
  // Instancia direto e captura o valor mascarado tanto no DOM (target.value) quanto
  // na emissão. `fire` devolve o valor final do input após a máscara.
  function setup() {
    const dir = makeDirective();
    const emit = vi.spyOn(dir.horaChange, 'emit');
    return { dir, emit };
  }

  function fire(dir: HoraMaskDirective, value: string, inputType = 'insertText'): string {
    const target = { value } as HTMLInputElement;
    dir.onInput({ target, inputType } as unknown as Event);
    return target.value;
  }

  it('digitação progressiva insere o dois-pontos após o 2º dígito', () => {
    const { dir, emit } = setup();
    expect(fire(dir, '1')).toBe('1');        // 1 dígito
    expect(fire(dir, '12')).toBe('12:');     // 2 dígitos → ':' automático
    expect(fire(dir, '12:3')).toBe('12:3');  // usuário digitou o 3º
    expect(fire(dir, '12:34')).toBe('12:34');
    expect(emit).toHaveBeenLastCalledWith('12:34');
  });

  it('colar 4 dígitos vira HH:MM', () => {
    const { dir, emit } = setup();
    expect(fire(dir, '1234', 'insertFromPaste')).toBe('12:34');
    expect(emit).toHaveBeenCalledWith('12:34');
  });

  it('backspace após o dois-pontos não o re-insere (inputType delete*)', () => {
    const { dir, emit } = setup();
    // Mesmo value '12', mas apagando: NÃO deve virar '12:' (senão o backspace trava)
    expect(fire(dir, '12', 'deleteContentBackward')).toBe('12');
    expect(emit).toHaveBeenLastCalledWith('12');
    // Contraprova: sem apagar, o mesmo '12' vira '12:'
    expect(fire(dir, '12', 'insertText')).toBe('12:');
  });

  it('trunca em 4 dígitos', () => {
    const { dir } = setup();
    expect(fire(dir, '123456')).toBe('12:34');
    expect(fire(dir, '12:3456')).toBe('12:34'); // ':' e extras ignorados na contagem
  });

  it('filtra não-dígitos antes de mascarar', () => {
    const { dir } = setup();
    expect(fire(dir, 'a1b2c')).toBe('12:');
    expect(fire(dir, '')).toBe('');
  });

  it('escreve o valor mascarado de volta no input e emite o mesmo valor', () => {
    const { dir, emit } = setup();
    const out = fire(dir, '9');
    expect(out).toBe('9');                  // efeito no DOM
    expect(emit).toHaveBeenCalledWith('9'); // emissão idêntica ao DOM
  });

  it('inputType ausente é tratado como "não apagando" (?? false)', () => {
    const dir = makeDirective();
    const target = { value: '12' } as HTMLInputElement;
    dir.onInput({ target } as unknown as Event); // sem inputType
    expect(target.value).toBe('12:');
  });
});
