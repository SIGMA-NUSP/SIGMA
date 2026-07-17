package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;

import java.util.regex.Pattern;

/**
 * Régua única de horário de entrada manual (F73/C19) — o equivalente de servidor do
 * {@code <input type="time">} das telas: até aqui toda a segurança de formato era do
 * frontend, e quem escrevia pela API gravava "24:00:00" ou "xx" numa coluna VARCHAR2.
 *
 * <p>Contrato: null/vazio significa "não informado" e passa (a obrigatoriedade é de cada
 * porta); {@code HH:MM} ou {@code HH:MM:SS} com hora 00–23 e minuto/segundo 00–59 passam;
 * QUALQUER outra coisa ("24:00:00", "12:60:00", "xx", "1:5"...) recusa com 400 nomeando o
 * campo pelo rótulo da tela (idioma do {@code normalizarHora} do AdminCrud).
 *
 * <p>Jornada de trabalho ({@code AdminCrudService.normalizarHora}) e retificação de ponto
 * ({@code RetificacaoService}, C11) já validavam com mensagens próprias travadas em teste
 * e NÃO migram para cá (decisão do C19) — a régua (00–23/00–59) é a mesma.
 */
public final class HoraValidator {

    /** HH:MM 00:00–23:59 (regex canônico do projeto, cf. AdminCrudService/RetificacaoService). */
    private static final Pattern HORA_HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    /** HH:MM:SS — o formato das colunas de horário VARCHAR2(8) do módulo de operação. */
    private static final Pattern HORA_HHMMSS = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");

    private HoraValidator() {}

    /**
     * Valida (e opcionalmente completa) um horário de entrada manual.
     *
     * @param valor         o que chegou do body (cru; null/vazio = não informado → null)
     * @param rotuloDoCampo rótulo da tela, usado na mensagem de recusa
     * @param comSegundos   true quando a coluna grava HH:MM:SS → "HH:MM" é completado com ":00"
     * @return null se vazio; o horário validado (completado quando {@code comSegundos})
     */
    public static String normalizar(String valor, String rotuloDoCampo, boolean comSegundos) {
        if (valor == null || valor.isBlank()) return null;
        String h = valor.strip();
        if (HORA_HHMM.matcher(h).matches()) return comSegundos ? h + ":00" : h;
        if (HORA_HHMMSS.matcher(h).matches()) return h;
        throw new ServiceValidationException(
                "Horário inválido em '" + rotuloDoCampo + "': '" + h + "'. Use o formato HH:MM.");
    }
}
