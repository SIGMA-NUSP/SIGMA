import { WritableSignal } from '@angular/core';
import { environment } from '../../../environments/environment';

/** Imagem exibida quando a pessoa não tem foto (ou a foto cadastrada não carrega). */
export const FOTO_ANONIMA = 'assets/imgs/usuario_anonimo.jpg';

/** Resolve a URL de foto vinda da API: absoluta é mantida; relativa ganha o apiBaseUrl. */
export function resolverFotoUrl(url: string | null | undefined): string {
  if (!url) return '';
  return url.startsWith('http') ? url : environment.apiBaseUrl + url;
}

/** Se a foto cadastrada não existir (404), cai para a imagem anônima (sem loop). */
export function fotoErrorFallback(event: Event): void {
  const img = event.target as HTMLImageElement;
  if (!img.src.includes('usuario_anonimo')) img.src = FOTO_ANONIMA;
}

// ── Upload de foto: espelho da whitelist do backend ─────────────────────────
// O backend só grava estes 5 formatos (AdminCrudService.EXTENSOES_IMAGEM) — o arquivo vai para um
// diretório servido publicamente, e aceitar a extensão que o cliente mandar seria XSS armazenado.
// Manter os dois lados alinhados: um `accept` mais largo aqui só produz um 400 depois do upload.

/** Extensões aceitas no upload de foto. */
export const FOTO_EXTENSOES = ['jpg', 'jpeg', 'png', 'gif', 'webp'] as const;

/** Valor do atributo `accept` dos inputs de foto (extensões + os MIME correspondentes). */
export const FOTO_ACCEPT = '.jpg,.jpeg,.png,.gif,.webp,image/jpeg,image/png,image/gif,image/webp';

/** Mensagem única de formato recusado (mesma redação da validação do backend). */
export const FOTO_FORMATO_INVALIDO = 'Formato de imagem não suportado. Envie JPG, PNG, GIF ou WEBP.';

/**
 * Vale a mesma ordem do backend: a extensão do nome decide; se ela não estiver na whitelist, o
 * content-type do arquivo é a segunda chance. Falso ⇒ o backend recusaria com 400.
 */
export function fotoFormatoAceito(file: File): boolean {
  const nome = file.name ?? '';
  const ext = nome.includes('.') ? nome.slice(nome.lastIndexOf('.') + 1).trim().toLowerCase() : '';
  if ((FOTO_EXTENSOES as readonly string[]).includes(ext)) return true;

  const tipo = (file.type ?? '').toLowerCase();
  return FOTO_EXTENSOES.some(e => tipo.includes(e));
}

/**
 * Lê o arquivo escolhido no input, recusando na hora o que o backend rejeitaria: avisa pela mensagem
 * de erro do formulário e limpa o campo (o `accept` filtra o seletor, mas o usuário pode trocar o
 * filtro para "todos os arquivos" ou arrastar o arquivo).
 *
 * @returns o arquivo aceito, ou null (nada escolhido ou formato recusado).
 */
export function aceitarFoto(input: HTMLInputElement, errorMsg: WritableSignal<string>): File | null {
  const file = input.files?.[0] ?? null;
  if (!file) return null;
  if (!fotoFormatoAceito(file)) {
    errorMsg.set(FOTO_FORMATO_INVALIDO);
    input.value = '';
    return null;
  }
  return file;
}
