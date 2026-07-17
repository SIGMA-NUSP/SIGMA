import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { FolhasPontoListaComponent, MinhaFolha } from './folhas-ponto-lista.component';

/**
 * FolhasPontoListaComponent (shared — lista reutilizável das folhas de ponto de uma pessoa;
 * usada em /ponto e embutida em /admin/ponto). O componente NÃO carrega as folhas da API —
 * recebe-as por `input<MinhaFolha[]>()` (quem busca é o pai, `ponto-banco`); o `ApiService`
 * aqui só serve aos downloads (`getBlob` + `abrirBlobInline`/`baixarBlob`).
 * TestBed sem `detectChanges()`; lógica exercitada por chamada direta; `ApiService`/`Router`
 * mockados via `useValue`; o input é semeado com `componentRef.setInput` — o `ClientPager` é
 * construído sobre o Signal do input e reage sem render. `pager` é `protected` → lido via
 * `(comp as any)`; o ClientPager em si tem spec próprio (`core/helpers/client-pager.spec.ts`),
 * aqui trava-se só a INTEGRAÇÃO. O SUT não lê `Date` — o relógio congelado é profilático.
 */
describe('FolhasPontoListaComponent', () => {
  let apiGetBlob: ReturnType<typeof vi.fn>;
  let abrirBlobInline: ReturnType<typeof vi.fn>;
  let baixarBlob: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.fn>;
  let alertSpy: ReturnType<typeof vi.spyOn>;

  const BLOB = new Blob(['%PDF-1.4'], { type: 'application/pdf' });

  const FOLHA: MinhaFolha = {
    id: 'f-1',
    tipo: 'MENSAL',
    data_inicio: '2026-06-01',
    data_fim: '2026-06-30',
    publicado_em: '2026-07-02',
  };

  /** N folhas distintas (id/período) para exercitar o recorte do pager. */
  function folhas(n: number): MinhaFolha[] {
    return Array.from({ length: n }, (_, i) => ({
      ...FOLHA,
      id: `f-${i + 1}`,
      data_inicio: `2026-01-${String(i + 1).padStart(2, '0')}`,
    }));
  }

  beforeEach(async () => {
    apiGetBlob = vi.fn().mockReturnValue(of(BLOB));
    abrirBlobInline = vi.fn();
    baixarBlob = vi.fn();
    navigate = vi.fn();

    await TestBed.configureTestingModule({
      imports: [FolhasPontoListaComponent],
      providers: [
        { provide: ApiService, useValue: { getBlob: apiGetBlob, abrirBlobInline, baixarBlob } },
        { provide: Router, useValue: { navigate } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Cria o componente com as folhas já no input (sem detectChanges → sem render). */
  function criar(lista: MinhaFolha[] = []) {
    const fixture = TestBed.createComponent(FolhasPontoListaComponent);
    fixture.componentRef.setInput('folhas', lista);
    const comp = fixture.componentInstance;
    return { comp, pager: (comp as any).pager, fixture };
  }

  // ═══════════════════════════════════════════════════════════════════
  // Integração com o ClientPager — o recorte vem do input (Signal)
  // ═══════════════════════════════════════════════════════════════════
  describe('paginação (ClientPager sobre o input)', () => {
    it('lista vazia: sem linhas e meta zerada', () => {
      const { pager } = criar([]);
      expect(pager.rows()).toEqual([]);
      expect(pager.meta()).toEqual({ page: 1, limit: 10, total: 0, pages: 1 });
    });

    it('recorta a 1ª página (10 por página) e conta o total do input', () => {
      const { pager } = criar(folhas(12));
      expect(pager.rows().map((f: MinhaFolha) => f.id)).toEqual(
        ['f-1', 'f-2', 'f-3', 'f-4', 'f-5', 'f-6', 'f-7', 'f-8', 'f-9', 'f-10'],
      );
      expect(pager.meta()).toEqual({ page: 1, limit: 10, total: 12, pages: 2 });
    });

    it('última página parcial traz só o resto', () => {
      const { pager } = criar(folhas(12));
      pager.onPage(2);
      expect(pager.rows().map((f: MinhaFolha) => f.id)).toEqual(['f-11', 'f-12']);
      expect(pager.meta().page).toBe(2);
    });

    it('o recorte reage à troca do input (Signal), sem render', () => {
      const { pager, fixture } = criar(folhas(3));
      expect(pager.meta().total).toBe(3);
      fixture.componentRef.setInput('folhas', folhas(25));
      expect(pager.meta()).toMatchObject({ total: 25, pages: 3 });
      expect(pager.rows()).toHaveLength(10);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // ver() — abre o PDF da folha inline (nova aba)
  // ═══════════════════════════════════════════════════════════════════
  describe('ver', () => {
    it('baixa o blob da folha e abre inline', () => {
      const { comp } = criar([FOLHA]);
      comp.ver(FOLHA);
      expect(apiGetBlob).toHaveBeenCalledWith('/api/ponto/folha/f-1/download');
      expect(abrirBlobInline).toHaveBeenCalledWith(BLOB);
      expect(alertSpy).not.toHaveBeenCalled();
    });

    it('erro: alerta e não abre nada', () => {
      const { comp } = criar([FOLHA]);
      apiGetBlob.mockReturnValue(throwError(() => new Error('rede')));
      comp.ver(FOLHA);
      expect(alertSpy).toHaveBeenCalledWith('Não foi possível abrir a folha de ponto.');
      expect(abrirBlobInline).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // baixar() — mesmo endpoint, salva como arquivo com nome derivado da folha
  // ═══════════════════════════════════════════════════════════════════
  describe('baixar', () => {
    it('salva o blob com o nome ponto-{tipo}-{inicio}_a_{fim}.pdf', () => {
      const { comp } = criar([FOLHA]);
      comp.baixar(FOLHA);
      expect(apiGetBlob).toHaveBeenCalledWith('/api/ponto/folha/f-1/download');
      expect(baixarBlob).toHaveBeenCalledWith(BLOB, 'ponto-mensal-2026-06-01_a_2026-06-30.pdf');
    });

    it('o tipo entra em minúsculas no nome (SEMANAL → semanal)', () => {
      const { comp } = criar([]);
      comp.baixar({ ...FOLHA, id: 'f-9', tipo: 'SEMANAL', data_inicio: '2026-06-08', data_fim: '2026-06-14' });
      expect(baixarBlob).toHaveBeenCalledWith(BLOB, 'ponto-semanal-2026-06-08_a_2026-06-14.pdf');
    });

    it('erro: alerta e não salva arquivo', () => {
      const { comp } = criar([FOLHA]);
      apiGetBlob.mockReturnValue(throwError(() => ({ status: 404 })));
      comp.baixar(FOLHA);
      expect(alertSpy).toHaveBeenCalledWith('Não foi possível baixar a folha de ponto.');
      expect(baixarBlob).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // retificar() — navega para a tela de retificação daquela folha
  // ═══════════════════════════════════════════════════════════════════
  describe('retificar', () => {
    it('navega para /ponto/retificar/{id}', () => {
      const { comp } = criar([FOLHA]);
      comp.retificar(FOLHA);
      expect(navigate).toHaveBeenCalledWith(['/ponto/retificar', 'f-1']);
      expect(apiGetBlob).not.toHaveBeenCalled();
    });
  });
});
