import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { FeatureToggleDirective } from './feature-toggle.directive';
import { FeatureFlagService } from '../../core/services/feature-flags.service';

/**
 * FeatureToggleDirective — testada via componente host com o FeatureFlagService REAL
 * (integração signal → computed → host binding), no padrão dos cards: classe
 * `.card-disabled` + rótulo condicionado por `f.enabled()`.
 */
@Component({
  standalone: true,
  imports: [FeatureToggleDirective],
  template: `
    <a [featureToggle]="'pontoBanco'" #f="featureToggle" class="card-custom">
      <span class="rotulo">{{ f.enabled() ? 'Abrir' : 'Indisponível' }}</span>
    </a>
  `,
})
class HostComponent {}

describe('FeatureToggleDirective', () => {
  let flags: FeatureFlagService;

  const renderizar = () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
  };
  const card = (fixture: ReturnType<typeof renderizar>) =>
    fixture.debugElement.query(By.css('a')).nativeElement as HTMLAnchorElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    flags = TestBed.inject(FeatureFlagService);
  });

  it('flag desligada (default fail-safe) — aplica .card-disabled e rótulo "Indisponível"', () => {
    const fixture = renderizar();
    expect(card(fixture).classList.contains('card-disabled')).toBe(true);
    expect(card(fixture).textContent).toContain('Indisponível');
  });

  it('flag ligada — sem .card-disabled, rótulo "Abrir"', () => {
    flags.setFlags({ pontoBanco: true });
    const fixture = renderizar();
    expect(card(fixture).classList.contains('card-disabled')).toBe(false);
    expect(card(fixture).textContent).toContain('Abrir');
  });

  it('reage à mudança da flag após o render (signal → computed → host binding)', () => {
    const fixture = renderizar();
    expect(card(fixture).classList.contains('card-disabled')).toBe(true);

    flags.setFlags({ pontoBanco: true });
    fixture.detectChanges();
    expect(card(fixture).classList.contains('card-disabled')).toBe(false);
    expect(card(fixture).textContent).toContain('Abrir');
  });
});
