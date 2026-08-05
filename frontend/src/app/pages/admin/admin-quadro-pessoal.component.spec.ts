import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { AdminQuadroPessoalComponent } from './admin-quadro-pessoal.component';
import { AdminTabelasPessoalComponent } from './admin-tabelas-pessoal.component';

/**
 * A página que passou a hospedar as listagens de pessoal. O que se prova aqui é o
 * enquadramento — título, volta para a Gestão de pessoas e a presença real das
 * listagens; o comportamento das três tabelas tem spec próprio.
 */
describe('AdminQuadroPessoalComponent', () => {
  async function renderizar() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AdminQuadroPessoalComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getList: () => of({ data: [], meta: { page: 1, limit: 10, total: 0, pages: 0 } }), downloadReport: vi.fn() } },
        { provide: AuthService, useValue: { isMaster: signal(false) } },
      ],
    });
    const fixture = TestBed.createComponent(AdminQuadroPessoalComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('exibe as listagens de pessoal', async () => {
    const fixture = await renderizar();

    expect(fixture.debugElement.query(By.directive(AdminTabelasPessoalComponent))).toBeTruthy();
  });

  it('volta para a Gestão de pessoas, de onde o card veio', async () => {
    const fixture = await renderizar();

    const voltar = fixture.debugElement.query(By.css('a.back-link'));
    expect(voltar.attributes['href']).toBe('/admin/gestao-pessoas');
  });

  it('anuncia a página no título', async () => {
    const fixture = await renderizar();

    expect(fixture.debugElement.query(By.css('h1')).nativeElement.textContent.trim()).toBe('Quadro de Pessoal');
  });
});
