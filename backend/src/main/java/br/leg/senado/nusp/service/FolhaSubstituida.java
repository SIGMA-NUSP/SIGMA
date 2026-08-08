package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Qual folha publicada ainda é A folha daquele período para o DONO.
 *
 * <p>A Plansul corrige e reenvia folhas, e publicar a correção não apaga a anterior: as duas
 * continuam no histórico do admin, e é aqui que se decide qual delas o dono enxerga. A regra é uma
 * só — <b>a publicada mais recente vence</b> —, e vale apenas entre folhas que ocupam o MESMO lugar:
 *
 * <ul>
 *   <li>duas MENSAIS da mesma competência (a janela de meses que elas tocam, como a guarda da
 *       publicação a enxerga);</li>
 *   <li>duas SEMANAIS do período EXATAMENTE igual — semanais de períodos diferentes são cumulativas
 *       (01–05, 01–12, 01–19…) e convivem;</li>
 *   <li>mensal e semanal nunca se escondem: a semanal cobre um pedaço do mês, e as duas coexistem.</li>
 * </ul>
 *
 * <p>Uma exceção à recência, nos dois sentidos: entre a PRÉVIA e a DEFINITIVA do mesmo mês quem
 * decide é a natureza, não a data. A definitiva fecha o mês e toma o lugar da prévia; uma prévia
 * publicada depois de uma definitiva não desfaz nada (a guarda da publicação, aliás, recusa esse
 * envio — a regra aqui existe para que um acervo importado com essa ordem não esconda a folha certa).
 *
 * <p>Para o ADMIN nada some — o histórico de lotes continua inteiro. O que esta regra decide é a
 * lista do dono, o acesso direto dele ao PDF e a tela de retificação, que respondem a mesma coisa.
 */
final class FolhaSubstituida {

    private static final String TIPO_MENSAL = "MENSAL";

    private FolhaSubstituida() {
    }

    /**
     * Ordem total de "quem chegou depois". Decide a data de PUBLICAÇÃO — é ela que diz qual versão o
     * dono recebeu por último; a data do upload não serve, porque o admin pode enviar a correção
     * antes e publicá-la depois. Os desempates existem para que a ordem seja TOTAL: sem eles, duas
     * folhas do mesmo instante esconderiam uma à outra e o dono ficaria sem nenhuma.
     */
    private static final Comparator<PontoLote> CHEGOU_DEPOIS = Comparator
            .comparing(PontoLote::getPublicadoEm, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(PontoLote::getCriadoEm, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(PontoLote::getId);

    /**
     * Dentre as folhas publicadas de uma pessoa (pares [página, lote], como a consulta do dono os
     * devolve), os ids das PÁGINAS que saíram de cena.
     */
    static Set<String> paginasSubstituidas(List<Object[]> folhasPublicadas) {
        Set<String> escondidas = new HashSet<>();
        for (Object[] alvo : folhasPublicadas) {
            PontoLotePagina pagina = (PontoLotePagina) alvo[0];
            PontoLote lote = (PontoLote) alvo[1];
            for (Object[] candidata : folhasPublicadas) {
                if (substitui((PontoLotePagina) candidata[0], (PontoLote) candidata[1], pagina, lote)) {
                    escondidas.add(pagina.getId());
                    break;
                }
            }
        }
        return escondidas;
    }

    /** Aquela folha saiu de cena? A pergunta do acesso direto, sobre o mesmo conjunto. */
    static boolean substituida(String paginaId, List<Object[]> folhasPublicadas) {
        return paginasSubstituidas(folhasPublicadas).contains(paginaId);
    }

    /** A folha nova tomou o lugar da antiga? */
    private static boolean substitui(PontoLotePagina paginaNova, PontoLote nova,
                                     PontoLotePagina paginaAntiga, PontoLote antiga) {
        // Duas páginas do MESMO lote são a mesma folha (a da pessoa que ocupa duas páginas do PDF).
        if (nova.getId().equals(antiga.getId())) return false;
        if (!mesmaPessoa(paginaNova, paginaAntiga)) return false;
        if (!ocupamOMesmoLugar(nova, antiga)) return false;
        if (ehPrevia(nova) && ehDefinitiva(antiga)) return false;
        // A definitiva fecha o mês: ela ocupa o lugar da prévia mesmo tendo sido publicada antes.
        if (ehDefinitiva(nova) && ehPrevia(antiga)) return true;
        return CHEGOU_DEPOIS.compare(nova, antiga) > 0;
    }

    /** A chave da pessoa é o PAR (id, tipo) — o mesmo id com outro papel é outra pessoa. */
    private static boolean mesmaPessoa(PontoLotePagina a, PontoLotePagina b) {
        return a.getPessoaId() != null && a.getPessoaId().equals(b.getPessoaId())
                && a.getPessoaTipo() != null && a.getPessoaTipo().equals(b.getPessoaTipo());
    }

    private static boolean ocupamOMesmoLugar(PontoLote a, PontoLote b) {
        boolean mensalA = TIPO_MENSAL.equals(a.getTipo());
        boolean mensalB = TIPO_MENSAL.equals(b.getTipo());
        if (mensalA != mensalB) return false;
        return mensalA
                ? mesmaCompetencia(a, b)
                : a.getDataInicio().equals(b.getDataInicio()) && a.getDataFim().equals(b.getDataFim());
    }

    /**
     * As duas mensais disputam o mesmo mês? A competência é a janela de meses-calendário que o
     * período toca — a MESMA conta da guarda da publicação, para que o que ela anuncia como
     * substituição seja exatamente o que some da vista do dono.
     */
    private static boolean mesmaCompetencia(PontoLote a, PontoLote b) {
        return !inicioDaCompetencia(a).isAfter(fimDaCompetencia(b))
                && !inicioDaCompetencia(b).isAfter(fimDaCompetencia(a));
    }

    private static LocalDate inicioDaCompetencia(PontoLote lote) {
        return YearMonth.from(lote.getDataInicio()).atDay(1);
    }

    private static LocalDate fimDaCompetencia(PontoLote lote) {
        return YearMonth.from(lote.getDataFim()).atEndOfMonth();
    }

    private static boolean ehPrevia(PontoLote lote) {
        return PontoLote.CATEGORIA_PREVIA.equals(lote.getCategoria());
    }

    private static boolean ehDefinitiva(PontoLote lote) {
        return PontoLote.CATEGORIA_DEFINITIVA.equals(lote.getCategoria());
    }
}
