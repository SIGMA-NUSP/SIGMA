/**
 * Extrai a mensagem de erro de uma resposta HTTP do backend (formato dual do
 * ApiResponse: `error.message` e/ou `error.error`), caindo no fallback quando
 * nenhum campo vier preenchido.
 *
 * `fields` define QUAIS campos consultar e em QUE ordem — cada chamador
 * preserva exatamente o comportamento que tinha inline (há pontos que liam só
 * `message`, só `error`, ou os dois em ordens opostas).
 */
export function httpErrorMsg(
  err: any,
  fallback: string,
  fields: ReadonlyArray<'message' | 'error'> = ['message', 'error'],
): string {
  for (const f of fields) {
    const v = err?.error?.[f];
    if (v) return v;
  }
  return fallback;
}

/**
 * Mensagem do canal de erro de CARGA de listagem (C7): a orientação da TELA vem SEMPRE
 * na frente — é ela que diz o que está em jogo e o que fazer ("não reenvie o PDF", "o prazo
 * de retificação corre") —, com o detalhe do backend anexado quando houver.
 *
 * ⚠️ `httpErrorMsg` sozinho NÃO serve aqui: ele PRIORIZA o corpo da resposta, e o backend
 * responde `{ok:false, error:"Erro interno do servidor"}` em todo 500 (`GlobalExceptionHandler`)
 * — a orientação da tela nunca chegaria ao usuário justamente no caso mais comum.
 */
export function erroCargaMsg(err: any, guia: string): string {
  const detalhe = httpErrorMsg(err, '');
  return detalhe && detalhe !== guia ? `${guia} (${detalhe})` : guia;
}
