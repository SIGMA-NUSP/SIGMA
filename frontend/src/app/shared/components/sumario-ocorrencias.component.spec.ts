import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { SumarioOcorrenciasComponent, TRADUCAO_OCORRENCIA } from './sumario-ocorrencias.component';

/**
 * SumarioOcorrenciasComponent (card admin "Sumário"): filtro de competências, consulta ao
 * endpoint e a matriz funcionários × "Ocorrências Secullum".
 *
 * A tela não decide nada sobre os dados — quais colunas existem, em que ordem e quantos dias
 * cada célula tem vem TODO do backend (é lá que mora a precedência definitiva → prévia →
 * última semanal, e a exclusão do feriado e do ponto facultativo). O que se testa aqui é o
 * filtro (o que é pedido ao servidor), a fidelidade da renderização e o tooltip que traduz o
 * código impresso na folha.
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
      expect(heads.map(h => h.textContent!.trim())).toEqual(['FERNC', 'ZZNOVO']);
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

    it('o rodapé repete o total de cada coluna, como o backend o calculou', () => {
      const fixture = renderizar();

      expect(textos(fixture, 'tfoot td.col-oc')).toEqual(['5', '2']);
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

      expect(cabecalhos(fixture).map(h => h.textContent!.trim())).toEqual(['Falta']);
    });
  });
});
