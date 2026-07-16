import { Injectable, inject } from '@angular/core';
import { TitleStrategy, RouterStateSnapshot } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { EnvService } from './services/env.service';

/**
 * Prefixa o título da aba com [LABEL] quando o backend reporta um rótulo de
 * ambiente (ex: "[HOMOLOG] Home | Senado NUSP"). Em produção o rótulo vem
 * vazio e o título original passa intacto.
 */
@Injectable({ providedIn: 'root' })
export class PrefixedTitleStrategy extends TitleStrategy {
  private title = inject(Title);
  private env = inject(EnvService);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const routeTitle = this.buildTitle(snapshot) ?? 'Senado NUSP';
    const label = this.env.label();
    this.title.setTitle(label ? `[${label.toUpperCase()}] ${routeTitle}` : routeTitle);
  }
}
