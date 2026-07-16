import { TestBed } from '@angular/core/testing';
import { FeatureFlagService } from './feature-flags.service';

/**
 * FeatureFlagService — fonte única das flags de runtime. O foco é o comportamento
 * FAIL-SAFE: sem config carregado, ou valor não exatamente `true`, a feature fica desligada.
 */
describe('FeatureFlagService', () => {
  let svc: FeatureFlagService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    svc = TestBed.inject(FeatureFlagService);
  });

  it('sem flags carregadas — tudo desligado (fail-safe)', () => {
    expect(svc.isEnabled('pontoBanco')).toBe(false);
    expect(svc.isEnabled('inserirAvisos')).toBe(false);
  });

  it('setFlags(null/undefined) — tudo desligado', () => {
    svc.setFlags(null);
    expect(svc.isEnabled('pontoBanco')).toBe(false);
    svc.setFlags(undefined);
    expect(svc.isEnabled('inserirAvisos')).toBe(false);
  });

  it('liga apenas as flags marcadas com exatamente true', () => {
    svc.setFlags({ pontoBanco: true, inserirAvisos: false });
    expect(svc.isEnabled('pontoBanco')).toBe(true);
    expect(svc.isEnabled('inserirAvisos')).toBe(false);
  });

  it('valor não-boolean (ex.: string "true") não liga — fail-safe', () => {
    svc.setFlags({ pontoBanco: 'true' as unknown as boolean });
    expect(svc.isEnabled('pontoBanco')).toBe(false);
  });
});
