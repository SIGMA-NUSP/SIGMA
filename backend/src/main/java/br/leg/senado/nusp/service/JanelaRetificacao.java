package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Quais dias de uma pessoa ainda aceitam retificação.
 *
 * <p>Não há prazo em dias: quem fecha um dia é a <b>folha mensal definitiva</b>, e só ela. A
 * pergunta é feita por dia e por pessoa — nunca pela folha que está aberta na tela —, sobre as
 * folhas que ainda valem para o dono (publicadas, não ocultas e não substituídas), e vale igual
 * para criar, alterar e apagar.
 *
 * <p>Um dia está aberto enquanto alguma folha vigente o cobre e nenhuma mensal definitiva o tiver
 * coberto. Semanais e prévias podem se acumular sobre o mesmo dia à vontade — reimprimi-lo não o
 * fecha; ele segue editável até a definitiva do período. E a definitiva encerra de uma vez por
 * todas: nada a desfaz — nem uma semanal publicada depois, nem a correção da própria definitiva.
 * Por isso as definitivas contam mesmo depois de substituídas: o que foi fechado uma vez fica
 * fechado.
 *
 * <p>É a cobertura que decide, não a data de publicação — e dia que nenhuma folha vigente cobre
 * não tem o que retificar.
 */
final class JanelaRetificacao {

    /** Por que o dia não aceita mais retificação. */
    enum Motivo {
        /** Uma folha mensal definitiva já cobriu o dia. */
        DEFINITIVA_PUBLICADA,
        /** Nenhuma folha que ainda vale para a pessoa cobre o dia. */
        SEM_FOLHA
    }

    private static final String TIPO_MENSAL = "MENSAL";

    /** Folha que ainda vale para o dono: o par [página, lote] como a consulta o devolve. */
    private record Folha(PontoLotePagina pagina, PontoLote lote) {}

    private final List<Folha> vigentes;
    private final List<PontoLote> definitivas;

    private JanelaRetificacao(List<Folha> vigentes, List<PontoLote> definitivas) {
        this.vigentes = vigentes;
        this.definitivas = definitivas;
    }

    /**
     * A janela de uma pessoa, a partir das folhas publicadas (pares [página, lote], como a consulta
     * do dono os devolve). A chave da pessoa é o PAR (id, papel): o mesmo id com outro papel é
     * outra pessoa, e nenhuma folha alheia entra na conta ainda que a lista traga.
     */
    static JanelaRetificacao daPessoa(List<Object[]> folhasPublicadas, String pessoaId, String pessoaTipo) {
        List<Object[]> daPessoa = new ArrayList<>();
        for (Object[] row : folhasPublicadas) {
            PontoLotePagina pagina = (PontoLotePagina) row[0];
            if (pessoaId.equals(pagina.getPessoaId()) && pessoaTipo.equals(pagina.getPessoaTipo())) {
                daPessoa.add(row);
            }
        }
        Set<String> substituidas = FolhaSubstituida.paginasSubstituidas(daPessoa);

        List<Folha> vigentes = new ArrayList<>();
        List<PontoLote> definitivas = new ArrayList<>();
        for (Object[] row : daPessoa) {
            PontoLotePagina pagina = (PontoLotePagina) row[0];
            PontoLote lote = (PontoLote) row[1];
            if (!substituidas.contains(pagina.getId())) vigentes.add(new Folha(pagina, lote));
            if (definitiva(lote)) definitivas.add(lote);
        }
        return new JanelaRetificacao(vigentes, definitivas);
    }

    boolean aberto(LocalDate dia) {
        return motivo(dia) == null;
    }

    /** Por que o dia está fechado; {@code null} quando ele aceita retificação. */
    Motivo motivo(LocalDate dia) {
        if (encerradoPorDefinitiva(dia)) return Motivo.DEFINITIVA_PUBLICADA;
        return algumaCobre(dia) ? null : Motivo.SEM_FOLHA;
    }

    /**
     * As páginas da folha que representa o dia — a mais recente entre as que ainda valem e o
     * cobrem. É de onde saem os horários que a pessoa não retificou: a folha da tela pode ser uma
     * antiga, mas o que vale é sempre a última publicação daquele dia. Vazio quando nenhuma folha
     * cobre o dia.
     *
     * <p>São as páginas, no plural, porque a folha de uma pessoa pode ocupar mais de uma página do
     * PDF — o dia procurado está numa delas.
     */
    List<String> paginasDoDia(LocalDate dia) {
        PontoLote maisRecente = null;
        for (Folha f : vigentes) {
            if (!cobre(f.lote(), dia)) continue;
            if (maisRecente == null || FolhaSubstituida.CHEGOU_DEPOIS.compare(f.lote(), maisRecente) > 0) {
                maisRecente = f.lote();
            }
        }
        if (maisRecente == null) return List.of();

        List<String> paginas = new ArrayList<>();
        for (Folha f : vigentes) {
            if (f.lote().getId().equals(maisRecente.getId())) paginas.add(f.pagina().getId());
        }
        return paginas;
    }

    // ══════════════════════════════════════════════════════════════

    /**
     * Alguma folha mensal definitiva imprimiu este dia? Inclusive uma que outra tenha substituído
     * depois: a definitiva encerra o dia no momento em que é publicada, e a versão corrigida dela
     * pode cobrir menos dias que a anterior sem reabrir o que já estava fechado.
     */
    private boolean encerradoPorDefinitiva(LocalDate dia) {
        for (PontoLote l : definitivas) {
            if (cobre(l, dia)) return true;
        }
        return false;
    }

    private boolean algumaCobre(LocalDate dia) {
        for (Folha f : vigentes) {
            if (cobre(f.lote(), dia)) return true;
        }
        return false;
    }

    private static boolean definitiva(PontoLote lote) {
        return TIPO_MENSAL.equals(lote.getTipo())
                && PontoLote.CATEGORIA_DEFINITIVA.equals(lote.getCategoria());
    }

    private static boolean cobre(PontoLote lote, LocalDate dia) {
        return !dia.isBefore(lote.getDataInicio()) && !dia.isAfter(lote.getDataFim());
    }
}
