package br.leg.senado.nusp.controller;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import br.leg.senado.nusp.security.AdminOnly;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda estrutural do F2: {@code @AdminOnly} e o prefixo {@code /api/admin} andam juntos —
 * em TODA rota administrativa, não só nas amostradas.
 *
 * <p>O {@code AdminOnlyMethodSecurityTest} prova que a anotação FUNCIONA (403 sem o matcher),
 * mas exercita 3 rotas; a matriz do T15 mede o matcher, não a anotação. Sem este teste,
 * uma rota admin nova criada sem {@code @AdminOnly} — ou a anotação apagada de uma das
 * existentes — deixaria a suíte inteira verde e o F2 regrediria em silêncio, que é
 * exatamente o modo de falha que o achado descreve.
 *
 * <p>Duas formas de proteção, conforme o desenho do controller:
 * <ul>
 *   <li><b>MISTOS</b> (o F2 propriamente dito): sem {@code @RequestMapping} de classe, rotas admin e
 *       comuns lado a lado → a anotação vai <b>método a método</b>. Aqui se trava as duas direções:
 *       nenhuma rota {@code /api/admin/...} sem ela, e nenhuma rota comum COM ela (anotar uma rota de
 *       operador/técnico a fecharia em produção).</li>
 *   <li><b>PUROS</b> (extensão autorizada pelo Douglas): todo o controller é administrativo e o prefixo
 *       vem do {@code @RequestMapping} de classe → a anotação vai <b>na classe</b>, e vale para todos os
 *       métodos. Aqui se trava a anotação de classe e o prefixo — se alguém remover o
 *       {@code @RequestMapping} de classe, o teste cai.</li>
 * </ul>
 */
class AdminOnlyCoberturaTest {

    /** Os 3 controllers do F2 — sem {@code @RequestMapping} de classe, rotas admin e comuns lado a lado. */
    private static final List<Class<?>> CONTROLLERS_MISTOS =
            List.of(AvisoController.class, EscalaSemanalController.class, PontoController.class);

    /** Controllers 100% administrativos: prefixo e {@code @AdminOnly} no nível de classe. */
    private static final List<Class<?>> CONTROLLERS_ADMIN_PUROS =
            List.of(AdminCrudController.class, AdminDashboardController.class, MetabaseDashboardController.class);

    private static final String PREFIXO_ADMIN = "/api/admin";

    static Stream<Arguments> rotas() {
        List<Arguments> rotas = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS_MISTOS) {
            // Nenhum dos 3 tem @RequestMapping de classe (é o próprio achado F2): se um ganhar
            // um prefixo de classe, o path do método deixa de ser o path completo e a varredura
            // mentiria — o assert abaixo derruba o teste antes disso acontecer em silêncio.
            assertThat(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class))
                    .as("%s ganhou @RequestMapping de classe — reveja esta varredura", controller.getSimpleName())
                    .isNull();

            for (Method m : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) continue;
                for (String path : mapping.path()) {
                    rotas.add(Arguments.of(controller.getSimpleName(), m.getName(), path,
                            temAdminOnly(m)));
                }
            }
        }
        assertThat(rotas).as("a varredura tem de encontrar as rotas dos 3 controllers").isNotEmpty();
        return rotas.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}.{1} → {2}")
    @MethodSource("rotas")
    @DisplayName("F2 — rota /api/admin tem @AdminOnly; rota comum não tem")
    void prefixoEAnotacaoAndamJuntos(String controller, String metodo, String path, boolean anotada) {
        boolean admin = path.startsWith(PREFIXO_ADMIN);

        assertThat(anotada)
                .as(admin
                        ? "%s.%s expõe %s (rota admin) e PRECISA de @AdminOnly — sem ela, a única camada é o matcher do SecurityConfig"
                        : "%s.%s expõe %s (rota comum) e NÃO pode ter @AdminOnly — operador/técnico perderiam o acesso",
                        controller, metodo, path)
                .isEqualTo(admin);
    }

    static Stream<Arguments> controllersAdminPuros() {
        return CONTROLLERS_ADMIN_PUROS.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("controllersAdminPuros")
    @DisplayName("F2 — controller 100% admin tem @AdminOnly na classe, sob o prefixo /api/admin")
    void controllerAdminPuro_anotadoNaClasse(Class<?> controller) {
        RequestMapping deClasse = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);

        assertThat(deClasse)
                .as("%s perdeu o @RequestMapping de classe — as rotas saem do prefixo do matcher e a "
                        + "anotação de classe deixa de ser suficiente; reveja a proteção", controller.getSimpleName())
                .isNotNull();
        assertThat(deClasse.path())
                .as("%s: todo path de classe tem de ficar sob %s", controller.getSimpleName(), PREFIXO_ADMIN)
                .isNotEmpty()
                .allSatisfy(path -> assertThat(path).startsWith(PREFIXO_ADMIN));
        assertThat(AnnotatedElementUtils.hasAnnotation(controller, AdminOnly.class))
                .as("%s é 100%% administrativo e PRECISA de @AdminOnly na classe — sem ela, a única camada "
                        + "é o matcher do SecurityConfig", controller.getSimpleName())
                .isTrue();
    }

    /** {@code @AdminOnly} direta ou como meta-anotação (o Spring Security a resolve dos dois jeitos). */
    private static boolean temAdminOnly(Method m) {
        if (AnnotatedElementUtils.hasAnnotation(m, AdminOnly.class)) return true;
        return Arrays.stream(m.getAnnotations())
                .map(Annotation::annotationType)
                .anyMatch(t -> AnnotatedElementUtils.hasAnnotation(t, AdminOnly.class));
    }
}
