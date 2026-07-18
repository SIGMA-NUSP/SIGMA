import { Injectable, signal } from '@angular/core';

/** Flags conhecidas — controladas em runtime por `/config.json` (ver docker/frontend-entrypoint.sh). */
export type FeatureFlag = 'pontoBanco' | 'inserirAvisos' | 'ajudaIa';

/**
 * Flags de funcionalidade carregadas em runtime de `/config.json` (gerado pelo container
 * a partir das variáveis de ambiente FEATURE_*). Fonte única para cards e guards decidirem
 * se uma feature está ligada. **Fail-safe:** ausência da chave (ou config não carregado) =
 * desligado — nenhuma feature liga por acidente.
 */
@Injectable({ providedIn: 'root' })
export class FeatureFlagService {
  private readonly flags = signal<Record<string, boolean>>({});

  setFlags(flags: Record<string, boolean> | null | undefined): void {
    this.flags.set(flags ?? {});
  }

  /** true somente se a flag existir e for exatamente `true`. */
  isEnabled(flag: FeatureFlag): boolean {
    return this.flags()[flag] === true;
  }
}
