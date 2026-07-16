import { Directive, computed, inject, input } from '@angular/core';
import { FeatureFlag, FeatureFlagService } from '../../core/services/feature-flags.service';

/**
 * Marca um elemento como controlado por feature flag: aplica `.card-disabled` quando a
 * flag está desligada e expõe `enabled()` via exportAs para o template condicionar link
 * e rótulo — sem precisar injetar o FeatureFlagService em cada componente.
 *
 * Uso (card "visível porém inerte" quando desligado):
 *   <a [featureToggle]="'pontoBanco'" #f="featureToggle"
 *      [routerLink]="f.enabled() ? '/ponto' : null" class="card-custom card-stack">
 *     <strong>Ponto e Banco</strong>
 *     <span class="text-muted-sm">{{ f.enabled() ? 'Abrir' : 'Indisponível' }}</span>
 *   </a>
 *
 * A rota correspondente deve ser protegida por `featureFlagGuard` (auth.guard.ts) —
 * a diretiva cuida do visual; o guard fecha o acesso por URL.
 */
@Directive({
  selector: '[featureToggle]',
  standalone: true,
  exportAs: 'featureToggle',
  host: { '[class.card-disabled]': '!enabled()' },
})
export class FeatureToggleDirective {
  private features = inject(FeatureFlagService);

  featureToggle = input.required<FeatureFlag>();

  enabled = computed(() => this.features.isEnabled(this.featureToggle()));
}
