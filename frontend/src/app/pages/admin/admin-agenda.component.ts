import { Component } from '@angular/core';
import { AgendaLegislativaBaseComponent } from '../../shared/components/agenda-legislativa-base.component';

@Component({
  selector: 'app-admin-agenda',
  standalone: true,
  imports: [AgendaLegislativaBaseComponent],
  template: `<app-agenda-legislativa-base
    voltarRoute="/admin"
    voltarLabel="← Voltar ao Painel"
    [voltarComoBotao]="false"
    [exibirOperadores]="true" />`,
})
export class AdminAgendaComponent {}
