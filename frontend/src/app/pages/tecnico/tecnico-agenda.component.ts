import { Component } from '@angular/core';
import { AgendaLegislativaBaseComponent } from '../../shared/components/agenda-legislativa-base.component';

/**
 * Agenda Legislativa para o técnico — reusa a base, com a linha de operadores
 * escalados por sala; "Voltar" leva à home do técnico.
 */
@Component({
  selector: 'app-tecnico-agenda',
  standalone: true,
  imports: [AgendaLegislativaBaseComponent],
  template: `<app-agenda-legislativa-base voltarRoute="/tecnico" voltarLabel="Voltar" [exibirOperadores]="true" />`,
})
export class TecnicoAgendaComponent {}
