import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { SumarioOcorrenciasComponent, TRADUCAO_OCORRENCIA } from './sumario-ocorrencias.component';

/**
 * SumarioOcorrenciasComponent (card admin "Sumário"): filtro de competências, consulta ao
 * endpoint e a matriz funcionários × "Ocorrências Secullum".
 *
 * A tela não decide nada sobre os DADOS — quais colunas existem e quantos dias cada célula tem
 * vem do backend (é lá que mora a precedência definitiva → prévia → última semanal, e a exclusão
 * das ocorrências coletivas). O recorte é local: filtro de valores na coluna Funcionário,
 * classificação em todas e o rodapé somado das linhas exibidas. O que se testa aqui é o filtro de
 * competências (o que é pedido ao servidor), a fidelidade da renderização, o tooltip que traduz o
 * código impresso na folha e esse recorte local.
 *
 * Relógio congelado ANTES de `createComponent`: o período default (o ano corrente inteiro) e a
 * lista de anos são lidos no field initializer.
 */

/** Resposta representativa: uma ocorrência do catálogo oficial e uma que ele não conhece. */
function payloadSumario() {
  return {
    de: '2026-01',
    ate: '2026-12',
    ocorrencias: [{ codigo: 'FERNC', total: 5 }, { codigo: 'ZZNOVO', total: 2 }],
    funcionarios: [
      { pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', nome: 'Ana Lima', contagens: { FERNC: 3 } },
      { pessoa_id: 'tec-1', pessoa_tipo: 'TECNICO', nome: 'Bruno Dias', contagens: { FERNC: 2, ZZNOVO: 2 } },
    ],
  };
}

const ROTA = '/api/admin/ponto/ocorrencias/sumario';

describe('SumarioOcorrenciasComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    apiGet = vi.fn().mockReturnValue(of({ ok: true, data: payloadSumario() }));

    await TestBed.configureTestingModule({
      imports: [SumarioOcorrenciasComponent],
      providers: [{ provide: ApiService, useValue: { get: apiGet } }],
    }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function renderizar(hoje = '2026-07-12T10:00:00-03:00'): ComponentFixture<SumarioOcorrenciasComponent> {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    const fixture = TestBed.createComponent(SumarioOcorrenciasComponent);
    fixture.detectChanges();
    return fixture;
  }

  const selects = (f: ComponentFixture<SumarioOcorrenciasComponent>) =>
    f.debugElement.queryAll(By.css('select')).map(d => d.nativeElement as HTMLSelectElement);

  function escolher(select: HTMLSelectElement, valor: string): void {
    if (select.value === valor) return;
    select.value = valor;
    select.dispatchEvent(new Event('change'));
  }

  const cabecalhos = (f: ComponentFixture<SumarioOcorrenciasComponent>) =>
    f.debugElement.queryAll(By.css('thead th.col-oc')).map(d => d.nativeElement as HTMLElement);

  /** Título da coluna sem o ícone (▽/▼) que o gatilho do filtro acrescenta ao texto. */
  const titulos = (f: ComponentFixture<SumarioOcorrenciasComponent>) =>
    cabecalhos(f).map(h => h.textContent!.replace(/[▽▼]/g, '').trim());

  const linhas = (f: ComponentFixture<SumarioOcorrenciasComponent>) =>
    f.debugElement.queryAll(By.css('tbody tr'));

  const textos = (f: ComponentFixture<SumarioOcorrenciasComponent>, seletor: string) =>
    f.debugElement.queryAll(By.css(seletor)).map(d => (d.nativeElement as HTMLElement).textContent!.trim());

  describe('filtro de competências', () => {
    it('abre no ano corrente inteiro', () => {
      const fixture = renderizar();

      expect(apiGet).toHaveBeenCalledWith(ROTA, { de: '2026-01', ate: '2026-12' });
      expect(fixture.componentInstance.deMes()).toBe(1);
      expect(fixture.componentInstance.ateMes()).toBe(12);
    });

    it('trocar o mês final consulta o novo período', () => {
      const fixture = renderizar();
      apiGet.mockClear();

      escolher(selects(fixture)[2], '3');   // De: mês, ano · Até: mês, ano
      fixture.detectChanges();

      expect(apiGet).toHaveBeenCalledWith(ROTA, { de: '2026-01', ate: '2026-03' });
    });

    it('"Ano inteiro" volta a janeiro–dezembro do ano inicial', () => {
      const fixture = renderizar();
      escolher(selects(fixture)[0], '5');
      escolher(selects(fixture)[2], '6');
      fixture.detectChanges();
      apiGet.mockClear();

      fixture.debugElement.query(By.css('button.btn-ano')).nativeElement.click();
      fixture.detectChanges();

      expect(apiGet).toHaveBeenCalledWith(ROTA, { de: '2026-01', ate: '2026-12' });
    });

    /**
     * O intervalo invertido é um estado transitório do próprio filtro (quem quer jun→ago passa
     * por ago→ago): pedi-lo ao servidor só traria uma recusa que o admin não pediu.
     */
    it('intervalo invertido não vai ao servidor e explica o motivo na tela', () => {
      const fixture = renderizar();
      apiGet.mockClear();

      escolher(selects(fixture)[0], '9');   // De: setembro; Até segue em dezembro… trocamos o final
      fixture.detectChanges();
      apiGet.mockClear();
      escolher(selects(fixture)[2], '3');
      fixture.detectChanges();

      expect(apiGet).not.toHaveBeenCalled();
      expect(fixture.debugElement.query(By.css('.error-box')).nativeElement.textContent)
        .toContain('O mês final não pode ser anterior ao inicial.');
      expect(fixture.debugElement.query(By.css('table.sumario'))).toBeNull();
    });
  });

  describe('matriz de ocorrências', () => {
    it('mostra o código da folha e traduz no tooltip; código desconhecido fica sem tooltip', () => {
      const fixture = renderizar();

      const heads = cabecalhos(fixture);
      expect(titulos(fixture)).toEqual(['FERNC', 'ZZNOVO']);
      expect(heads[0].getAttribute('title')).toBe(TRADUCAO_OCORRENCIA['FERNC']);
      // Sem tradução, o atributo nem existe — o cursor de ajuda não promete uma dica que não vem.
      expect(heads[1].getAttribute('title')).toBeNull();
      expect(heads[0].classList.contains('traduzida')).toBe(true);
      expect(heads[1].classList.contains('traduzida')).toBe(false);
    });

    it('cada linha é um funcionário; célula sem contagem fica vazia', () => {
      const fixture = renderizar();

      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Ana Lima', 'Bruno Dias']);
      const celulasDaAna = linhas(fixture)[0].queryAll(By.css('td.col-oc'))
        .map(d => (d.nativeElement as HTMLElement).textContent!.trim());
      expect(celulasDaAna).toEqual(['3', '']);   // Ana não tem ZZNOVO
    });

    it('o rodapé soma cada coluna das linhas exibidas', () => {
      const fixture = renderizar();

      expect(textos(fixture, 'tfoot td.col-oc')).toEqual(['5', '2']);
    });

    it('sem paginação: todas as linhas do período ficam na tela de uma vez', () => {
      const muitos = Array.from({ length: 12 }, (_, i) => ({
        pessoa_id: `op-${i}`, pessoa_tipo: 'OPERADOR', nome: `Func ${String(i).padStart(2, '0')}`,
        contagens: { FERNC: 1 },
      }));
      apiGet.mockReturnValue(of({ ok: true, data: {
        de: '2026-01', ate: '2026-12', ocorrencias: [{ codigo: 'FERNC', total: 12 }], funcionarios: muitos,
      } }));

      const fixture = renderizar();

      expect(linhas(fixture)).toHaveLength(12);
    });
  });

  describe('recorte local: filtro e classificação', () => {
    it('cada coluna tem o gatilho de filtro no cabeçalho', () => {
      const fixture = renderizar();

      // Funcionário + uma por ocorrência
      expect(fixture.debugElement.queryAll(By.css('thead th app-column-filter'))).toHaveLength(3);
    });

    it('filtrar a coluna Funcionário recorta as linhas e o rodapé acompanha', () => {
      const fixture = renderizar();

      fixture.componentInstance.onFiltroNomes({ key: 'nome', state: { values: ['Ana Lima'] } });
      fixture.detectChanges();

      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Ana Lima']);
      expect(textos(fixture, 'tfoot td.col-oc')).toEqual(['3', '0']);   // só os dias da Ana
    });

    it('filtro sem nenhum resultado diz isso na tabela, sem derrubá-la', () => {
      const fixture = renderizar();

      fixture.componentInstance.onFiltroNomes({ key: 'nome', state: { values: ['Ninguém'] } });
      fixture.detectChanges();

      // A tabela fica de pé: é nela que o admin reabre o painel e limpa o filtro
      expect(fixture.debugElement.query(By.css('table.sumario'))).not.toBeNull();
      expect(fixture.debugElement.query(By.css('tbody .empty-state')).nativeElement.textContent)
        .toContain('Nenhum funcionário corresponde ao filtro.');
    });

    it('limpar o filtro (state null) devolve todas as linhas', () => {
      const fixture = renderizar();
      fixture.componentInstance.onFiltroNomes({ key: 'nome', state: { values: ['Ana Lima'] } });
      fixture.detectChanges();

      fixture.componentInstance.onFiltroNomes({ key: 'nome', state: null });
      fixture.detectChanges();

      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Ana Lima', 'Bruno Dias']);
    });

    it('classificar por uma coluna de ocorrência reordena pela contagem', () => {
      const fixture = renderizar();

      fixture.componentInstance.onSort({ sort: 'oc:FERNC', direction: 'asc' });
      fixture.detectChanges();
      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Bruno Dias', 'Ana Lima']);   // 2 < 3

      fixture.componentInstance.onSort({ sort: 'oc:FERNC', direction: 'desc' });
      fixture.detectChanges();
      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Ana Lima', 'Bruno Dias']);
    });

    it('classificar por Funcionário ordena pelo nome', () => {
      const fixture = renderizar();

      fixture.componentInstance.onSort({ sort: 'nome', direction: 'desc' });
      fixture.detectChanges();

      expect(textos(fixture, 'tbody td.col-nome')).toEqual(['Bruno Dias', 'Ana Lima']);
    });
  });

  describe('estados da tela', () => {
    it('sem folha publicada no período — diz isso, não uma tabela vazia', () => {
      apiGet.mockReturnValue(of({ ok: true, data: { de: '2026-01', ate: '2026-12', ocorrencias: [], funcionarios: [] } }));

      const fixture = renderizar();

      expect(fixture.debugElement.query(By.css('.empty-state')).nativeElement.textContent)
        .toContain('Nenhuma folha publicada no período');
    });

    it('folhas sem nenhum status — distingue "ninguém teve ocorrência" de "não há folha"', () => {
      apiGet.mockReturnValue(of({
        ok: true,
        data: {
          de: '2026-01', ate: '2026-12', ocorrencias: [],
          funcionarios: [{ pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', nome: 'Ana Lima', contagens: {} }],
        },
      }));

      const fixture = renderizar();

      expect(fixture.debugElement.query(By.css('.empty-state')).nativeElement.textContent)
        .toContain('Nenhuma ocorrência registrada');
    });

    it('falha da consulta vira caixa de erro com a frase do backend', () => {
      apiGet.mockReturnValue(throwError(() => ({ error: { error: 'Competência inválida em de (use AAAA-MM).' } })));

      const fixture = renderizar();

      expect(fixture.debugElement.query(By.css('.error-box')).nativeElement.textContent)
        .toContain('Competência inválida em de (use AAAA-MM).');
    });

    /**
     * O intervalo invertido não consulta o servidor — mas a consulta ANTERIOR pode estar em voo.
     * Se ela ainda valesse, sua resposta (ou sua falha) escreveria por cima da caixa que explica
     * o filtro, e o admin leria um erro de servidor que não existe.
     */
    it('consulta em voo é abortada e não apaga o aviso do intervalo invertido', () => {
      const fixture = renderizar();
      const pendente = new Subject<any>();
      apiGet.mockReturnValue(pendente);

      escolher(selects(fixture)[0], '9');   // De: setembro — consulta em voo
      fixture.detectChanges();
      expect(pendente.observed).toBe(true);

      escolher(selects(fixture)[2], '3');   // Até: março — intervalo invertido
      fixture.detectChanges();

      expect(pendente.observed).toBe(false);   // a consulta anterior foi cancelada
      const erro = () => fixture.debugElement.query(By.css('.error-box')).nativeElement.textContent;
      expect(erro()).toContain('O mês final não pode ser anterior ao inicial.');

      pendente.next({ ok: true, data: payloadSumario() });   // chegaria agora, se ainda valesse
      fixture.detectChanges();

      expect(erro()).toContain('O mês final não pode ser anterior ao inicial.');
      expect(fixture.debugElement.query(By.css('table.sumario'))).toBeNull();
    });

    /** Trocar de período rápido põe duas consultas em voo; vale a do período que está na tela. */
    it('resposta de uma consulta já superada não entra na tela', () => {
      const primeira = new Subject<any>();
      apiGet.mockReturnValueOnce(primeira);
      const fixture = renderizar();
      expect(fixture.componentInstance.carregando()).toBe(true);

      apiGet.mockReturnValue(of({
        ok: true,
        data: {
          de: '2026-01', ate: '2026-03', ocorrencias: [{ codigo: 'Falta', total: 1 }],
          funcionarios: [{ pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', nome: 'Ana Lima', contagens: { Falta: 1 } }],
        },
      }));
      escolher(selects(fixture)[2], '3');
      fixture.detectChanges();

      primeira.next({ ok: true, data: payloadSumario() });   // a antiga chega depois
      primeira.complete();
      fixture.detectChanges();

      expect(titulos(fixture)).toEqual(['Falta']);
    });
  });
});
