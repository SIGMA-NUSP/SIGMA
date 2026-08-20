import { Injectable, WritableSignal, inject, signal } from '@angular/core';
import { ApiService } from './api.service';

export interface LookupItem { id: number | string; nome: string; nome_completo?: string; multi_operador?: boolean; participa_escala?: boolean; turno?: string; }

@Injectable({ providedIn: 'root' })
export class LookupService {
  private api = inject(ApiService);

  salas = signal<LookupItem[]>([]);
  operadores = signal<LookupItem[]>([]);
  comissoes = signal<LookupItem[]>([]);

  /** Mensagem de erro por recurso — vazia enquanto a última carga daquele recurso tiver sucedido. */
  erroSalas = signal('');
  erroOperadores = signal('');
  erroComissoes = signal('');
  erroOperadoresPlenario = signal('');

  loadSalas(): void {
    this.carregar('/api/forms/lookup/salas', this.salas, this.erroSalas);
  }

  loadOperadores(): void {
    this.carregar('/api/forms/lookup/operadores', this.operadores, this.erroOperadores);
  }

  loadComissoes(): void {
    this.carregar('/api/forms/lookup/comissoes', this.comissoes, this.erroComissoes);
  }

  /** Salas filtradas por permissão do operador (autenticado) */
  loadSalasOperador(): void {
    this.carregar('/api/operacao/lookup/salas', this.salas, this.erroSalas);
  }

  /** Operadores com flag plenário principal */
  operadoresPlenario = signal<LookupItem[]>([]);

  loadOperadoresPlenario(): void {
    this.carregar('/api/operacao/lookup/operadores-plenario', this.operadoresPlenario, this.erroOperadoresPlenario);
  }

  loadAll(): void {
    this.loadSalas();
    this.loadOperadores();
    this.loadComissoes();
  }

  /**
   * Falha de carga não pode virar select vazio mudo: o dado fica como está (um cache
   * anterior segue servindo) e a mensagem do recurso é registrada para a tela exibir
   * com "Tentar novamente" — tentar de novo é rechamar o load que a tela usou.
   */
  private carregar(url: string, dados: WritableSignal<LookupItem[]>, erro: WritableSignal<string>): void {
    erro.set('');
    this.api.get<any>(url).subscribe({
      next: res => dados.set(res.data || []),
      error: () => erro.set('Não foi possível carregar a lista.'),
    });
  }
}
