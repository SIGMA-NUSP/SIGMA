package br.leg.senado.nusp.controller.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Builders de request com {@code servletPath} explícito — obrigatório nos
 * testes de controller deste projeto.
 *
 * O {@code JwtAuthenticationFilter.shouldNotFilter} decide as rotas públicas
 * por {@code request.getServletPath()}. Em produção o DispatcherServlet está
 * mapeado em "/" e o servletPath é o caminho completo; no MockMvc ele é VAZIO
 * por default → o filtro nunca se reconhece isento e responde 401 nas rotas
 * permitAll sem token. Setar o servletPath no builder (com pathInfo nulo,
 * derivado pelo próprio MockMvc) reproduz o contêiner real.
 *
 * <p>{@code get}/{@code post} nasceram com o T15 (matriz RBAC). {@code patch},
 * {@code delete} e {@code multipart} foram acrescentados pelo T16 (controllers
 * admin: toggles PATCH do AdminCrud, DELETE de escala e upload multipart de
 * foto). {@code put} foi acrescentado pelo T17 (edição de operação/checklist)
 * — todos com a mesma disciplina de servletPath.
 */
public final class Requests {

    private Requests() {
    }

    public static MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).servletPath(semQuery(url));
    }

    public static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).servletPath(semQuery(url));
    }

    public static MockHttpServletRequestBuilder put(String url) {
        return MockMvcRequestBuilders.put(url).servletPath(semQuery(url));
    }

    public static MockHttpServletRequestBuilder patch(String url) {
        return MockMvcRequestBuilders.patch(url).servletPath(semQuery(url));
    }

    public static MockHttpServletRequestBuilder delete(String url) {
        return MockMvcRequestBuilders.delete(url).servletPath(semQuery(url));
    }

    /**
     * Request multipart (POST) para o upload de foto do AdminCrudController.
     * O servletPath é setado no próprio builder de multipart (que expõe {@code
     * .file(...)}) — chamado como statement e devolvendo o builder para não
     * perder o subtipo, independente do tipo de retorno de {@code servletPath}.
     */
    public static MockMultipartHttpServletRequestBuilder multipart(String url) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart(url);
        b.servletPath(semQuery(url));
        return b;
    }

    /** servletPath nunca inclui a query string. */
    private static String semQuery(String url) {
        int i = url.indexOf('?');
        return i < 0 ? url : url.substring(0, i);
    }
}
