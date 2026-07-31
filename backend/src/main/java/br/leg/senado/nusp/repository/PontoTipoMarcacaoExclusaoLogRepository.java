package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoTipoMarcacaoExclusaoLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PontoTipoMarcacaoExclusaoLogRepository
        extends JpaRepository<PontoTipoMarcacaoExclusaoLog, String> {

    /** Trilha de um tipo já excluído, do registro mais recente para o mais antigo. */
    List<PontoTipoMarcacaoExclusaoLog> findByTipoIdOrderByExcluidoEmDesc(String tipoId);
}
