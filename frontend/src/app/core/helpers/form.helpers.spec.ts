import { SALA_DEMAIS_SALAS_ID, focusFirst } from './form.helpers';

describe('focusFirst', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('foca o elemento com o atributo name informado', () => {
    const input = document.createElement('input');
    input.setAttribute('name', 'campoObrigatorio');
    document.body.appendChild(input);

    focusFirst('campoObrigatorio');

    expect(document.activeElement).toBe(input);
  });

  it('retorna sempre false, mesmo quando o elemento existe', () => {
    const input = document.createElement('input');
    input.setAttribute('name', 'campoObrigatorio');
    document.body.appendChild(input);

    expect(focusFirst('campoObrigatorio')).toBe(false);
  });

  it('elemento inexistente não lança erro e retorna false', () => {
    expect(focusFirst('naoExiste')).toBe(false);
  });
});

describe('SALA_DEMAIS_SALAS_ID', () => {
  it('é a constante 11', () => {
    expect(SALA_DEMAIS_SALAS_ID).toBe(11);
  });
});
