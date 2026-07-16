package br.leg.senado.nusp.controller;

import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infra de request/response compartilhada pelos controllers de listagem
 * (AdminDashboard, OperadorDashboard, Aviso): parse defensivo de paginação
 * e filtros JSON + envelope paginado {ok, data, meta}.
 * Package-private de propósito — utilitário interno do pacote, não uma camada.
 */
final class ControllerUtils {

    static final int REPORT_LIMIT = 100000;

    private ControllerUtils() {}

    static int getInt(String val, int def) {
        try { return val != null ? Integer.parseInt(val) : def; } catch (Exception e) { return def; }
    }

    /**
     * Campo obrigatório do payload, como texto (achado F27).
     *
     * <p>Os controllers que recebem o corpo como {@code Map} cru não têm binding do Spring para
     * validar nada: ler `payload.get("x").toString()` direto rebenta em {@code NullPointerException}
     * quando o campo falta, e o cliente recebe **500** por um erro que é dele. Aqui a ausência vira
     * {@link ServiceValidationException} → **400** com o nome do campo.
     */
    static String reqTexto(Map<String, Object> body, String campo) {
        Object valor = body == null ? null : body.get(campo);
        String texto = valor == null ? "" : valor.toString().strip();
        if (texto.isEmpty()) {
            throw new ServiceValidationException("Campo obrigatório ausente: " + campo + ".");
        }
        return texto;
    }

    /** Data obrigatória do payload, em ISO (AAAA-MM-DD) — 400 quando ausente ou fora do formato (F27). */
    static LocalDate reqData(Map<String, Object> body, String campo) {
        String texto = reqTexto(body, campo);
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException e) {
            throw new ServiceValidationException("Data inválida em " + campo + " (use AAAA-MM-DD).");
        }
    }

    /**
     * Identificador numérico OPCIONAL do payload — {@code null} só quando a chave está AUSENTE ou
     * vem {@code null}; 400 quando presente e não numérico (F27).
     *
     * <p>Texto em branco conta como valor inválido, não como ausência: tratá-lo como ausente faria
     * um {@code {"id":""}} num endpoint de "salvar" criar um registro NOVO em silêncio, em vez de
     * atualizar o existente — trocar um 500 barulhento por um dado errado gravado seria pior.
     */
    static Long optLong(Map<String, Object> body, String campo) {
        Object valor = body == null ? null : body.get(campo);
        if (valor == null) return null;
        try {
            return Long.valueOf(valor.toString().strip());
        } catch (NumberFormatException e) {
            throw new ServiceValidationException("Valor inválido em " + campo + " (esperado um número).");
        }
    }

    /**
     * Lista de textos de um mapa aninhado do payload (ex.: {@code "salas": {"3": ["uuid1","uuid2"]}}) — F27.
     *
     * <p>Valida o container E os elementos: o cast {@code (List<String>) valor} some no erasure, então
     * uma lista de números passava no controller e só estourava {@code ClassCastException} lá dentro do
     * service — 500 por um corpo torto do cliente, que é o defeito do F27. Elemento nulo também é
     * recusado (viraria NPE adiante).
     */
    static List<String> listaDeTextos(Object valor, String campo) {
        if (valor == null) return List.of();
        if (!(valor instanceof List<?> lista)) {
            throw new ServiceValidationException("Valor inválido em " + campo + " (esperado uma lista).");
        }
        List<String> textos = new ArrayList<>(lista.size());
        for (Object item : lista) {
            if (item == null || item.toString().isBlank()) {
                throw new ServiceValidationException("Item vazio na lista de " + campo + ".");
            }
            textos.add(item.toString().strip());
        }
        return textos;
    }

    /**
     * Texto OPCIONAL do payload — {@code null} quando a chave está AUSENTE ou vem {@code null};
     * 400 quando presente com outro tipo JSON (F35).
     *
     * <p>É o par tipado do {@link #reqTexto}, para os campos em que a AUSÊNCIA tem significado próprio:
     * no vínculo da página, {@code pessoa_id} nulo quer dizer <b>desvincular</b> — exigir o campo aqui
     * quebraria a função. O que não pode continuar é o {@code body.get(campo).toString()} cru, que
     * transforma uma lista {@code ["x"]} no texto {@code "[x]"} e um objeto {@code {"a":1}} no texto
     * {@code "{a=1}"} — este último chegava a ser GRAVADO como motivo de uma rejeição.
     *
     * <p>Não faz {@code strip()}: o valor segue ao service exatamente como veio (é lá que cada campo
     * decide o que é branco, o que é obrigatório e qual é o teto de tamanho).
     */
    static String optTexto(Map<String, Object> body, String campo) {
        Object valor = body == null ? null : body.get(campo);
        if (valor == null) return null;
        if (!(valor instanceof String texto)) {
            throw new ServiceValidationException("Valor inválido em " + campo + " (esperado um texto).");
        }
        return texto;
    }

    /**
     * Booleano OPCIONAL do payload — o {@code padrao} quando a chave está AUSENTE ou vem {@code null};
     * 400 quando presente com outro tipo JSON (F35).
     *
     * <p>Um booleano lido por {@code !Boolean.FALSE.equals(valor)} só reconhece o {@code false} JSON
     * genuíno: a string {@code "false"} — tipo errado — passava como TRUE em silêncio. No
     * {@code emitir_aviso} da publicação de folha isso significava disparar um aviso pessoal para cada
     * pessoa do lote quando o cliente pediu justamente o contrário.
     */
    static boolean optBooleano(Map<String, Object> body, String campo, boolean padrao) {
        Object valor = body == null ? null : body.get(campo);
        if (valor == null) return padrao;
        if (!(valor instanceof Boolean booleano)) {
            throw new ServiceValidationException("Valor inválido em " + campo + " (esperado true ou false).");
        }
        return booleano;
    }

    /** Chave numérica de um mapa aninhado do payload (ex.: {@code "salas": {"3": [...]}}) — 400 quando não numérica (F27). */
    static int chaveNumerica(Object chave, String campo) {
        try {
            return Integer.parseInt(String.valueOf(chave).strip());
        } catch (NumberFormatException e) {
            throw new ServiceValidationException("Chave inválida em " + campo + ": " + chave + " (esperado um número).");
        }
    }

    static Map<String, Object> parseJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return null; }
    }

    static ResponseEntity<?> pagedResponse(PagedResult result, int page, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", result.data());
        body.put("meta", Map.of(
                "distinct", result.distinct(),
                "page", page, "limit", limit,
                "total", result.total(),
                "pages", limit > 0 ? (result.total() + limit - 1) / limit : 1
        ));
        return ResponseEntity.ok(body);
    }

    /**
     * Política de senha mínima (6) da troca autenticada e do reset por token
     * (AuthController/PasswordResetController — mensagem byte a byte idêntica
     * nos dois; o mínimo 4 do reset por admin é contrato próprio, separado).
     *
     * @return o badRequest quando a senha é curta; null quando válida.
     */
    static ResponseEntity<Map<String, Object>> senhaCurtaBadRequest(String novaSenha) {
        if (novaSenha.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "A senha deve ter no mínimo 6 caracteres."));
        }
        return null;
    }
}
