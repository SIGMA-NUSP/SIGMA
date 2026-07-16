import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ApiService } from './api.service';

/** Card de dashboard cadastrado em INT_METABASE_DASHBOARD. */
export interface DashboardCard {
  id: string;
  titulo: string;
  descricao: string | null;
  icone: string | null;
  ordem: number;
}

/**
 * Acesso aos dashboards Metabase (static embedding) — fonte única para as telas
 * /admin (dashboard) e /admin/analise, que antes duplicavam HttpClient direto,
 * a extração de mensagem de erro e o `bypassSecurityTrustResourceUrl`.
 * A autenticação vai pelo header `Authorization: Bearer` do auth.interceptor
 * (via ApiService), como nos demais endpoints /api/admin.
 */
@Injectable({ providedIn: 'root' })
export class MetabaseService {
  private api = inject(ApiService);
  private sanitizer = inject(DomSanitizer);

  /** Lista os dashboards de indicadores cadastrados. */
  listarDashboards(): Observable<DashboardCard[]> {
    return this.api.get<DashboardCard[]>('/api/admin/metabase/dashboards');
  }

  /** URL de embed já sanitizada para uso direto em `[src]` de um iframe. */
  embedUrl(id: string): Observable<SafeResourceUrl> {
    return this.api
      .get<{ url: string }>(`/api/admin/metabase/dashboards/${id}/embed-url`)
      .pipe(map(r => this.sanitizer.bypassSecurityTrustResourceUrl(r.url)));
  }
}
