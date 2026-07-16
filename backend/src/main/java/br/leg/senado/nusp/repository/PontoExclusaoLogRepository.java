package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.PontoExclusaoLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PontoExclusaoLogRepository extends JpaRepository<PontoExclusaoLog, String> {

    /** Trilha de um lote (o mais recente primeiro) — consulta de auditoria; sem UI nesta entrega. */
    List<PontoExclusaoLog> findByLoteIdOrderByExcluidoEmDesc(String loteId);
}
