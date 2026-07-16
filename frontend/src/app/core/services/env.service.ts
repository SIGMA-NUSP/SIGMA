import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Title } from '@angular/platform-browser';
import { environment } from '../../../environments/environment';

interface HealthResponse {
  ok: boolean;
  envLabel?: string;
}

/**
 * Lê o rótulo do ambiente (ex: "homolog") do backend via /api/health.
 * Em produção, a env var APP_ENV_LABEL não é definida e o rótulo fica vazio,
 * o que faz o banner e o prefixo do título da aba desaparecerem.
 */
@Injectable({ providedIn: 'root' })
export class EnvService {
  private http = inject(HttpClient);
  private titleSvc = inject(Title);

  private _label = signal<string>('');
  label = this._label.asReadonly();

  load(): void {
    this.http.get<HealthResponse>(`${environment.apiBaseUrl}/api/health`).subscribe({
      next: res => {
        const label = (res.envLabel ?? '').trim();
        this._label.set(label);
        if (label) this.applyTitlePrefix(label);
      },
      error: () => this._label.set(''),
    });
  }

  // Reaplica o prefixo no título já renderizado, caso a TitleStrategy tenha
  // rodado antes da resposta do /api/health chegar.
  private applyTitlePrefix(label: string): void {
    const prefix = `[${label.toUpperCase()}] `;
    const current = this.titleSvc.getTitle();
    if (!current.startsWith(prefix)) {
      this.titleSvc.setTitle(prefix + current);
    }
  }
}
