import { PaginationComponent } from './pagination.component';
import { PaginationMeta } from '../../core/models/user.model';

/**
 * PaginationComponent: instanciável direto, sem TestBed. Cobre o clamp de `go` em
 * [1, meta.pages], a guarda "só emite se p !== meta.page", a coerção string→número
 * de `onLimitChange` e a sincronização de `ngOnChanges`.
 */
describe('PaginationComponent', () => {
  function meta(page: number, pages: number): PaginationMeta {
    return { page, limit: 10, total: pages * 10, pages };
  }

  function setup(page: number, pages: number) {
    const c = new PaginationComponent();
    c.meta = meta(page, pages);
    const pageEmit = vi.spyOn(c.pageChange, 'emit');
    const limitEmit = vi.spyOn(c.limitChange, 'emit');
    return { c, pageEmit, limitEmit };
  }

  describe('go', () => {
    it('clampa acima de meta.pages', () => {
      const { c, pageEmit } = setup(2, 5);
      c.go(10);
      expect(pageEmit).toHaveBeenCalledWith(5);
    });

    it('clampa abaixo de 1', () => {
      const { c, pageEmit } = setup(3, 5);
      c.go(-4);
      expect(pageEmit).toHaveBeenCalledWith(1);
      c.go(0);
      expect(pageEmit).toHaveBeenLastCalledWith(1);
    });

    it('emite a página pedida quando dentro do intervalo e diferente da atual', () => {
      const { c, pageEmit } = setup(3, 5);
      c.go(2);
      expect(pageEmit).toHaveBeenCalledWith(2);
    });

    it('NÃO emite quando o alvo já é a página atual', () => {
      const { c, pageEmit } = setup(3, 5);
      c.go(3);
      expect(pageEmit).not.toHaveBeenCalled();
    });

    it('NÃO emite quando o clamp cai na página atual (próximo estando na última)', () => {
      const { c, pageEmit } = setup(5, 5);
      c.go(6); // clampa para 5 === page
      expect(pageEmit).not.toHaveBeenCalled();
    });

    it('NÃO emite quando o clamp cai na página atual (anterior estando na primeira)', () => {
      const { c, pageEmit } = setup(1, 5);
      c.go(0); // clampa para 1 === page
      expect(pageEmit).not.toHaveBeenCalled();
    });
  });

  describe('onLimitChange', () => {
    it('coage a string do <select> para número', () => {
      const { c, limitEmit } = setup(1, 5);
      c.onLimitChange('50');
      expect(limitEmit).toHaveBeenCalledWith(50);
      expect(typeof limitEmit.mock.calls[0][0]).toBe('number');
    });

    it('repassa número inalterado', () => {
      const { c, limitEmit } = setup(1, 5);
      c.onLimitChange(30);
      expect(limitEmit).toHaveBeenCalledWith(30);
    });
  });

  describe('ngOnChanges', () => {
    it('sincroniza pageInput com meta.page', () => {
      const { c } = setup(4, 9);
      expect(c.pageInput).toBe(1); // default antes do ngOnChanges
      c.ngOnChanges();
      expect(c.pageInput).toBe(4);
    });

    it('não quebra quando meta ainda não chegou', () => {
      const c = new PaginationComponent();
      // meta undefined (input required ainda não bindado)
      expect(() => c.ngOnChanges()).not.toThrow();
      expect(c.pageInput).toBe(1);
    });
  });
});
