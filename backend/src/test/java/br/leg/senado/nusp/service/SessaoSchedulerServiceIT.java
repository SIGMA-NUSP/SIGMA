package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;

/**
 * ITs dos 2 UPDATEs de {@link SessaoSchedulerService} contra Oracle real — o job
 * que fecha, às 06:00 BRT, as sessões de áudio deixadas abertas em dias anteriores.
 *
 * Só é testável aqui: o "tempo" do SUT é {@code TRUNC(SYSDATE)} dentro do SQL, e
 * {@code FECHADO_EM} nasce de {@code TO_DSINTERVAL} sobre o VARCHAR2 'HH:MI:SS'.
 * Por isso as DATAs das linhas são ancoradas no relógio do BANCO
 * ({@link CenarioFactory#fixarDataRelativa}) e as asserções olham o estado final
 * das linhas — o método devolve void. A releitura de OPR_REGISTRO_AUDIO é sempre
 * por SQL nativo, sem passar pela entidade.
 *
 * O {@code @Transactional} do job é inerte neste arranjo (service construído à mão,
 * sem proxy): estes testes provam a semântica dos dois UPDATEs dentro da transação
 * do próprio teste, não a demarcação transacional em produção.
 *
 * Toda sessão aberta ocupa uma sala distinta — a FBI única UQ_OPR_REGAUDIO_SALA_ABERTA
 * admite no máximo 1 registro aberto por sala.
 */
@OracleIT
class SessaoSchedulerServiceIT {

    @Autowired
    private TestEntityManager em;

    private SessaoSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new SessaoSchedulerService(emReal());
    }

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    /** Sessão aberta em sala nova, com a DATA ancorada em (hoje - diasAtras) no relógio do banco. */
    private RegistroOperacaoAudio sessaoAberta(int diasAtras) {
        Sala sala = CenarioFactory.novaSala(emReal());
        RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala, true);
        CenarioFactory.fixarDataRelativa(emReal(), "OPR_REGISTRO_AUDIO", "DATA", registro.getId(), diasAtras);
        return registro;
    }

    private RegistroOperacaoOperador entrada(RegistroOperacaoAudio registro, Operador operador, int ordem,
            String horaSaida, String horarioTermino) {
        RegistroOperacaoOperador e = CenarioFactory.novaEntrada(emReal(), registro, operador, ordem);
        e.setHoraSaida(horaSaida);
        e.setHorarioTermino(horarioTermino);
        emReal().flush();
        return e;
    }

    /** Data (sem hora) de hoje - diasAtras, como o banco a enxerga. */
    private String dia(int diasAtras) {
        return (String) emReal()
                .createNativeQuery("SELECT TO_CHAR(TRUNC(SYSDATE) - :d, 'YYYY-MM-DD') FROM dual")
                .setParameter("d", diasAtras)
                .getSingleResult();
    }

    /** Estado do registro sem passar pela entidade: [EM_ABERTO, FECHADO_POR, FECHADO_EM formatado]. */
    private Object[] lerRegistro(Long id) {
        return (Object[]) emReal().createNativeQuery("""
                SELECT EM_ABERTO, FECHADO_POR, TO_CHAR(FECHADO_EM, 'YYYY-MM-DD HH24:MI:SS')
                FROM OPR_REGISTRO_AUDIO WHERE ID = :id
                """)
                .setParameter("id", id)
                .getSingleResult();
    }

    private String lerHorarioTermino(Long entradaId) {
        return (String) emReal()
                .createNativeQuery("SELECT HORARIO_TERMINO FROM OPR_REGISTRO_ENTRADA WHERE ID = :id")
                .setParameter("id", entradaId)
                .getSingleResult();
    }

    private String lerHoraSaida(Long entradaId) {
        return (String) emReal()
                .createNativeQuery("SELECT HORA_SAIDA FROM OPR_REGISTRO_ENTRADA WHERE ID = :id")
                .setParameter("id", entradaId)
                .getSingleResult();
    }

    private void executarJob() {
        service.fecharSessoesAbertas();
        emReal().clear(); // os 2 UPDATEs são nativos: sem clear, a releitura serviria entidade stale
    }

    @Test
    @DisplayName("fecharSessoesAbertas — sessão de ontem: HORARIO_TERMINO recebe HORA_SAIDA, EM_ABERTO zera e FECHADO_EM = DATA + maior HORA_SAIDA")
    void fecharSessoesAbertas_fechaSessaoDeDiaAnterior() {
        RegistroOperacaoAudio ontem = sessaoAberta(1);
        Operador primeiro = CenarioFactory.novoOperador(emReal());
        Operador ultimo = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoOperador e1 = entrada(ontem, primeiro, 1, "10:00:00", null);
        RegistroOperacaoOperador e2 = entrada(ontem, ultimo, 2, "12:30:00", null);

        executarJob();

        assertEquals("10:00:00", lerHorarioTermino(e1.getId()), "Query 1 copia HORA_SAIDA → HORARIO_TERMINO");
        assertEquals("12:30:00", lerHorarioTermino(e2.getId()));
        Object[] registro = lerRegistro(ontem.getId());
        assertEquals(0, ((Number) registro[0]).intValue(), "EM_ABERTO deveria zerar");
        assertEquals(ultimo.getId(), registro[1], "FECHADO_POR = operador da maior HORA_SAIDA");
        assertEquals(dia(1) + " 12:30:00", registro[2],
                "FECHADO_EM = CAST(DATA AS TIMESTAMP) + TO_DSINTERVAL('0 ' || MAX(HORA_SAIDA))");
    }

    @Test
    @DisplayName("fecharSessoesAbertas — sessão do próprio dia fica intacta (DATA < TRUNC(SYSDATE))")
    void fecharSessoesAbertas_sessaoDeHojeFicaIntacta() {
        RegistroOperacaoAudio hoje = sessaoAberta(0);
        Operador operador = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoOperador entradaHoje = entrada(hoje, operador, 1, "09:00:00", null);

        executarJob();

        assertNull(lerHorarioTermino(entradaHoje.getId()),
                "a Query 1 não pode alcançar entradas de sessão do próprio dia");
        Object[] registro = lerRegistro(hoje.getId());
        assertEquals(1, ((Number) registro[0]).intValue(), "EM_ABERTO da sessão de hoje deveria continuar 1");
        assertNull(registro[1], "FECHADO_POR deveria continuar NULL");
        assertNull(registro[2], "FECHADO_EM deveria continuar NULL");
    }

    @Test
    @DisplayName("fecharSessoesAbertas — FECHADO_POR vem da maior HORA_SAIDA, não da maior ORDEM")
    void fecharSessoesAbertas_fechadoPorSeguiHoraSaidaNaoAOrdem() {
        RegistroOperacaoAudio ontem = sessaoAberta(1);
        Operador saiuPorUltimo = CenarioFactory.novoOperador(emReal());
        Operador ordemMaior = CenarioFactory.novoOperador(emReal());
        entrada(ontem, saiuPorUltimo, 1, "18:00:00", null);
        entrada(ontem, ordemMaior, 2, "10:00:00", null);

        executarJob();

        Object[] registro = lerRegistro(ontem.getId());
        assertEquals(saiuPorUltimo.getId(), registro[1],
                "a subquery ordena por HORA_SAIDA DESC — a entrada de ORDEM 2 saiu antes");
        assertEquals(dia(1) + " 18:00:00", registro[2]);
    }

    @Test
    @DisplayName("fecharSessoesAbertas — HORARIO_TERMINO já preenchido não é sobrescrito, e entrada sem HORA_SAIDA não recebe término")
    void fecharSessoesAbertas_preservaTerminoExistenteEIgnoraEntradaSemSaida() {
        RegistroOperacaoAudio ontem = sessaoAberta(1);
        Operador comTermino = CenarioFactory.novoOperador(emReal());
        Operador semSaida = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoOperador jaTemTermino = entrada(ontem, comTermino, 1, "12:30:00", "11:11:11");
        RegistroOperacaoOperador semHoraSaida = entrada(ontem, semSaida, 2, null, null);

        executarJob();

        assertEquals("11:11:11", lerHorarioTermino(jaTemTermino.getId()),
                "predicado HORARIO_TERMINO IS NULL protege o valor já registrado");
        assertNull(lerHorarioTermino(semHoraSaida.getId()),
                "predicado HORA_SAIDA IS NOT NULL — sem saída não há o que copiar");
        Object[] registro = lerRegistro(ontem.getId());
        assertEquals(0, ((Number) registro[0]).intValue());
        assertEquals(comTermino.getId(), registro[1], "só a entrada com HORA_SAIDA concorre ao FECHADO_POR");
        assertEquals(dia(1) + " 12:30:00", registro[2], "MAX(HORA_SAIDA) ignora a entrada sem saída");
    }

    @Test
    @DisplayName("fecharSessoesAbertas — sessão de dia anterior já fechada não é reprocessada (EM_ABERTO = 1 no WHERE)")
    void fecharSessoesAbertas_sessaoJaFechadaNaoEReprocessada() {
        Sala sala = CenarioFactory.novaSala(emReal());
        RegistroOperacaoAudio fechada = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
        Operador operador = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoOperador entradaAntiga = entrada(fechada, operador, 1, "12:30:00", null);
        CenarioFactory.fixarDataRelativa(emReal(), "OPR_REGISTRO_AUDIO", "DATA", fechada.getId(), 1);

        executarJob();

        assertNull(lerHorarioTermino(entradaAntiga.getId()),
                "a Query 1 só alcança entradas de registros com EM_ABERTO = 1");
        Object[] registro = lerRegistro(fechada.getId());
        assertEquals(0, ((Number) registro[0]).intValue());
        assertNull(registro[1], "FECHADO_POR não pode ser preenchido por reprocessamento");
        assertNull(registro[2], "FECHADO_EM não pode ser preenchido por reprocessamento");
    }

    @Test
    @DisplayName("sessão de dia anterior sem nenhuma HORA_SAIDA fecha com FECHADO_EM nulo, e a sessão saudável do mesmo lote fecha normalmente")
    void fecharSessoesAbertas_sessaoSemHoraSaidaFechaComFechadoEmNuloSemDerrubarOLote() {
        // MAX(HORA_SAIDA) sobre conjunto vazio é NULL, e '0 ' || NULL = '0 '
        // — TO_DSINTERVAL('0 ') estourava ORA-01867. Como a Query 2 é um único UPDATE sobre TODAS
        // as sessões de dias anteriores, o erro abortava o statement inteiro: nenhuma sessão fechava
        // naquele dia (o @Transactional é inerte neste arranjo — a prova certa é "a saudável fechou",
        // não "a transação reverteu"). A guarda CASE cura os dois lados de uma vez.
        RegistroOperacaoAudio saudavel = sessaoAberta(1);
        Operador operadorSaudavel = CenarioFactory.novoOperador(emReal());
        entrada(saudavel, operadorSaudavel, 1, "12:30:00", null);
        RegistroOperacaoAudio semSaida = sessaoAberta(1);
        entrada(semSaida, CenarioFactory.novoOperador(emReal()), 1, null, null);

        executarJob(); // não estoura mais

        Object[] degenerada = lerRegistro(semSaida.getId());
        assertEquals(0, ((Number) degenerada[0]).intValue(),
                "decisão do Douglas: a sessão sem hora de saída FECHA (não fica 'em operação' para sempre)");
        assertNull(degenerada[2], "sem HORA_SAIDA não há carimbo: FECHADO_EM fica nulo");
        assertNull(degenerada[1], "FECHADO_POR também fica nulo — não há entrada com saída a apontar");

        Object[] intacta = lerRegistro(saudavel.getId());
        assertEquals(0, ((Number) intacta[0]).intValue(),
                "o lote deixou de ser envenenado: a sessão saudável do mesmo run fecha");
        assertEquals(dia(1) + " 12:30:00", intacta[2], "e fecha exatamente como antes da guarda");
        assertEquals(operadorSaudavel.getId(), intacta[1]);
    }

    @Test
    @DisplayName("HORA_SAIDA = '24:00:00' fecha a sessão como 23:59:59 (fim visível): "
            + "Query 1 copia 23:59:59 e FECHADO_EM = DATA + 23:59:59")
    void fecharSessoesAbertas_vinteEQuatroHorasViraUmMinutoAntes() {
        // A escrita recusa 24:00:00 na porta; este é o lixo pré-existente que a
        // faxina TOLERA. Antes: TO_DSINTERVAL('0 24:00:00') → ORA-01850 abortava o UPDATE.
        RegistroOperacaoAudio ontem = sessaoAberta(1);
        Operador operador = CenarioFactory.novoOperador(emReal());
        RegistroOperacaoOperador e = entrada(ontem, operador, 1, "24:00:00", null);

        executarJob(); // não estoura mais

        assertEquals("23:59:59", lerHorarioTermino(e.getId()),
                "Query 1 copia o valor MAPEADO (24:00:00 → 23:59:59), não o cru");
        Object[] registro = lerRegistro(ontem.getId());
        assertEquals(0, ((Number) registro[0]).intValue(), "a sala libera");
        assertEquals(operador.getId(), registro[1], "o 24:00:00 mapeado ainda concorre ao FECHADO_POR");
        assertEquals(dia(1) + " 23:59:59", registro[2], "FECHADO_EM carimbado com o valor mapeado");
        assertEquals("24:00:00", lerHoraSaida(e.getId()), "a HORA_SAIDA torta NÃO é reescrita (sem saneamento)");
    }

    @Test
    @DisplayName("HORA_SAIDA torta ('xx:yy:zz' / '25:00:00') fecha com carimbo em branco: "
            + "HORARIO_TERMINO fica nulo, FECHADO_EM/POR nulos, EM_ABERTO = 0, e a torta permanece intacta")
    void fecharSessoesAbertas_horaTortaFechaComCarimboEmBranco() {
        RegistroOperacaoAudio comLixo = sessaoAberta(1);
        RegistroOperacaoOperador tortaTexto = entrada(comLixo, CenarioFactory.novoOperador(emReal()), 1,
                "xx:yy:zz", null);
        RegistroOperacaoAudio comHoraImpossivel = sessaoAberta(1);
        RegistroOperacaoOperador tortaHora = entrada(comHoraImpossivel, CenarioFactory.novoOperador(emReal()), 1,
                "25:00:00", null);

        executarJob(); // antes: ORA-01867 ('xx') / ORA-01850 ('25:00:00') abortavam o UPDATE

        for (var caso : java.util.List.of(
                java.util.Map.entry(comLixo, tortaTexto), java.util.Map.entry(comHoraImpossivel, tortaHora))) {
            Object[] registro = lerRegistro(caso.getKey().getId());
            assertEquals(0, ((Number) registro[0]).intValue(), "a sala libera mesmo com lixo");
            assertNull(registro[2], "torta não gera carimbo: FECHADO_EM nulo (caminho degenerado)");
            assertNull(registro[1], "FECHADO_POR nulo — nenhuma saída válida a apontar");
            assertNull(lerHorarioTermino(caso.getValue().getId()),
                    "Query 1 NÃO copia a torta → relatório mostra 'Evento não encerrado'");
        }
        assertEquals("xx:yy:zz", lerHoraSaida(tortaTexto.getId()), "sem saneamento: a torta fica");
        assertEquals("25:00:00", lerHoraSaida(tortaHora.getId()));
    }

    @Test
    @DisplayName("lote misto: a sessão torta não envenena o UPDATE e a saudável fecha com carimbo normal")
    void fecharSessoesAbertas_loteMistoTortaNaoEnvenenaASaudavel() {
        // Sem a tolerância da faxina, este cenário estoura ORA-01850/01867 e NENHUMA sessão
        // fecha (salas presas em "operação").
        RegistroOperacaoAudio torta = sessaoAberta(1);
        entrada(torta, CenarioFactory.novoOperador(emReal()), 1, "99:99:99", null);
        RegistroOperacaoAudio saudavel = sessaoAberta(1);
        Operador operadorSaudavel = CenarioFactory.novoOperador(emReal());
        entrada(saudavel, operadorSaudavel, 1, "12:30:00", null);

        executarJob();

        Object[] intacta = lerRegistro(saudavel.getId());
        assertEquals(0, ((Number) intacta[0]).intValue(), "a saudável do mesmo lote fecha");
        assertEquals(dia(1) + " 12:30:00", intacta[2], "com o carimbo de sempre");
        assertEquals(operadorSaudavel.getId(), intacta[1]);
        assertEquals(0, ((Number) lerRegistro(torta.getId())[0]).intValue(), "e a torta também libera a sala");
    }

    @Test
    @DisplayName("entrada torta e entrada válida na MESMA sessão: MAX e FECHADO_POR vêm da válida")
    void fecharSessoesAbertas_tortaNaMesmaSessaoNaoVenceOMax() {
        // 'xx:yy:zz' é lexicograficamente maior que qualquer hora numérica: sem o filtro de
        // validade, venceria o MAX e o ORDER BY — carimbo torto e autor errado.
        RegistroOperacaoAudio ontem = sessaoAberta(1);
        Operador daTorta = CenarioFactory.novoOperador(emReal());
        Operador daValida = CenarioFactory.novoOperador(emReal());
        entrada(ontem, daTorta, 1, "xx:yy:zz", null);
        entrada(ontem, daValida, 2, "12:30:00", null);

        executarJob();

        Object[] registro = lerRegistro(ontem.getId());
        assertEquals(0, ((Number) registro[0]).intValue());
        assertEquals(daValida.getId(), registro[1],
                "FECHADO_POR considera só saídas válidas — a torta não atribui o fechamento a ninguém");
        assertEquals(dia(1) + " 12:30:00", registro[2], "MAX ignora a torta (vira NULL no CASE)");
    }

    @Test
    @DisplayName("sessão de dia anterior sem nenhuma entrada fecha com FECHADO_EM e FECHADO_POR nulos")
    void fecharSessoesAbertas_sessaoSemEntradasFechaComFechadoEmNulo() {
        // Mesmo cenário pelo outro caminho: registro órfão de entradas (a subquery escalar não
        // encontra linha alguma e MAX devolve NULL).
        RegistroOperacaoAudio orfa = sessaoAberta(1);
        // Blinda o cenário: o caminho "registro sem NENHUMA entrada" só é coberto se a sessão for de
        // fato órfã — se um dia a factory semear entrada, o teste ainda passaria (por HORA_SAIDA
        // null), mas deixaria de exercitar este ramo. Falha ruidosa aqui, não verde falso.
        Number entradas = (Number) emReal()
                .createNativeQuery("SELECT COUNT(*) FROM OPR_REGISTRO_ENTRADA WHERE REGISTRO_ID = :id")
                .setParameter("id", orfa.getId())
                .getSingleResult();
        assertEquals(0, entradas.intValue(), "o cenário exige um registro sem nenhuma entrada");

        executarJob(); // não estoura mais

        Object[] registro = lerRegistro(orfa.getId());
        assertEquals(0, ((Number) registro[0]).intValue(), "a sessão órfã também fecha");
        assertNull(registro[2], "FECHADO_EM nulo — não há HORA_SAIDA de onde tirar o carimbo");
        assertNull(registro[1], "FECHADO_POR nulo");
    }
}
