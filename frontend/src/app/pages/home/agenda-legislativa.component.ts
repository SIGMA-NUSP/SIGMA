import { Component } from '@angular/core';
import { AgendaLegislativaBaseComponent } from '../../shared/components/agenda-legislativa-base.component';

@Component({
  selector: 'app-agenda-legislativa',
  standalone: true,
  imports: [AgendaLegislativaBaseComponent],
  template: `<app-agenda-legislativa-base voltarRoute="/home" voltarLabel="Voltar" />`,
})
export class AgendaLegislativaComponent {}
