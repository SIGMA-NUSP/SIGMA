import { signal } from '@angular/core';
import {
  aceitarFoto, FOTO_ACCEPT, FOTO_ANONIMA, FOTO_FORMATO_INVALIDO,
  fotoErrorFallback, fotoFormatoAceito, resolverFotoUrl,
} from './foto.helpers';
import { environment } from '../../../environments/environment';

describe('resolverFotoUrl', () => {
  it('valor falsy retorna string vazia', () => {
    expect(resolverFotoUrl(null)).toBe('');
    expect(resolverFotoUrl(undefined)).toBe('');
    expect(resolverFotoUrl('')).toBe('');
  });

  it('URL absoluta (começa com "http") é devolvida como está', () => {
    expect(resolverFotoUrl('http://outro-host/foto.jpg')).toBe('http://outro-host/foto.jpg');
  });

  it('caminho relativo ganha o prefixo environment.apiBaseUrl', () => {
    expect(resolverFotoUrl('/files/operadores/1.jpg')).toBe(environment.apiBaseUrl + '/files/operadores/1.jpg');
  });
});

describe('fotoErrorFallback', () => {
  it('troca img.src pela imagem anônima quando ainda não é ela', () => {
    const img = document.createElement('img');
    img.src = 'http://localhost/files/operadores/1.jpg';
    fotoErrorFallback({ target: img } as unknown as Event);
    expect(img.src).toContain('usuario_anonimo');
  });

  it('não altera img.src se já é a imagem anônima (evita loop)', () => {
    const img = document.createElement('img');
    img.src = 'http://localhost/usuario_anonimo.jpg?v=2';
    const srcAntes = img.src;
    fotoErrorFallback({ target: img } as unknown as Event);
    expect(img.src).toBe(srcAntes);
  });

  it('FOTO_ANONIMA é o caminho estático do asset', () => {
    expect(FOTO_ANONIMA).toBe('assets/imgs/usuario_anonimo.jpg');
  });
});

// ── Whitelist de upload (espelho do backend — AdminCrudService.EXTENSOES_IMAGEM) ──
// O backend grava a foto num diretório servido publicamente e só aceita 5 formatos; um `accept`
// mais largo aqui só produziria um 400 depois do upload.

function arquivo(nome: string, tipo = ''): File {
  return new File(['x'], nome, { type: tipo });
}

describe('fotoFormatoAceito', () => {
  it('aceita as 5 extensões da whitelist, com qualquer caixa', () => {
    for (const nome of ['a.jpg', 'a.JPEG', 'a.png', 'a.Gif', 'a.WEBP']) {
      expect(fotoFormatoAceito(arquivo(nome))).toBe(true);
    }
  });

  it('extensão fora da whitelist cai no content-type (mesma ordem do backend)', () => {
    expect(fotoFormatoAceito(arquivo('foto.html', 'image/png'))).toBe(true);
    expect(fotoFormatoAceito(arquivo('sem-extensao', 'image/jpeg'))).toBe(true);
  });

  it('recusa quando nem a extensão nem o content-type mapeiam (o backend responderia 400)', () => {
    expect(fotoFormatoAceito(arquivo('foto.svg', 'text/html'))).toBe(false);
    expect(fotoFormatoAceito(arquivo('foto.bmp', 'image/bmp'))).toBe(false);
    expect(fotoFormatoAceito(arquivo('foto.heic', 'image/heic'))).toBe(false);
    expect(fotoFormatoAceito(arquivo('qualquer', ''))).toBe(false);
  });
});

describe('FOTO_ACCEPT', () => {
  it('oferece no seletor exatamente os formatos que o backend grava', () => {
    expect(FOTO_ACCEPT).toBe('.jpg,.jpeg,.png,.gif,.webp,image/jpeg,image/png,image/gif,image/webp');
    expect(FOTO_ACCEPT).not.toContain('image/*');
  });
});

describe('aceitarFoto', () => {
  /** FileList sintético: o ambiente de teste não tem DataTransfer (só o navegador o oferece). */
  function inputCom(files: File[]): HTMLInputElement {
    const input = document.createElement('input');
    input.type = 'file';
    const fileList = {
      ...files,
      length: files.length,
      item: (i: number) => files[i] ?? null,
    } as unknown as FileList;
    Object.defineProperty(input, 'files', { value: fileList, writable: true, configurable: true });
    return input;
  }

  it('arquivo válido é devolvido e a mensagem de erro não é tocada', () => {
    const erro = signal('');
    const input = inputCom([arquivo('avatar.png', 'image/png')]);

    expect(aceitarFoto(input, erro)?.name).toBe('avatar.png');
    expect(erro()).toBe('');
  });

  it('formato recusado → null, mensagem de erro e input limpo (não fica o nome do arquivo no campo)', () => {
    const erro = signal('');
    const input = inputCom([arquivo('curriculo.pdf', 'application/pdf')]);

    expect(aceitarFoto(input, erro)).toBeNull();
    expect(erro()).toBe(FOTO_FORMATO_INVALIDO);
    expect(input.value).toBe('');
  });

  it('nenhum arquivo escolhido → null, sem erro', () => {
    const erro = signal('');

    expect(aceitarFoto(inputCom([]), erro)).toBeNull();
    expect(erro()).toBe('');
  });
});
