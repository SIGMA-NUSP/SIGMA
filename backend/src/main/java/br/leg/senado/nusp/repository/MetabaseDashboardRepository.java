package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.MetabaseDashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetabaseDashboardRepository extends JpaRepository<MetabaseDashboard, String> {

    List<MetabaseDashboard> findByAtivoTrueOrderByOrdemAscTituloAsc();
}
