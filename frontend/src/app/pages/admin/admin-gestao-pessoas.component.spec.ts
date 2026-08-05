import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By, DomSanitizer } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { FeatureFlagService } from '../../core/services/feature-flags.service';
import { MetabaseService } from '../../core/services/metabase.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { AdminGestaoPessoasComponent } from './admin-gestao-pessoas.component';
import { AdminTabelasPessoalComponent } from './admin-tabelas-pessoal.component';

/**
 * Os DOIS modos de /admin/gestao-pessoas, decididos pela flag `dashboardPessoas`:
 * ligada, o corpo é o dashboard embutido e aparece o card do Quadro de Pessoal;
 * desligada, a tela é a de sempre — as três listagens, sem card novo e sem nenhuma
 * chamada ao Metabase. Cobre também o que o admin vê quando o embed falha ou quando
 * não há dashboard cadastrado para esta página, e que o retry refaz a busca.
 * O componente das tabelas é renderizado DE VERDADE (mocká-lo provaria só que o
 * template o cita); as três listagens em si são provadas no spec próprio dele.
 */

const CARD = { id: 'dash-1', titulo: 'Gestão de Pessoas — Operadores', descricao: null, icone: null, ordem: 1 };
const EMBED = 'https://bi-homolog.soap-nusp.org/embed/dashboard/jwt-fake';

/** 500 real do backend: o texto da tela vem na frente, o detalhe do corpo entre parênteses. */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };

describe('AdminGestaoPessoasComponent — dashboard x listagens conforme a flag', () => {
  let listarDashboards: ReturnType<typeof vi.fn>;
  let embedUrl: ReturnType<typeof vi.fn>;

  /** Monta o TestBed com a flag no estado desejado (lida na construção do componente). */
  async function configurar(flagLigada: boolean): Promise<ComponentFixture<AdminGestaoPessoasComponent>> {
    TestBed.resetTestingModule();
    listarDashboards = vi.fn();
    embedUrl = vi.fn();

    TestBed.configureTestingModule({
      imports: [AdminGestaoPessoasComponent],
      providers: [
        provideRouter([]),
        { provide: FeatureFlagService, useValue: { isEnabled: (f: string) => flagLigada && f === 'dashboardPessoas' } },
        { provide: MetabaseService, useValue: { listarDashboards, embedUrl } },
        // Deps do componente filho das tabelas (só exercitado com a flag desligada).
        { provide: ApiService, useValue: { getList: () => of({ data: [], meta: { page: 1, limit: 10, total: 0, pages: 0 } }), downloadReport: vi.fn() } },
        { provide: AuthService, useValue: { isMaster: signal(false) } },
      ],
    });
    // A URL de embed precisa ser um SafeResourceUrl de verdade: string crua em [src] de
    // iframe é barrada pela sanitização do Angular.
    listarDashboards.mockReturnValue(of([CARD]) as Observable<any>);
    embedUrl.mockReturnValue(of(TestBed.inject(DomSanitizer).bypassSecurityTrustResourceUrl(EMBED)));

    const fixture = TestBed.createComponent(AdminGestaoPessoasComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const iframe = (f: ComponentFixture<unknown>) => f.debugElement.query(By.css('iframe'));
  const tabelas = (f: ComponentFixture<unknown>) => f.debugElement.query(By.directive(AdminTabelasPessoalComponent));
  const erro = (f: ComponentFixture<unknown>) => f.debugElement.query(By.directive(ErroCargaComponent));
  const cardQuadro = (f: ComponentFixture<unknown>) =>
    f.debugElement.queryAll(By.css('a.card-nav')).find(a => a.nativeElement.textContent.trim() === 'Quadro de Pessoal');

  describe('flag desligada — a tela de antes, intacta', () => {
    it('mostra as três listagens e nenhum iframe', async () => {
      const fixture = await configurar(false);

      expect(tabelas(fixture)).toBeTruthy();
      expect(iframe(fixture)).toBeNull();
    });

    it('não oferece o card do Quadro de Pessoal (a rota está fechada pelo guard)', async () => {
      const fixture = await configurar(false);

      expect(cardQuadro(fixture)).toBeUndefined();
    });

    it('não chama o Metabase', async () => {
      await configurar(false);

      expect(listarDashboards).not.toHaveBeenCalled();
      expect(embedUrl).not.toHaveBeenCalled();
    });
  });

  describe('flag ligada — indicadores no corpo', () => {
    it('embute o dashboard da PÁGINA de gestão de pessoas, não o do Painel', async () => {
      const fixture = await configurar(true);

      expect(listarDashboards).toHaveBeenCalledWith('GESTAO_PESSOAS');
      expect(embedUrl).toHaveBeenCalledWith('dash-1');
      expect(iframe(fixture)).toBeTruthy();
    });

    it('troca as listagens pelo dashboard e oferece o card do Quadro de Pessoal', async () => {
      const fixture = await configurar(true);

      expect(tabelas(fixture)).toBeNull();
      expect(cardQuadro(fixture)).toBeTruthy();
    });

    it('falha ao listar vira caixa de erro com retry, não tela em branco', async () => {
      const fixture = await configurar(true);
      listarDashboards.mockReturnValue(throwError(() => ERRO_500));
      fixture.componentInstance.carregarIndicadores();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      const caixa = erro(fixture);
      expect(caixa).toBeTruthy();
      expect(caixa.nativeElement.textContent).toContain('Erro interno do servidor');
      expect(iframe(fixture)).toBeNull();
    });

    it('o retry refaz a busca e, dando certo, mostra o dashboard', async () => {
      const fixture = await configurar(true);
      listarDashboards.mockReturnValueOnce(throwError(() => ERRO_500));
      fixture.componentInstance.carregarIndicadores();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(erro(fixture)).toBeTruthy();

      erro(fixture).componentInstance.tentarNovamente.emit();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(erro(fixture)).toBeNull();
      expect(iframe(fixture)).toBeTruthy();
    });

    it('com o dashboard em erro, os cards de navegação continuam na tela (não se perde a saída)', async () => {
      const fixture = await configurar(true);
      listarDashboards.mockReturnValue(throwError(() => ERRO_500));
      fixture.componentInstance.carregarIndicadores();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.debugElement.queryAll(By.css('a.card-nav'))).toHaveLength(6);
      expect(cardQuadro(fixture)).toBeTruthy();
    });

    it('catálogo sem dashboard para esta página avisa em vez de ficar carregando para sempre', async () => {
      const fixture = await configurar(true);
      listarDashboards.mockReturnValue(of([]));
      embedUrl.mockClear();   // a carga inicial deu certo; o que interessa é a recarga
      fixture.componentInstance.carregarIndicadores();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(erro(fixture).nativeElement.textContent).toContain('Nenhum dashboard de indicadores configurado.');
      expect(embedUrl).not.toHaveBeenCalled();
    });
  });
});
