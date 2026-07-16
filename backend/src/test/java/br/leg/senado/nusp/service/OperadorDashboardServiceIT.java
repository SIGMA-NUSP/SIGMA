package br.leg.senado.nusp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;

import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;

/**
 * ITs dos gates de ownership de {@link OperadorDashboardService} contra Oracle real (§4.14).
 *
 * O SUT é construído à mão — {@code EntityManager} é a única dependência. Matriz por recurso
 * (checklist, operação, anormalidade): 404 (query vazia decide, antes do gate), 403 (linha
 * existe mas o solicitante não tem acesso), dono direto, acesso "adicional" via junction e
 * "fixo do Plenário Principal".
 *
 * <p><b>Veredito do B1 (código vence).</b> O padrão sistemático dos fluxos de EDIÇÃO
 * (T6 {@code OperacaoService.editarEntrada}, T5 {@code ChecklistService.editar}) — gate de
 * ownership do titular ANTES de {@code isDonoOuAdicional}, tornando a junction inalcançável —
 * NÃO se repete aqui. {@code OperadorDashboardService} é LEITURA: {@code gate403} consulta a
 * junction logo após descartar o dono direto, então o acesso adicional é PLENAMENTE ALCANÇÁVEL
 * para checklist e operação. Anormalidade passa {@code sqlAdicional=null} — sem camada de
 * junction; um adicional recebe 403. A matriz do T14 fica exatamente como descrita.
 */
@OracleIT
class OperadorDashboardServiceIT {

    /** Nome canônico do Plenário Principal — precisa bater byte-a-byte com a constante do SUT. */
    private static final String NOME_PLENARIO_PRINCIPAL = "Plenário Principal";

    private static final long ID_INEXISTENTE = 999_999_999L;

    @Autowired
    private TestEntityManager em;

    private OperadorDashboardService service;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new OperadorDashboardService(emReal());
    }

    /** Sala com o NOME canônico do Plenário Principal (o gate do fixo compara por nome exato). */
    private Sala salaPlenarioPrincipal() {
        Sala s = new Sala();
        s.setNome(NOME_PLENARIO_PRINCIPAL);
        s.setMultiOperador(false);
        emReal().persist(s);
        emReal().flush();
        return s;
    }

    /** Operador marcado como fixo do Plenário Principal (PLENARIO_PRINCIPAL_FIXO = 1). */
    private Operador operadorFixoPP() {
        Operador op = CenarioFactory.novoOperador(emReal());
        op.setPlenarioPrincipalFixo(true);
        emReal().flush();
        return op;
    }

    private static long asLong(Object v) {
        return ((Number) v).longValue();
    }

    @Nested
    @DisplayName("getMeuChecklistDetalhe")
    class ChecklistDetalhe {

        @Test
        @DisplayName("checklist — id inexistente → 404 (query fundida vazia decide, antes de qualquer gate)")
        void checklist_idInexistente404() {
            // ruído: a tabela não está vazia — o 404 vem do filtro por ID, não de tabela vazia
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            CenarioFactory.novoChecklist(emReal(), sala, dono);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMeuChecklistDetalhe(ID_INEXISTENTE, dono.getId()));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }

        @Test
        @DisplayName("checklist — existe mas o solicitante não é dono/adicional/fixo → 403 (depois do 404)")
        void checklist_naoDono403() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, dono);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMeuChecklistDetalhe(checklist.getId(), terceiro.getId()));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("checklist — dono direto recebe o detalhe com somente_leitura=false")
        void checklist_donoDireto() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, dono);

            Map<String, Object> det = service.getMeuChecklistDetalhe(checklist.getId(), dono.getId());

            assertEquals(checklist.getId().longValue(), asLong(det.get("id")));
            assertEquals(dono.getId(), det.get("criado_por"));
            assertEquals(Boolean.FALSE, det.get("somente_leitura"), "dono tem leitura+escrita");
        }

        @Test
        @DisplayName("checklist — B1: adicional via junction FRM_CHECKLIST_OPERADOR ACESSA (leitura+escrita)")
        void checklist_adicionalViaJunctionAcessa() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador adicional = CenarioFactory.novoOperador(emReal());
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), sala, dono);
            CenarioFactory.novoVinculoChecklist(emReal(), checklist, adicional);

            Map<String, Object> det = service.getMeuChecklistDetalhe(checklist.getId(), adicional.getId());

            assertEquals(checklist.getId().longValue(), asLong(det.get("id")),
                    "diferente do fluxo de EDIÇÃO (T5): na leitura a junction é alcançável");
            assertEquals(Boolean.FALSE, det.get("somente_leitura"), "adicional tem leitura+escrita");
        }

        @Test
        @DisplayName("checklist — fixo do PP lê checklist do PP de outro dono, com somente_leitura=true")
        void checklist_fixoPlenarioPrincipal() {
            Sala pp = salaPlenarioPrincipal();
            Operador outroDono = CenarioFactory.novoOperador(emReal());
            Operador fixo = operadorFixoPP();
            Checklist checklist = CenarioFactory.novoChecklist(emReal(), pp, outroDono);

            Map<String, Object> det = service.getMeuChecklistDetalhe(checklist.getId(), fixo.getId());

            assertEquals(checklist.getId().longValue(), asLong(det.get("id")));
            assertEquals(Boolean.TRUE, det.get("somente_leitura"), "fixo do PP só lê");
        }
    }

    @Nested
    @DisplayName("getMinhaOperacaoDetalhe")
    class OperacaoDetalhe {

        @Test
        @DisplayName("operação — id inexistente → 404")
        void operacao_idInexistente404() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMinhaOperacaoDetalhe(ID_INEXISTENTE, dono.getId()));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }

        @Test
        @DisplayName("operação — existe mas o solicitante não é dono/adicional/fixo → 403")
        void operacao_naoDono403() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMinhaOperacaoDetalhe(entrada.getId(), terceiro.getId()));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("operação — dono direto (OPERADOR_ID) recebe o detalhe com somente_leitura=false")
        void operacao_donoDireto() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);

            Map<String, Object> det = service.getMinhaOperacaoDetalhe(entrada.getId(), dono.getId());

            assertEquals(entrada.getId().longValue(), asLong(det.get("id")));
            assertEquals(dono.getId(), det.get("operador_id"));
            assertEquals(Boolean.FALSE, det.get("somente_leitura"));
        }

        @Test
        @DisplayName("operação — B1: adicional via junction OPR_ENTRADA_OPERADOR ACESSA (leitura+escrita)")
        void operacao_adicionalViaJunctionAcessa() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador adicional = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntradaComRegistro(emReal(), sala, dono);
            CenarioFactory.novoVinculoOperador(emReal(), entrada, adicional);

            Map<String, Object> det = service.getMinhaOperacaoDetalhe(entrada.getId(), adicional.getId());

            assertEquals(entrada.getId().longValue(), asLong(det.get("id")),
                    "diferente do fluxo de EDIÇÃO (T6): na leitura a junction é alcançável");
            assertEquals(Boolean.FALSE, det.get("somente_leitura"));
        }

        @Test
        @DisplayName("operação — fixo do PP lê operação do PP de outro dono, com somente_leitura=true")
        void operacao_fixoPlenarioPrincipal() {
            Sala pp = salaPlenarioPrincipal();
            Operador outroDono = CenarioFactory.novoOperador(emReal());
            Operador fixo = operadorFixoPP();
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), pp);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, outroDono, 1);

            Map<String, Object> det = service.getMinhaOperacaoDetalhe(entrada.getId(), fixo.getId());

            assertEquals(entrada.getId().longValue(), asLong(det.get("id")));
            assertEquals(Boolean.TRUE, det.get("somente_leitura"));
        }
    }

    @Nested
    @DisplayName("getMinhaAnormalidadeDetalhe")
    class AnormalidadeDetalhe {

        @Test
        @DisplayName("anormalidade — id inexistente → 404")
        void anormalidade_idInexistente404() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, dono, 1);
            CenarioFactory.novaAnormalidade(emReal(), registro, sala, entrada.getId(), dono.getId());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMinhaAnormalidadeDetalhe(ID_INEXISTENTE, dono.getId()));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }

        @Test
        @DisplayName("anormalidade — existe mas o solicitante não é o criador → 403")
        void anormalidade_naoDono403() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador terceiro = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, dono, 1);
            RegistroAnormalidade anom = CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                    entrada.getId(), dono.getId());

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMinhaAnormalidadeDetalhe(anom.getId(), terceiro.getId()));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("anormalidade — dono (a.CRIADO_POR) recebe o detalhe")
        void anormalidade_donoDireto() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, dono, 1);
            RegistroAnormalidade anom = CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                    entrada.getId(), dono.getId());

            Map<String, Object> det = service.getMinhaAnormalidadeDetalhe(anom.getId(), dono.getId());

            assertEquals(anom.getId().longValue(), asLong(det.get("id")));
            assertEquals(sala.getNome(), det.get("sala_nome"));
        }

        @Test
        @DisplayName("anormalidade — B1: SEM camada de junction — quem é adicional NA OPERAÇÃO recebe 403 na anormalidade alheia")
        void anormalidade_semCamadaJunctionAdicionalRecebe403() {
            Sala sala = CenarioFactory.novaSala(emReal());
            Operador dono = CenarioFactory.novoOperador(emReal());
            Operador adicional = CenarioFactory.novoOperador(emReal());
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), sala);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, dono, 1);
            CenarioFactory.novoVinculoOperador(emReal(), entrada, adicional); // adicional NA OPERAÇÃO
            RegistroAnormalidade anom = CenarioFactory.novaAnormalidade(emReal(), registro, sala,
                    entrada.getId(), dono.getId());

            // O mesmo adicional ACESSA a operação (junction OPR_ENTRADA_OPERADOR)...
            Map<String, Object> op = service.getMinhaOperacaoDetalhe(entrada.getId(), adicional.getId());
            assertEquals(entrada.getId().longValue(), asLong(op.get("id")));

            // ...mas a anormalidade NÃO tem essa camada (sqlAdicional=null) → 403
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.getMinhaAnormalidadeDetalhe(anom.getId(), adicional.getId()));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("anormalidade — fixo do PP lê anormalidade do PP de outro dono (o gate do fixo vale para os 3 recursos)")
        void anormalidade_fixoPlenarioPrincipal() {
            Sala pp = salaPlenarioPrincipal();
            Operador outroDono = CenarioFactory.novoOperador(emReal());
            Operador fixo = operadorFixoPP();
            RegistroOperacaoAudio registro = CenarioFactory.novoRegistroAudio(emReal(), pp);
            RegistroOperacaoOperador entrada = CenarioFactory.novaEntrada(emReal(), registro, outroDono, 1);
            RegistroAnormalidade anom = CenarioFactory.novaAnormalidade(emReal(), registro, pp,
                    entrada.getId(), outroDono.getId());

            Map<String, Object> det = service.getMinhaAnormalidadeDetalhe(anom.getId(), fixo.getId());

            assertEquals(anom.getId().longValue(), asLong(det.get("id")));
        }
    }
}
