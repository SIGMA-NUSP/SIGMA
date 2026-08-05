import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminTabelasPessoalComponent } from './admin-tabelas-pessoal.component';

/**
 * Quadro de Pessoal — a relação de operadores, técnicos e administradores.
 *
 * As listagens saíram da página de Gestão de pessoas, cujo corpo passou a exibir os
 * indicadores por operador; o caminho até cada Perfil continua sendo por elas.
 */
@Component({
  selector: 'app-admin-quadro-pessoal',
  standalone: true,
  imports: [RouterLink, AdminTabelasPessoalComponent],
  template: `
    <h1>Quadro de Pessoal</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <app-admin-tabelas-pessoal />
  `,
})
export class AdminQuadroPessoalComponent {}
