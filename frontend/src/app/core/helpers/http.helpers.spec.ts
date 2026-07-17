import { erroCargaMsg, httpErrorMsg } from './http.helpers';

describe('httpErrorMsg', () => {
  it('retorna err.error.message quando presente (ordem default)', () => {
    const err = { error: { message: 'Falha de validação', error: 'ERR_CODE' } };
    expect(httpErrorMsg(err, 'fallback')).toBe('Falha de validação');
  });

  it('cai para err.error.error quando message ausente (ordem default)', () => {
    const err = { error: { error: 'ERR_CODE' } };
    expect(httpErrorMsg(err, 'fallback')).toBe('ERR_CODE');
  });

  it('respeita a ordem customizada de fields', () => {
    const err = { error: { message: 'Msg', error: 'ERR_CODE' } };
    expect(httpErrorMsg(err, 'fallback', ['error', 'message'])).toBe('ERR_CODE');
  });

  it('nenhum campo presente retorna o fallback', () => {
    const err = { error: {} };
    expect(httpErrorMsg(err, 'fallback')).toBe('fallback');
  });

  it('err null retorna o fallback', () => {
    expect(httpErrorMsg(null, 'fallback')).toBe('fallback');
  });
});

/**
 * Mensagem do canal de erro de CARGA: a orientação da TELA vem sempre na frente (é ela que
 * diz o que está em jogo e o que fazer), com o detalhe do backend anexado quando houver.
 */
describe('erroCargaMsg', () => {
  const GUIA = 'Não foi possível carregar os lotes. Não reenvie o PDF antes de recarregar.';

  it('500 REAL do backend: a orientação sobrevive e o detalhe vai entre parênteses', () => {
    // O `GlobalExceptionHandler` responde `{ok:false, error:"Erro interno do servidor"}` em TODO
    // 500. Usar `httpErrorMsg` direto aqui (ele PRIORIZA o corpo) apagaria a orientação da tela
    // justamente no caso mais comum — foi por isso que este helper nasceu.
    const err = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };
    expect(erroCargaMsg(err, GUIA)).toBe(`${GUIA} (Erro interno do servidor)`);
  });

  it('mensagem de negócio do backend também entra como detalhe', () => {
    const err = { status: 403, error: { message: 'Sem permissão.' } };
    expect(erroCargaMsg(err, GUIA)).toBe(`${GUIA} (Sem permissão.)`);
  });

  it('erro sem corpo (rede/timeout): só a orientação da tela', () => {
    expect(erroCargaMsg({ status: 0 }, GUIA)).toBe(GUIA);
    expect(erroCargaMsg(null, GUIA)).toBe(GUIA);
  });

  it('detalhe idêntico à orientação não é repetido', () => {
    expect(erroCargaMsg({ error: { message: GUIA } }, GUIA)).toBe(GUIA);
  });
});
