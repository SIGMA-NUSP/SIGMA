import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';

export interface LookupItem { id: number | string; nome: string; nome_completo?: string; multi_operador?: boolean; participa_escala?: boolean; turno?: string; }

@Injectable({ providedIn: 'root' })
export class LookupService {
  private api = inject(ApiService);

  salas = signal<LookupItem[]>([]);
  operadores = signal<LookupItem[]>([]);
  comissoes = signal<LookupItem[]>([]);

  loadSalas(): void {
    this.api.get<any>('/api/forms/lookup/salas').subscribe(res => {
      this.salas.set(res.data || []);
    });
  }

  loadOperadores(): void {
    this.api.get<any>('/api/forms/lookup/operadores').subscribe(res => {
      this.operadores.set(res.data || []);
    });
  }

  loadComissoes(): void {
    this.api.get<any>('/api/forms/lookup/comissoes').subscribe(res => {
      this.comissoes.set(res.data || []);
    });
  }

  /** Salas filtradas por permissão do operador (autenticado) */
  loadSalasOperador(): void {
    this.api.get<any>('/api/operacao/lookup/salas').subscribe(res => {
      this.salas.set(res.data || []);
    });
  }

  /** Operadores com flag plenário principal */
  operadoresPlenario = signal<LookupItem[]>([]);

  loadOperadoresPlenario(): void {
    this.api.get<any>('/api/operacao/lookup/operadores-plenario').subscribe(res => {
      this.operadoresPlenario.set(res.data || []);
    });
  }

  loadAll(): void {
    this.loadSalas();
    this.loadOperadores();
    this.loadComissoes();
  }
}
