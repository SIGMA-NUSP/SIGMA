package br.leg.senado.nusp.service;

import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * "Esta pessoa existe?" — resposta única para o par polimórfico
 * {@code (PESSOA_ID, PESSOA_TIPO)} do módulo Ponto.
 *
 * <p>As tabelas do Ponto guardam a pessoa como um par (id + tipo) e <b>não têm FK</b> — o cadastro
 * mora em três tabelas diferentes (PES_OPERADOR / PES_TECNICO / PES_ADMINISTRADOR), e nenhuma
 * constraint impede gravar um id que não existe em lugar nenhum. Quem escreve esse par tem, então,
 * de conferir a existência: era o que o vínculo da página já fazia e a marcação pessoa-dia não fazia
 * (F34 — a marcação órfã que nunca aparecia na grade e que o modal não removia).
 *
 * <p>O {@code switch} por tipo vive AQUI, e não copiado em cada chamador: o par <b>trocado</b> (id
 * real de OPERADOR com {@code pessoa_tipo: "TECNICO"}) só é recusado porque a busca é no cadastro
 * DAQUELE tipo — um {@code existsById} genérico deixaria passar. Mesmo motivo pelo qual a tradução
 * {@code PESSOA_TIPO → papel} ficou em {@link br.leg.senado.nusp.enums.PapelPessoa#dePessoaTipo}.
 */
@Component
@RequiredArgsConstructor
public class PessoaCadastroLookup {

    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;

    /**
     * {@code true} somente quando o cadastro DO TIPO informado tem esse id.
     *
     * <p>Devolve {@code false} — nunca estoura — para id vazio, tipo desconhecido e para o par
     * trocado. Quem chama decide a mensagem da recusa (as duas são 400, mas o texto é do contexto:
     * "Operador/técnico/administrador inválido." no vínculo, "Funcionário …" na marcação).
     */
    public boolean existe(String pessoaId, String pessoaTipo) {
        if (pessoaId == null || pessoaId.isBlank()) return false;
        return switch (normalizarTipo(pessoaTipo)) {
            case "OPERADOR"      -> operadorRepo.existsById(pessoaId);
            case "TECNICO"       -> tecnicoRepo.existsById(pessoaId);
            case "ADMINISTRADOR" -> administradorRepo.existsById(pessoaId);
            default -> false;
        };
    }

    /** O PESSOA_TIPO como as tabelas do Ponto o gravam: sem espaços e em maiúsculas ({@code ""} se nulo). */
    public static String normalizarTipo(String pessoaTipo) {
        return pessoaTipo == null ? "" : pessoaTipo.trim().toUpperCase(Locale.ROOT);
    }
}
