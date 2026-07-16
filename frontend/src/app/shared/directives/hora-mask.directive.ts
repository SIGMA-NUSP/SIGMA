import { Directive, HostListener, output } from '@angular/core';

/** Valida hora no formato HH:MM (00:00–23:59). */
export const HORA_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/**
 * Máscara HH:MM para inputs de texto: aceita só dígitos (máx. 4) e insere ':'
 * automaticamente após o 2º — exceto ao apagar (inputType `delete*`), para o
 * backspace não "travar" no ':'. Emite o valor mascarado em `horaChange` para
 * o host atualizar seu model (o input é atualizado direto pela diretiva).
 *
 * Uso: <input appHoraMask [value]="hora" (horaChange)="hora = $event"
 *             inputmode="numeric" maxlength="5" placeholder="HH:MM">
 */
@Directive({
  selector: 'input[appHoraMask]',
  standalone: true,
})
export class HoraMaskDirective {
  readonly horaChange = output<string>();

  @HostListener('input', ['$event'])
  onInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const apagando = (event as InputEvent).inputType?.startsWith('delete') ?? false;
    const dig = input.value.replace(/\D/g, '').slice(0, 4);
    let masked: string;
    if (dig.length > 2) masked = dig.slice(0, 2) + ':' + dig.slice(2);
    else if (dig.length === 2 && !apagando) masked = dig + ':';
    else masked = dig;
    input.value = masked;
    this.horaChange.emit(masked);
  }
}
