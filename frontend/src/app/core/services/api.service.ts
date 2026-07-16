import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PagedResponse, ApiResponse } from '../models/user.model';

export interface ListParams {
  page?: number;
  limit?: number;
  sort?: string;
  direction?: string;
  search?: string;
  filters?: Record<string, unknown>;
  periodo?: { inicio?: string; fim?: string };
}

@Injectable({ providedIn: 'root' })
export class ApiService {

  private http = inject(HttpClient);

  private url(path: string): string {
    return `${environment.apiBaseUrl}${path}`;
  }

  /** GET paginado — usado por todos os dashboards. */
  getList(endpoint: string, params: ListParams = {}): Observable<PagedResponse> {
    let hp = new HttpParams();
    if (params.page) hp = hp.set('page', params.page);
    if (params.limit) hp = hp.set('limit', params.limit);
    if (params.sort) hp = hp.set('sort', params.sort);
    if (params.direction) hp = hp.set('direction', params.direction);
    if (params.search) hp = hp.set('search', params.search);
    if (params.filters && Object.keys(params.filters).length) {
      hp = hp.set('filters', JSON.stringify(params.filters));
    }
    if (params.periodo) hp = hp.set('periodo', JSON.stringify(params.periodo));
    return this.http.get<PagedResponse>(this.url(endpoint), { params: hp });
  }

  /** GET simples (detalhe, lookup, etc). */
  get<T = ApiResponse>(endpoint: string, params?: Record<string, string | number>): Observable<T> {
    let hp = new HttpParams();
    if (params) Object.entries(params).forEach(([k, v]) => hp = hp.set(k, v));
    return this.http.get<T>(this.url(endpoint), { params: hp });
  }

  /** POST JSON. */
  post<T = ApiResponse>(endpoint: string, body: unknown): Observable<T> {
    return this.http.post<T>(this.url(endpoint), body);
  }

  /** PUT JSON. */
  put<T = ApiResponse>(endpoint: string, body: unknown): Observable<T> {
    return this.http.put<T>(this.url(endpoint), body);
  }

  /** PATCH JSON. */
  patch<T = ApiResponse>(endpoint: string, body: unknown): Observable<T> {
    return this.http.patch<T>(this.url(endpoint), body);
  }

  /** DELETE. */
  delete<T = ApiResponse>(endpoint: string): Observable<T> {
    return this.http.delete<T>(this.url(endpoint));
  }

  /** POST multipart (upload de arquivo). */
  postForm<T = ApiResponse>(endpoint: string, formData: FormData): Observable<T> {
    return this.http.post<T>(this.url(endpoint), formData);
  }

  /** GET blob (download de relatório PDF/DOCX/XLSX). */
  getBlob(endpoint: string, params?: Record<string, string>): Observable<Blob> {
    let hp = new HttpParams();
    if (params) Object.entries(params).forEach(([k, v]) => hp = hp.set(k, v));
    return this.http.get(this.url(endpoint), { params: hp, responseType: 'blob' });
  }

  /** Abre um blob em nova aba (revoga a URL após 60s). */
  abrirBlobInline(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  /** Baixa um blob como arquivo (revoga a URL logo após o clique). */
  baixarBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  /** Abre PDF inline (nova aba). */
  openPdfInline(endpoint: string, params?: Record<string, string>): void {
    this.getBlob(endpoint, { ...params, format: 'pdf' }).subscribe(blob => this.abrirBlobInline(blob));
  }

  /** Download de relatório (PDF abre inline, DOCX/XLSX baixa arquivo). */
  downloadReport(endpoint: string, params: Record<string, string>): void {
    const format = params['format'] || 'pdf';
    this.getBlob(endpoint, params).subscribe(blob => {
      if (format === 'pdf') {
        this.abrirBlobInline(blob);
      } else {
        const ext = format === 'docx' ? 'docx' : format;
        this.baixarBlob(blob, `relatorio.${ext}`);
      }
    });
  }
}
