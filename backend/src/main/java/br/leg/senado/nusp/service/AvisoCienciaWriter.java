package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.repository.AvisoCienciaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insere a ciência numa transação isolada (REQUIRES_NEW). Assim, se houver
 * corrida (duplo-clique / retry) e o INSERT violar o índice único parcial
 * UK_FRM_AVISO_CIE_OP/_TEC, apenas esta sub-transação é marcada para rollback —
 * a transação chamadora (AvisoService.registrarCiencia) permanece íntegra e
 * pode capturar a exceção sem ser arrastada para UnexpectedRollbackException.
 */
@Component
@RequiredArgsConstructor
public class AvisoCienciaWriter {

    private final AvisoCienciaRepository cienciaRepo;

    /** O saveAndFlush força a violação a aflorar aqui dentro (e não no commit externo). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inserir(AvisoCiencia c) {
        cienciaRepo.saveAndFlush(c);
    }
}
