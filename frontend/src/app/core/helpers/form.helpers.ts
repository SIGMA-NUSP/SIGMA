/** ID da sala "Demais Salas" — quando selecionada, os forms exigem o nome livre da sala. */
export const SALA_DEMAIS_SALAS_ID = 11;

/**
 * Foca o campo do form com o atributo `name` informado (1º campo inválido na
 * validação do submit). Retorna sempre false, para uso como guard do submit.
 */
export function focusFirst(name: string): boolean {
  const el = document.querySelector<HTMLElement>(`[name="${name}"]`);
  if (el) el.focus();
  return false;
}
