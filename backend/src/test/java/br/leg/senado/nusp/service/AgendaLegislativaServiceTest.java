package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.repository.SalaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unitário do {@link AgendaLegislativaService}: parsing, cache e mapeamento local→sala.
 * Nenhum teste toca a rede real — o {@link HttpClient} é injetado e mockado, com cada
 * fetch casado por URL exata via argThat.
 *
 * Fixtures XML em text blocks: COMISSOES_XML e PLENARIO_XML derivam de respostas reais
 * da API de dados abertos do Senado (a de comissões reduzida a 2 reuniões — um literal
 * Java tem limite de 64 KB); MAPEAMENTO_XML é sintético e serve só às variações de
 * <local> do mapeamento local→sala. Salas do mock espelham o cadastro real (CAD_SALA).
 *
 * Fora do escopo: subscribe/SSE e o agendamento @Scheduled.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgendaLegislativaService — parsing, cache e mapeamento local→sala")
class AgendaLegislativaServiceTest {

    @Mock
    private SalaRepository salaRepository;

    @Mock
    private CessaoSheetService cessaoSheetService;

    @Mock
    private HttpClient httpClient;

    @InjectMocks
    private AgendaLegislativaService service;

    private static final String COMISSAO_API = "https://legis.senado.leg.br/dadosabertos/comissao/agenda/";
    private static final String PLENARIO_API = "https://legis.senado.leg.br/dadosabertos/plenario/agenda/dia/";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String XML_MALFORMADO = "<<< isto nao e um xml valido >>>";

    // ══ Fixtures XML (reais — ver javadoc da classe) ═════════════════════════

    private static final String COMISSOES_XML = """
<?xml version='1.0' encoding='UTF-8'?>
<AgendaReuniao xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
    xsi:noNamespaceSchemaLocation='https://legis.senado.leg.br/dadosabertos/dados/AgendaReuniaov2.xsd'>
    <Metadados>
    <Versao>10/07/2026 09:23:12</Versao>
    <VersaoServico>2</VersaoServico>
    <DataVersaoServico>2020-08-07</DataVersaoServico>
    <DescricaoDataSet>Agenda das Reuniões das Comissões na data informada.</DescricaoDataSet>
    
</Metadados>

<reunioes><reuniao><codigo>14859</codigo><versao>2026-07-08T15:50:52.380</versao><descricao>32ª, Extraordinária - Semipresencial</descricao><titulo>32ª Reunião Extraordinária Semipresencial</titulo><colegiadoCriador><codigo>47</codigo><sigla>CE</sigla><nome>Comissão de Educação e Cultura</nome><siglaCasa>SF</siglaCasa><codigoTipo>21</codigoTipo><descricaoTipo>Comissão Permanente</descricaoTipo></colegiadoCriador><dataInicio>2026-07-08T14:30:00.000</dataInicio><dataInicioFormatadaComObsHorario>08/07/2026 às 14h30</dataInicioFormatadaComObsHorario><isExibirNoPortal>true</isExibirNoPortal><confirmada>false</confirmada><realizada>true</realizada><foiAgendadaAposTerSidoSuspensa>false</foiAgendadaAposTerSidoSuspensa><codigoSituacao>6</codigoSituacao><situacao>Realizada</situacao><status>Realizada</status><secreta>false</secreta><local>Anexo II, Ala Senador Alexandre Costa, Plenário nº 15</local><numReuniaoConjunta>32</numReuniaoConjunta><urlUltimaPautaSimplesPublicada>https://legis.senado.leg.br/sdleg-getter/documento/download/001ac402-4930-410a-8caf-51c1e4cb431c</urlUltimaPautaSimplesPublicada><urlUltimaPautaCheiaPublicada>https://legis.senado.leg.br/sdleg-getter/documento/download/dc06dafc-d40b-4082-a5f0-0c49121e38ae</urlUltimaPautaCheiaPublicada><urlUltimoResultadoPublicado>https://legis.senado.leg.br/sdleg-getter/documento/download/f5f4d49f-72ca-46fc-9e52-24222df2a915</urlUltimoResultadoPublicado><idUltimaPublicacaoPautaSimples>001ac402-4930-410a-8caf-51c1e4cb431c</idUltimaPublicacaoPautaSimples><idUltimaPublicacaoResultado>f5f4d49f-72ca-46fc-9e52-24222df2a915</idUltimaPublicacaoResultado><tipoPresenca>Semipresencial</tipoPresenca><continuacaoCancelada>false</continuacaoCancelada><tipo><codigo>2</codigo><descricao>Extraordinária</descricao><sigla>EXT</sigla></tipo><sessaoLegislativa><codigo>874</codigo><numeroLegislatura>57</numeroLegislatura><numero>4</numero><descricao>57a. Legislatura (2026) - 4a. Sessão Legislativa Ordinária</descricao><inicio>2026-02-02</inicio><fim>2026-12-22</fim></sessaoLegislativa><permitePresencaApp>true</permitePresencaApp><permiteVotacaoSemPresencaFisica>true</permiteVotacaoSemPresencaFisica><possuiVotacaoSecreta>false</possuiVotacaoSecreta><presidente><codigo>6336</codigo><nome>Camilo Sobreira de Santana</nome><nomeParlamentar>Camilo Santana</nomeParlamentar><sexo>M</sexo><cargo>Senador</cargo><nomeComCargo>Senador Camilo Santana</nomeComCargo><nomeComCargoResumido>Sen. Camilo Santana</nomeComCargoResumido><casa>SF</casa><ufPartido>CE</ufPartido><siglaPartido>PT</siglaPartido><partido>Partido dos Trabalhadores</partido><urlFoto>https://www.senado.gov.br/senadores/img/fotos-oficiais/senador6336.jpg</urlFoto><urlPagina>https://www25.senado.leg.br/web/senadores/senador/-/perfil/6336</urlPagina><isSenador>true</isSenador><isDeputado>false</isDeputado><data>2026-07-08T00:00:00.000</data><liderancas><titulo>Líder do Senado Federal</titulo><siglaPartido>PT</siglaPartido><nomePartido>Partido dos Trabalhadores</nomePartido></liderancas></presidente><dataReuniao><codigo>68767</codigo><dataEvento>2026-07-08T15:13:00.000</dataEvento><codigoSituacao>6</codigoSituacao><descricaoSituacao>Realizada</descricaoSituacao><realizada>true</realizada></dataReuniao><dataReuniao><codigo>68766</codigo><dataEvento>2026-07-08T14:58:00.000</dataEvento><codigoSituacao>3</codigoSituacao><descricaoSituacao>Aberta</descricaoSituacao><realizada>false</realizada></dataReuniao><dataReuniao><codigo>68723</codigo><dataEvento>2026-07-08T10:00:00.000</dataEvento><codigoSituacao>1</codigoSituacao><descricaoSituacao>Agendada</descricaoSituacao><realizada>false</realizada></dataReuniao><colegiados><codigo>47</codigo><nome>Comissão de Educação e Cultura</nome><sigla>CE</sigla><siglaCasa>SF</siglaCasa><codigoTipoColegiado>21</codigoTipoColegiado><nomeTipoColegiado>Comissão Permanente</nomeTipoColegiado><numeroReuniao>32</numeroReuniao><isSubcomissao>false</isSubcomissao><composicaoPublica>true</composicaoPublica><publicoPortal>true</publicoPortal></colegiados><partes><codigo>19163</codigo><nome>Eleição</nome><codigoTipo>5</codigoTipo><descricaoTipo>Eleição</descricaoTipo><sequencial>1</sequencial><sequencialFormatado>1ª PARTE</sequencialFormatado><isDeliberativa>false</isDeliberativa><evento><codigo>10402</codigo><finalidade>Eleição de novo Presidente da Comissão de Educação e Cultura para o biênio 2025/2026.</finalidade><isPublicado>true</isPublicado><isRealizado>false</isRealizado><resultadoTexto>Eleito o Senador Camilo Santana como presidente da Comissão de Educação e Cultura.</resultadoTexto></evento></partes><videos><codigo>8051</codigo><ordem>1</ordem><url>https://www.youtube.com/watch?v=Sz9ZDWTzloE</url><dataHoraReuniao>2026-07-08T14:30:00.000</dataHoraReuniao></videos></reuniao><reuniao><codigo>14843</codigo><versao>2026-07-08T16:30:12.407</versao><descricao>41ª, Extraordinária - Semipresencial</descricao><titulo>41ª Reunião Extraordinária Semipresencial</titulo><colegiadoCriador><codigo>40</codigo><sigla>CAS</sigla><nome>Comissão de Assuntos Sociais</nome><siglaCasa>SF</siglaCasa><codigoTipo>21</codigoTipo><descricaoTipo>Comissão Permanente</descricaoTipo></colegiadoCriador><dataInicio>2026-07-08T14:30:00.000</dataInicio><dataInicioFormatadaComObsHorario>08/07/2026 às 14h30</dataInicioFormatadaComObsHorario><isExibirNoPortal>true</isExibirNoPortal><confirmada>false</confirmada><realizada>true</realizada><foiAgendadaAposTerSidoSuspensa>false</foiAgendadaAposTerSidoSuspensa><codigoSituacao>6</codigoSituacao><situacao>Realizada</situacao><status>Realizada</status><secreta>false</secreta><local>Anexo II, Ala Senador Alexandre Costa, Plenário nº 3</local><numReuniaoConjunta>41</numReuniaoConjunta><linkECidadania>https://www12.senado.leg.br/ecidadania/visualizacaoaudiencia?id=39870</linkECidadania><urlUltimaPautaSimplesPublicada>https://legis.senado.leg.br/sdleg-getter/documento/download/147b8300-8818-4f02-ac2f-428ffaee1665</urlUltimaPautaSimplesPublicada><urlUltimaPautaCheiaPublicada>https://legis.senado.leg.br/sdleg-getter/documento/download/f305bdfe-91eb-4568-b431-87fd4af24bc8</urlUltimaPautaCheiaPublicada><urlUltimoResultadoPublicado>https://legis.senado.leg.br/sdleg-getter/documento/download/fc12d6f2-3933-4f49-9651-d4d10dc7617b</urlUltimoResultadoPublicado><idUltimaPublicacaoPautaSimples>147b8300-8818-4f02-ac2f-428ffaee1665</idUltimaPublicacaoPautaSimples><idUltimaPublicacaoResultado>fc12d6f2-3933-4f49-9651-d4d10dc7617b</idUltimaPublicacaoResultado><tipoPresenca>Semipresencial</tipoPresenca><continuacaoCancelada>false</continuacaoCancelada><tipo><codigo>2</codigo><descricao>Extraordinária</descricao><sigla>EXT</sigla></tipo><sessaoLegislativa><codigo>874</codigo><numeroLegislatura>57</numeroLegislatura><numero>4</numero><descricao>57a. Legislatura (2026) - 4a. Sessão Legislativa Ordinária</descricao><inicio>2026-02-02</inicio><fim>2026-12-22</fim></sessaoLegislativa><permitePresencaApp>true</permitePresencaApp><permiteVotacaoSemPresencaFisica>true</permiteVotacaoSemPresencaFisica><possuiVotacaoSecreta>false</possuiVotacaoSecreta><presidente><codigo>5793</codigo><nome>Hiran Manuel Gonçalves da Silva</nome><nomeParlamentar>Dr. Hiran</nomeParlamentar><sexo>M</sexo><cargo>Senador</cargo><nomeComCargo>Senador Dr. Hiran</nomeComCargo><nomeComCargoResumido>Sen. Dr. Hiran</nomeComCargoResumido><casa>SF</casa><ufPartido>RR</ufPartido><siglaPartido>PP</siglaPartido><partido>Progressistas</partido><codigoDeputado>178959</codigoDeputado><urlFoto>https://www.senado.gov.br/senadores/img/fotos-oficiais/senador5793.jpg</urlFoto><urlPagina>https://www25.senado.leg.br/web/senadores/senador/-/perfil/5793</urlPagina><isSenador>true</isSenador><isDeputado>false</isDeputado><data>2026-07-08T00:00:00.000</data><liderancas><titulo>Líder do Senado Federal</titulo><bloco>Bloco Parlamentar Aliança</bloco></liderancas></presidente><dataReuniao><codigo>68769</codigo><dataEvento>2026-07-08T16:21:00.000</dataEvento><codigoSituacao>6</codigoSituacao><descricaoSituacao>Realizada</descricaoSituacao><realizada>true</realizada></dataReuniao><dataReuniao><codigo>68764</codigo><dataEvento>2026-07-08T14:28:00.000</dataEvento><codigoSituacao>3</codigoSituacao><descricaoSituacao>Aberta</descricaoSituacao><realizada>false</realizada></dataReuniao><dataReuniao><codigo>68683</codigo><dataEvento>2026-07-08T14:30:00.000</dataEvento><codigoSituacao>1</codigoSituacao><descricaoSituacao>Agendada</descricaoSituacao><realizada>false</realizada></dataReuniao><colegiados><codigo>40</codigo><nome>Comissão de Assuntos Sociais</nome><sigla>CAS</sigla><siglaCasa>SF</siglaCasa><codigoTipoColegiado>21</codigoTipoColegiado><nomeTipoColegiado>Comissão Permanente</nomeTipoColegiado><numeroReuniao>41</numeroReuniao><isSubcomissao>false</isSubcomissao><composicaoPublica>true</composicaoPublica><publicoPortal>true</publicoPortal></colegiados><partes><codigo>19145</codigo><nome>Audiência Pública Interativa</nome><codigoTipo>1</codigoTipo><descricaoTipo>Audiência Pública Interativa</descricaoTipo><sequencial>1</sequencial><sequencialFormatado>1ª PARTE</sequencialFormatado><isDeliberativa>false</isDeliberativa><evento><codigo>10394</codigo><finalidade>Debater sobre o tema "Cegueira evitável no Brasil: uma responsabilidade compartilhada".</finalidade><isPublicado>true</isPublicado><isRealizado>true</isRealizado><observacoes>A reunião será interativa, transmitida ao vivo e aberta à participação dos interessados por meio do portal e-cidadania, na internet, em senado.leg.br/ecidadania ou pelo telefone da ouvidoria 0800 061 22 11.</observacoes><resultadoTexto>Realizada.</resultadoTexto><resultadoObservacoes>A reunião foi interativa, transmitida ao vivo e aberta à participação dos interessados por meio do portal e-cidadania, na internet, em senado.leg.br/ecidadania ou pelo telefone da Ouvidoria 0800 061 22 11.</resultadoObservacoes><convidados><codigo>43241</codigo><ordem>1</ordem><nome>Camila Carloni Gasparro</nome><sexo>F</sexo><cargo>Coordenadora-Geral substituta de Atenção Especializada do Ministério da Saúde</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>2</codigoSituacao></convidados><convidados><codigo>43243</codigo><ordem>2</ordem><nome>Mauro Goldbaum</nome><sexo>M</sexo><cargo>Secretário-Geral do Conselho Brasileiro de Oftalmologia – CBO</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>2</codigoSituacao></convidados><convidados><codigo>43244</codigo><ordem>3</ordem><nome>Victor Pavarino</nome><sexo>M</sexo><cargo>Oficial Técnico de Segurança Viária e Prevenção de Lesões da Organização Pan-Americana da Saúde - OPAS/OMS</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>4</codigoSituacao></convidados><convidados><codigo>43246</codigo><ordem>4</ordem><nome>Frank Hida</nome><sexo>M</sexo><cargo>Co-Chair América Latina da Agência Internacional para a Prevenção à Cegueira – IAPB</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>2</codigoSituacao></convidados><convidados><codigo>43287</codigo><ordem>5</ordem><nome>Ewésh Yawalapiti Waura</nome><sexo>M</sexo><cargo>Presidente da Associação Terra Indígena Xingu – ATIX</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>2</codigoSituacao></convidados><convidados><codigo>43302</codigo><ordem>6</ordem><nome>Rubens Belfort Mattos Junior</nome><sexo>M</sexo><cargo>Professor Universitário</cargo><representanteDe>Academia Nacional de Medicina</representanteDe><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>4</codigoSituacao></convidados><convidados><codigo>43242</codigo><ordem>7</ordem><nome>Representante</nome><sexo>M</sexo><cargo>Ministério dos Povos Indígenas</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>3</codigoSituacao></convidados><convidados><codigo>43247</codigo><ordem>8</ordem><nome>Representante</nome><sexo>M</sexo><cargo>Articulação Povos Indígenas do Brasil</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia><codigoSituacao>3</codigoSituacao></convidados><participantes><codigo>33736</codigo><ordem>1</ordem><nome>Camila Carloni Gasparro</nome><sexo>F</sexo><cargo>Coordenadora-Geral substituta de Atenção Especializada do Ministério da Saúde</cargo><codigoConvidado>43241</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33734</codigo><ordem>2</ordem><nome>Victor Pavarino</nome><sexo>M</sexo><cargo>Oficial Técnico de Segurança Viária e Prevenção de Lesões da Organização Pan-Americana da Saúde - OPAS/OMS</cargo><apresentacoes><idEcmSenado>9e2285c3-7c0f-4b7d-a5a6-674a45e23cde</idEcmSenado><descricao>Apresentação</descricao><nome>Victor Pavarino.pptx</nome><mimeType>application/vnd.openxmlformats-officedocument.presentationml.presentation</mimeType><linkDownload>https://legis.senado.leg.br/sdleg-getter/documento/download/9e2285c3-7c0f-4b7d-a5a6-674a45e23cde</linkDownload></apresentacoes><codigoConvidado>43244</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33737</codigo><ordem>3</ordem><nome>Mauro Goldbaum</nome><sexo>M</sexo><cargo>Secretário-Geral do Conselho Brasileiro de Oftalmologia – CBO</cargo><apresentacoes><idEcmSenado>11c73540-10c5-46b5-97fb-3c6fcaad4d23</idEcmSenado><descricao>Apresentação</descricao><nome>Mauro.pptx</nome><mimeType>application/vnd.openxmlformats-officedocument.presentationml.presentation</mimeType><linkDownload>https://legis.senado.leg.br/sdleg-getter/documento/download/11c73540-10c5-46b5-97fb-3c6fcaad4d23</linkDownload></apresentacoes><codigoConvidado>43243</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33735</codigo><ordem>4</ordem><nome>Rubens Belfort Mattos Junior</nome><sexo>M</sexo><cargo>Professor Universitário</cargo><representanteDe>Academia Nacional de Medicina</representanteDe><codigoConvidado>43302</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33739</codigo><ordem>5</ordem><nome>Ewésh Yawalapiti Waura</nome><sexo>M</sexo><cargo>Presidente da Associação Terra Indígena Xingu – ATIX</cargo><codigoConvidado>43287</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33738</codigo><ordem>6</ordem><nome>Frank Hida</nome><sexo>M</sexo><cargo>Co-Chair América Latina da Agência Internacional para a Prevenção à Cegueira – IAPB</cargo><apresentacoes><idEcmSenado>9ee70eb6-db5b-4834-9900-080fdbc7246d</idEcmSenado><descricao>Vídeo</descricao><nome>Frank Hida vídeo - IAPB.mp4</nome><mimeType>video/mp4</mimeType><linkDownload>https://legis.senado.leg.br/sdleg-getter/documento/download/9ee70eb6-db5b-4834-9900-080fdbc7246d</linkDownload></apresentacoes><codigoConvidado>43246</codigoConvidado><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><participantes><codigo>33740</codigo><ordem>7</ordem><nome>Louise Christina Gonçalves Vasconcelos</nome><sexo>F</sexo><cargo>Presidente da Sociedade Brasileira de Oftalmologia para a Região Norte</cargo><isDepoentePresente>false</isDepoentePresente><isPorVideoConferencia>false</isPorVideoConferencia></participantes><domasRelacionados><finalidade>C</finalidade><descricaoFinalidade>Solicitação de realização de Audiência Pública Interativa</descricaoFinalidade><ordem>1</ordem><doma><idDoma>10238545</idDoma><idProcesso>9059057</idProcesso><autoria>Senador Dr. Hiran (PP/RR)</autoria><idTipoDoma>87</idTipoDoma><numeroSedol>SF267265626353</numeroSedol><descricaoTipoDoma>Requerimento</descricaoTipoDoma><siglaTipoDoma>REQUERIMENTO</siglaTipoDoma><ementa>Requer, nos termos do art. 58, § 2º, II, da Constituição Federal e do art. 93, II, do Regimento Interno do Senado Federal, a realização de audiência pública, com o objetivo de debater sobre o tema "Cegueira evitável no Brasil: uma responsabilidade compartilhada".</ementa><dataApresentacao>2026-06-08T10:08:33.000</dataApresentacao><sigla>REQ</sigla><numero>00063</numero><ano>2026</ano><identificacao>REQ 63/2026 - CAS</identificacao><identificacaoPorExtenso>Requerimento da Comissão de Assuntos Sociais n° 63, de 2026</identificacaoPorExtenso><idConteudoInformacional>7660176</idConteudoInformacional><codigoMateria>174456</codigoMateria><siglaPorExtenso>Requerimento</siglaPorExtenso><complementar>false</complementar><consolidacao>false</consolidacao><proposicao>true</proposicao><identificado>true</identificado><textos><idDoma>10238545</idDoma><idProcesso>9059057</idProcesso><identificacao>Requerimento</identificacao><descricaoTipoDoma>Requerimento</descricaoTipoDoma><siglaTipoDoma>REQUERIMENTO</siglaTipoDoma><dataAtualizacao>2026-06-08</dataAtualizacao><codigoColegiado>40</codigoColegiado><conteudoInformacional>REQ 63/2026 - CAS</conteudoInformacional><urlDownload>https://legis.senado.leg.br/sdleg-getter/documento?dm=10238545</urlDownload><descricao>Requer, nos termos do art. 58, § 2º, II, da Constituição Federal e do art. 93, II, do Regimento Interno do Senado Federal, a realização de audiência pública, com o objetivo de debater sobre o tema "Cegueira evitável no Brasil: uma responsabilidade compartilhada".</descricao></textos><textos><idDoma>10248025</idDoma><identificacao>Listagem ou relatório descritivo</identificacao><descricaoTipoDoma>Listagem ou relatório descritivo</descricaoTipoDoma><siglaTipoDoma>LISTAGEM_RELATORIO</siglaTipoDoma><dataAtualizacao>2026-06-17</dataAtualizacao><codigoColegiado>40</codigoColegiado><urlDownload>https://legis.senado.leg.br/sdleg-getter/documento?dm=10248025</urlDownload><descricao>Listagem ou relatório descritivo-Lista de Presença da reunião da 36ª Reunião CAS</descricao></textos><autorias><ordem>1</ordem><siglaTipo>SENADOR</siglaTipo><descricaoTipo>Senador</descricaoTipo><isAutoriaComOutros>false</isAutoriaComOutros><nome>Dr. Hiran</nome><tratamento>Senador</tratamento><parlamentar><codigo>5793</codigo><nome>Hiran Manuel Gonçalves da Silva</nome><nomeParlamentar>Dr. Hiran</nomeParlamentar><sexo>M</sexo><cargo>Senador</cargo><nomeComCargo>Senador Dr. Hiran</nomeComCargo><nomeComCargoResumido>Sen. Dr. Hiran</nomeComCargoResumido><casa>SF</casa><ufPartido>RR</ufPartido><siglaPartido>PP</siglaPartido><partido>Progressistas</partido><codigoDeputado>178959</codigoDeputado><urlFoto>https://www.senado.gov.br/senadores/img/fotos-oficiais/senador5793.jpg</urlFoto><urlPagina>https://www25.senado.leg.br/web/senadores/senador/-/perfil/5793</urlPagina><isSenador>true</isSenador><isDeputado>false</isDeputado><data>2026-06-08T00:00:00.000</data><liderancas><titulo>Líder do Senado Federal</titulo><bloco>Bloco Parlamentar Aliança</bloco></liderancas></parlamentar></autorias></doma></domasRelacionados></evento></partes><videos><codigo>8052</codigo><ordem>1</ordem><url>https://www.youtube.com/watch?v=MWBcsj20q8Q</url><dataHoraReuniao>2026-07-08T14:30:00.000</dataHoraReuniao></videos></reuniao></reunioes></AgendaReuniao>
""";

    private static final String PLENARIO_XML = """
<?xml version='1.0' encoding='UTF-8'?>
<AgendaPlenario xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
    xsi:noNamespaceSchemaLocation='https://legis.senado.leg.br/dadosabertos/dados/AgendaPlenariov2.xsd'>
    <Metadados>
    <Versao>10/07/2026 09:25:22</Versao>
    <VersaoServico>2</VersaoServico>
    <DataVersaoServico>2019-10-15</DataVersaoServico>
    <DescricaoDataSet>Retorna a Agenda do dia nos Plenários do Senado e Congresso.</DescricaoDataSet>
    
</Metadados>

    <Sessoes><Sessao><DiaUtil>02</DiaUtil><Data>2026-07-02</Data><DiaSemana>quinta-feira</DiaSemana><Mes>Julho</Mes><Horario>15 horas </Horario><Hora>15:00</Hora><NumeroSessao>18ª SESSÃO </NumeroSessao><TipoSessao>SESSÃO SOLENE </TipoSessao><LocalSessao>Plenário do Senado Federal</LocalSessao><CodigoSessao>562017</CodigoSessao><Casa>CN</Casa><SessaoLegislativa>4</SessaoLegislativa><Legislatura>57</Legislatura><CodigoSituacaoSessao>40</CodigoSituacaoSessao><SituacaoSessao>Encerrada</SituacaoSessao><Realizada><Status>Sim</Status></Realizada><PautaConfirmada>Sim</PautaConfirmada><TipoPresenca>P</TipoPresenca><DescricaoTipoPresenca>Presencial</DescricaoTipoPresenca><Evento><DescricaoTipoEvento>Sessão do Congresso Nacional</DescricaoTipoEvento><Data>2 de julho de 2026</Data><DiaSemana>quinta-feira</DiaSemana><Horario>às 15 horas</Horario><TipoSessao>(SESSÃO SOLENE ) </TipoSessao><DescricaoEvento>Destinada a homenagear os 170 anos do Corpo de Bombeiros Militar do Distrito Federal ( CBMDF). Requerentes : Senador Izalci Lucas (PL/DF); Senador Humberto Costa (PT/PE) e Deputado Federal Alberto Fraga (PL/DF). Req 11/2026-Mesa.</DescricaoEvento><FimInscricao>(Até as 8h50min de 2.7.2026)</FimInscricao><IndicadorPublicaOrador>0</IndicadorPublicaOrador></Evento></Sessao><Sessao><DiaUtil>02</DiaUtil><Data>2026-07-02</Data><DiaSemana>quinta-feira</DiaSemana><Mes>Julho</Mes><Horario>16 horas  e 30 minutos</Horario><Hora>16:30</Hora><NumeroSessao>93ª SESSÃO </NumeroSessao><TipoSessao>SESSÃO DELIBERATIVA EXTRAORDINÁRIA </TipoSessao><LocalSessao>Plenário do Senado Federal</LocalSessao><CodigoSessao>577474</CodigoSessao><Casa>SF</Casa><SessaoLegislativa>4</SessaoLegislativa><Legislatura>57</Legislatura><CodigoSituacaoSessao>40</CodigoSituacaoSessao><SituacaoSessao>Encerrada</SituacaoSessao><Realizada><Status>Sim</Status></Realizada><PautaConfirmada>Sim</PautaConfirmada><TipoPresenca>S</TipoPresenca><DescricaoTipoPresenca>Semipresencial</DescricaoTipoPresenca><Oradores><TipoOrador><DescricaoTipoOrador>Oradores Inscritos</DescricaoTipoOrador><CodigoTipoOrador>13</CodigoTipoOrador><OradorSessao><Orador><Ordem>1</Ordem><Parlamentar>Senador Eduardo Girão</Parlamentar><IndicadorCancelado>N</IndicadorCancelado></Orador><Orador><Ordem>3</Ordem><Parlamentar>Senador Magno Malta</Parlamentar><IndicadorCancelado>N</IndicadorCancelado></Orador><Orador><Ordem>4</Ordem><Parlamentar>Senadora Damares Alves</Parlamentar><IndicadorCancelado>N</IndicadorCancelado></Orador></OradorSessao></TipoOrador></Oradores><Evento><DescricaoTipoEvento>Sessão Deliberativa Extraordinária Semipresencial</DescricaoTipoEvento><Data>2 de julho de 2026</Data><DiaSemana>quinta-feira</DiaSemana><Horario>às 16h30min</Horario><TipoSessao>(SESSÃO DELIBERATIVA EXTRAORDINÁRIA ) </TipoSessao><DescricaoEvento>Destinada à deliberação da Medida Provisória nº 1.339, de 2026.</DescricaoEvento><FimInscricao>(Até as 9h23min de 2.7.2026)</FimInscricao><IndicadorPublicaOrador>0</IndicadorPublicaOrador></Evento><Materias><Materia><CodigoMateria>172954</CodigoMateria><Identificacao>MEDIDA PROVISÓRIA Nº 1.339, DE 2026
</Identificacao><SiglaMateria>MPV</SiglaMateria><NumeroMateria>01339</NumeroMateria><AnoMateria>2026</AnoMateria><DescricaoIdentificacaoMateria>MPV 1339/2026</DescricaoIdentificacaoMateria><SiglaCasaIniciadora>CN</SiglaCasaIniciadora><Ementa>Abre crédito extraordinário em favor do Ministério da Integração e do Desenvolvimento Regional, no valor de R$ 266.512.000,00, para os fins que especifica.</Ementa><Parecer>Parecer nº 1, de 2026, da Comissão Mista de Planos, Orçamentos Públicos e Fiscalização, Relator: Senador Rogério Carvalho, Relator Revisor: Deputado Zé Vitor, favorável à Medida Provisória, na forma proposta pelo Poder Executivo.

(Prazo final prorrogado: 06/07/2026)</Parecer><Apreciacao>Discussão, em turno único</Apreciacao><ApreciacaoPapeleta>Discussão, em turno único, da Medida Provisória nº 1.339, de 2026, que</ApreciacaoPapeleta><EmentaPapeleta>abre crédito extraordinário em favor do Ministério da Integração e do Desenvolvimento Regional, no valor de R$ 266.512.000,00, para os fins que especifica.</EmentaPapeleta><NomeAutor>Presidência da República</NomeAutor><TipoPauta>E</TipoPauta><DescricaoTipoPauta>Extrapauta</DescricaoTipoPauta><SequenciaOrdem>1</SequenciaOrdem></Materia></Materias></Sessao></Sessoes>
</AgendaPlenario>
""";

    /** Sintético — estrutura espelhada da reunião real 14859, variando só o {@code <local>}. */
    private static final String MAPEAMENTO_XML = """
<?xml version='1.0' encoding='UTF-8'?>
<AgendaReuniao>
<reunioes>
<reuniao><codigo>M1</codigo><titulo>Reunião de teste M1</titulo><colegiadoCriador><sigla>CE</sigla><nome>Comissão de Educação e Cultura</nome></colegiadoCriador><dataInicio>2026-07-08T09:00:00.000</dataInicio><situacao>Agendada</situacao><local>Anexo II, Ala Senador Alexandre Costa, Plenário nº 15</local><tipoPresenca>Semipresencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M2</codigo><titulo>Reunião de teste M2</titulo><colegiadoCriador><sigla>ECT</sigla><nome>Escritório Corporativo de Teste</nome></colegiadoCriador><dataInicio>2026-07-08T10:00:00.000</dataInicio><situacao>Agendada</situacao><local>Sala 7 do Interlegis</local><tipoPresenca>Presencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M3</codigo><titulo>Reunião de teste M3</titulo><colegiadoCriador><sigla>CDH</sigla><nome>Comissão de Direitos Humanos</nome></colegiadoCriador><dataInicio>2026-07-08T11:00:00.000</dataInicio><situacao>Agendada</situacao><local>Auditório Petrônio Portella</local><tipoPresenca>Presencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M4</codigo><titulo>Reunião de teste M4</titulo><colegiadoCriador><sigla>CDH</sigla><nome>Comissão de Direitos Humanos</nome></colegiadoCriador><dataInicio>2026-07-08T12:00:00.000</dataInicio><situacao>Agendada</situacao><local>Auditorio Petronio Portela</local><tipoPresenca>Presencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M5</codigo><titulo>Reunião de teste M5</titulo><colegiadoCriador><sigla>MESA</sigla><nome>Mesa do Congresso</nome></colegiadoCriador><dataInicio>2026-07-08T13:00:00.000</dataInicio><situacao>Agendada</situacao><local>Salão Negro do Congresso Nacional</local><tipoPresenca>Presencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M6</codigo><titulo>Reunião de teste M6</titulo><colegiadoCriador><sigla>CE</sigla><nome>Comissão de Educação e Cultura</nome></colegiadoCriador><dataInicio>2026-07-08T14:00:00.000</dataInicio><situacao>Agendada</situacao><local>Anexo II, Ala Senador Nilo Coelho, Plenário nº 99</local><tipoPresenca>Semipresencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
<reuniao><codigo>M7</codigo><titulo>Reunião de teste M7</titulo><colegiadoCriador><sigla>CAS</sigla><nome>Comissão de Assuntos Sociais</nome></colegiadoCriador><dataInicio>2026-07-08T15:00:00.000</dataInicio><situacao>Agendada</situacao><local>Anexo II, Ala Senador Nilo Coelho, Plenário nº 3</local><tipoPresenca>Semipresencial</tipoPresenca><tipo><codigo>90</codigo><descricao>Extraordinária</descricao></tipo></reuniao>
</reunioes>
</AgendaReuniao>
""";

    /**
     * Respostas VÁLIDAS e vazias (dia sem reunião/sessão — feriado, recesso). São o contraponto
     * das falhas: aqui a API respondeu, então a agenda realmente esvaziou e o cache deve refletir.
     */
    private static final String COMISSOES_VAZIO_XML = """
<?xml version='1.0' encoding='UTF-8'?>
<AgendaReuniao><reunioes></reunioes></AgendaReuniao>
""";

    private static final String PLENARIO_VAZIO_XML = """
<?xml version='1.0' encoding='UTF-8'?>
<AgendaPlenario><Sessoes></Sessoes></AgendaPlenario>
""";

    // ══ Helpers ══════════════════════════════════════════════════════════════

    private Sala sala(int id, String nome) {
        Sala s = new Sala();
        s.setId(id);
        s.setNome(nome);
        return s;
    }

    /** Cadastro real do homolog (10/07/2026), reduzido às salas relevantes aos casos. */
    private void stubSalas() {
        when(salaRepository.findAtivasOrdenadas()).thenReturn(List.of(
                sala(1, "Auditório Petrônio Portella"),
                sala(2, "Plenário Principal"),   // sem número: fora do mapa numérico
                sala(4, "Plenário 03"),
                sala(9, "Plenário 15"),
                sala(11, "Demais Salas")));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> respostaOk(String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> respostaErro(int status) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        // body() nunca é lido em não-2xx (httpGet devolve null antes) — só o status é stubado
        when(resp.statusCode()).thenReturn(status);
        return resp;
    }

    /**
     * Stub do send casado pela URL EXATA (nunca any()). Atenção: URL não casada NÃO
     * explode — o send devolve null, o NPE morre no catch de httpGet e o fetch devolve lista
     * vazia SILENCIOSA. Por isso, todo teste cujo valor esperado é "vazio"/"cache preservado"
     * DEVE ter pré-condição de cache cheio ou asserção de conteúdo (nunca só o vazio).
     */
    @SafeVarargs
    private void stubHttp(String url, HttpResponse<String> primeira, HttpResponse<String>... seguintes) throws Exception {
        when(httpClient.<String>send(argThat((HttpRequest req) -> req != null && url.equals(req.uri().toString())), any()))
                .thenReturn(primeira, seguintes);
    }

    // Risco aceito (ínfimo e nunca falso verde): se a suíte cruzar a meia-noite entre este
    // now() e o do SUT, o stub de URL não casa e o teste falha RUIDOSAMENTE (assert/strict
    // stubs). Blindar exigiria Clock injetado — fora do refactor autorizado pelo D7.
    private String hojeFmt() {
        return LocalDate.now().format(DATE_FMT);
    }

    private Map<String, Object> cessao(int salaId, String titulo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tipo", "cessao");
        c.put("sala_id", salaId);
        c.put("titulo", titulo);
        return c;
    }

    /** Poll com as duas fontes respondendo as fixtures reais de hoje. */
    private void pollComDadosReais() throws Exception {
        stubSalas();
        stubHttp(COMISSAO_API + hojeFmt(), respostaOk(COMISSOES_XML));
        stubHttp(PLENARIO_API + hojeFmt(), respostaOk(PLENARIO_XML));
        service.poll();
    }

    // ── Parse esperado das fixtures reais (valores conferidos 1:1 no XML) ────

    /** Reunião 14859 (CE) — local "Plenário nº 15" → sala 9 do cadastro. */
    private Map<String, Object> reuniaoCe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "comissao");
        m.put("codigo", "14859");
        m.put("titulo", "32ª Reunião Extraordinária Semipresencial");
        m.put("situacao", "Realizada");
        m.put("comissao_sigla", "CE");
        m.put("comissao_nome", "Comissão de Educação e Cultura");
        m.put("horario", "14:30");
        m.put("local", "Anexo II, Ala Senador Alexandre Costa, Plenário nº 15");
        m.put("sala_id", 9);
        m.put("tipo_descricao", "Extraordinária");
        m.put("tipo_presenca", "Semipresencial");
        return m;
    }

    /** Reunião 14843 (CAS) — local "Plenário nº 3" → sala 4 ("Plenário 03"). */
    private Map<String, Object> reuniaoCas() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "comissao");
        m.put("codigo", "14843");
        m.put("titulo", "41ª Reunião Extraordinária Semipresencial");
        m.put("situacao", "Realizada");
        m.put("comissao_sigla", "CAS");
        m.put("comissao_nome", "Comissão de Assuntos Sociais");
        m.put("horario", "14:30");
        m.put("local", "Anexo II, Ala Senador Alexandre Costa, Plenário nº 3");
        m.put("sala_id", 4);
        m.put("tipo_descricao", "Extraordinária");
        m.put("tipo_presenca", "Semipresencial");
        return m;
    }

    /** Sessão 577474 (Casa=SF) da fixture do plenário; a 562017 (Casa=CN) é descartada. */
    private Map<String, Object> sessaoPlenarioSf() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", "577474");
        m.put("titulo", "93ª Sessão Deliberativa Extraordinária");
        m.put("horario", "16:30");
        m.put("local", "Plenário do Senado Federal");
        m.put("situacao", "Encerrada");
        m.put("tipo_descricao", "Sessão Plenária");
        m.put("tipo_presenca", "Semipresencial");
        m.put("descricao", "Destinada à deliberação da Medida Provisória nº 1.339, de 2026.");
        return m;
    }

    // ══ Casos ════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Caches antes do primeiro poll")
    class CachesAntesDoPrimeiroPoll {

        @Test
        @DisplayName("getAgendaComissoes — antes do primeiro poll o cache é vazio; só as cessões aparecem")
        void getAgendaComissoes_antesDoPoll_soCessoes() {
            when(cessaoSheetService.getCessoes()).thenReturn(List.of(cessao(5, "SGM")));

            List<Map<String, Object>> result = service.getAgendaComissoes();

            assertEquals(List.of(cessao(5, "SGM")), result);
            verifyNoInteractions(httpClient);
        }

        @Test
        @DisplayName("getAgendaPlenario — antes do primeiro poll devolve lista vazia sem tocar rede ou cessões")
        void getAgendaPlenario_antesDoPoll_vazio() {
            List<Map<String, Object>> result = service.getAgendaPlenario();

            assertTrue(result.isEmpty());
            verifyNoInteractions(httpClient, cessaoSheetService);
        }

        @Test
        @DisplayName("getAgendaPorSala — antes do primeiro poll devolve só as cessões da sala")
        void getAgendaPorSala_antesDoPoll_soCessoesDaSala() {
            when(cessaoSheetService.getCessoesPorSala(9)).thenReturn(List.of(cessao(9, "Cessão P15")));

            List<Map<String, Object>> result = service.getAgendaPorSala(9);

            assertEquals(List.of(cessao(9, "Cessão P15")), result);
            verifyNoInteractions(httpClient);
        }
    }

    @Nested
    @DisplayName("poll — popula os caches com o parse do XML real")
    class PollPopulaCaches {

        @Test
        @DisplayName("poll — cache de comissões recebe o parse exato das 2 reuniões reais (campo a campo)")
        void poll_populaCacheComissoes_parseExato() throws Exception {
            pollComDadosReais();
            when(cessaoSheetService.getCessoes()).thenReturn(List.of(cessao(5, "SGM")));

            List<Map<String, Object>> result = service.getAgendaComissoes();

            assertEquals(List.of(reuniaoCe(), reuniaoCas(), cessao(5, "SGM")), result);
        }

        @Test
        @DisplayName("poll — plenário: sessão Casa=SF entra com parse exato; Casa=CN é descartada")
        void poll_populaCachePlenario_filtraCasaSF() throws Exception {
            pollComDadosReais();

            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }

        @Test
        @DisplayName("getAgendaPorSala — filtra o cache pelo sala_id resolvido no mapeamento")
        void getAgendaPorSala_filtraPeloMapeamento() throws Exception {
            pollComDadosReais();
            when(cessaoSheetService.getCessoesPorSala(9)).thenReturn(List.of(cessao(9, "Cessão P15")));
            when(cessaoSheetService.getCessoesPorSala(4)).thenReturn(List.of());

            assertEquals(List.of(reuniaoCe(), cessao(9, "Cessão P15")), service.getAgendaPorSala(9));
            assertEquals(List.of(reuniaoCas()), service.getAgendaPorSala(4));
        }

        @Test
        @DisplayName("poll — contrato da requisição HTTP: GET, URL com a data de hoje, headers e timeout")
        void poll_contratoDaRequisicaoHttp() throws Exception {
            pollComDadosReais();

            verify(httpClient).send(argThat((HttpRequest req) ->
                    req != null
                            && "GET".equals(req.method())
                            && (COMISSAO_API + hojeFmt()).equals(req.uri().toString())
                            && "application/xml, text/html, */*".equals(req.headers().firstValue("Accept").orElse(""))
                            && "NUSP-SenadoApp/1.0".equals(req.headers().firstValue("User-Agent").orElse(""))
                            && Optional.of(Duration.ofSeconds(15)).equals(req.timeout())), any());
            verify(httpClient).send(argThat((HttpRequest req) ->
                    req != null && (PLENARIO_API + hojeFmt()).equals(req.uri().toString())), any());
            verifyNoMoreInteractions(httpClient);
        }
    }

    @Nested
    @DisplayName("Falhas de fetch — os 4 quadrantes fonte×falha preservam o cache")
    class FalhasDeFetch {

        /** Deixa os dois caches populados e prepara a resposta SEGUINTE de cada fonte. */
        private void primeiroPollOkSegundoCom(HttpResponse<String> comissoes2,
                                              HttpResponse<String> plenario2) throws Exception {
            stubSalas();
            stubHttp(COMISSAO_API + hojeFmt(), respostaOk(COMISSOES_XML), comissoes2);
            stubHttp(PLENARIO_API + hojeFmt(), respostaOk(PLENARIO_XML), plenario2);
            service.poll();
            // sanidade: caches cheios antes do 2º poll (sem isso, "vazio depois" seria ambíguo)
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());
            assertEquals(2, service.getAgendaComissoes().size(), "pré-condição: cache de comissões populado");
            assertEquals(1, service.getAgendaPlenario().size(), "pré-condição: cache de plenário populado");
        }

        @Test
        @DisplayName("comissões — XML malformado no poll seguinte preserva o cache anterior (catch do parse)")
        void comissoes_xmlMalformado_preservaCacheAnterior() throws Exception {
            primeiroPollOkSegundoCom(respostaOk(XML_MALFORMADO), respostaOk(PLENARIO_XML));

            service.poll();

            assertEquals(List.of(reuniaoCe(), reuniaoCas()), service.getAgendaComissoes());
        }

        @Test
        @DisplayName("comissões: HTTP 500 preserva o cache anterior (falha ≠ agenda vazia)")
        void comissoes_http500_preservaCacheAnterior() throws Exception {
            // httpGet devolve null em não-2xx; fetchComissoes sinaliza
            // FALHA (null) e atualizarComissoes não publica nada — o cache (e o hash) ficam de pé.
            primeiroPollOkSegundoCom(respostaErro(500), respostaOk(PLENARIO_XML));

            service.poll();

            assertEquals(List.of(reuniaoCe(), reuniaoCas()), service.getAgendaComissoes());
        }

        @Test
        @DisplayName("comissões: exceção de rede no send preserva o cache anterior")
        void comissoes_excecaoDeRede_preservaCacheAnterior() throws Exception {
            // Cenário real: PKIX/TLS intermitente contra legis.senado.leg.br —
            // o cache oscilava a cada poll e o SSE difundia agenda vazia.
            stubSalas();
            stubHttp(PLENARIO_API + hojeFmt(), respostaOk(PLENARIO_XML), respostaOk(PLENARIO_XML));
            HttpResponse<String> comissoesOk = respostaOk(COMISSOES_XML);
            when(httpClient.<String>send(argThat((HttpRequest req) ->
                    req != null && (COMISSAO_API + hojeFmt()).equals(req.uri().toString())), any()))
                    .thenReturn(comissoesOk)
                    .thenThrow(new java.io.IOException("PKIX path building failed"));
            service.poll();
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());
            assertEquals(2, service.getAgendaComissoes().size(), "pré-condição: cache populado");

            service.poll();

            assertEquals(List.of(reuniaoCe(), reuniaoCas()), service.getAgendaComissoes());
        }

        @Test
        @DisplayName("plenário: XML malformado preserva o cache anterior")
        void plenario_xmlMalformado_preservaCacheAnterior() throws Exception {
            primeiroPollOkSegundoCom(respostaOk(COMISSOES_XML), respostaOk(XML_MALFORMADO));

            service.poll();

            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }

        @Test
        @DisplayName("plenário: HTTP 500 preserva o cache anterior")
        void plenario_http500_preservaCacheAnterior() throws Exception {
            primeiroPollOkSegundoCom(respostaOk(COMISSOES_XML), respostaErro(500));

            service.poll();

            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }

        @Test
        @DisplayName("plenário: exceção de rede no send preserva o cache anterior (4º quadrante)")
        void plenario_excecaoDeRede_preservaCacheAnterior() throws Exception {
            stubSalas();
            stubHttp(COMISSAO_API + hojeFmt(), respostaOk(COMISSOES_XML), respostaOk(COMISSOES_XML));
            HttpResponse<String> plenarioOk = respostaOk(PLENARIO_XML);
            when(httpClient.<String>send(argThat((HttpRequest req) ->
                    req != null && (PLENARIO_API + hojeFmt()).equals(req.uri().toString())), any()))
                    .thenReturn(plenarioOk)
                    .thenThrow(new java.io.IOException("Connection reset"));
            service.poll();
            assertEquals(1, service.getAgendaPlenario().size(), "pré-condição: cache populado");

            service.poll();

            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }

        // ── A contraprova: "sucesso com zero itens" NÃO é falha e DEVE zerar o cache ──
        // Sem estes dois casos, "preservar o cache" poderia ser implementado ignorando toda
        // resposta vazia — e uma agenda realmente vazia (feriado, recesso) nunca mais limparia
        // o painel. É a distinção que importa: FALHA preserva, resposta válida publica.

        @Test
        @DisplayName("comissões: XML válido SEM reuniões é sucesso → cache atualizado para vazio")
        void comissoes_respostaValidaVazia_atualizaCacheParaVazio() throws Exception {
            primeiroPollOkSegundoCom(respostaOk(COMISSOES_VAZIO_XML), respostaOk(PLENARIO_XML));

            service.poll();

            assertEquals(List.of(), service.getAgendaComissoes());
        }

        @Test
        @DisplayName("plenário: XML válido SEM sessões é sucesso → cache atualizado para vazio")
        void plenario_respostaValidaVazia_atualizaCacheParaVazio() throws Exception {
            primeiroPollOkSegundoCom(respostaOk(COMISSOES_XML), respostaOk(PLENARIO_VAZIO_XML));

            service.poll();

            assertEquals(List.of(), service.getAgendaPlenario());
        }

        // ── O limite da preservação: o cache vale para o DIA em que foi buscado ──
        // Preservar através da meia-noite serviria a agenda de ONTEM como se fosse a de hoje — com o
        // SSE carimbando "atualizado agora". A virada do dia só é exercitável porque pollParaDia(dia)
        // recebe o dia como parâmetro (o poll() de produção passa LocalDate.now()).

        @Test
        @DisplayName("falha DEPOIS da virada do dia: o cache do dia anterior é descartado, não servido como hoje")
        void falhaAposViradaDoDia_descartaCacheDeOntem() throws Exception {
            String ontem = "20260710";
            String hoje = "20260711";
            stubSalas();
            stubHttp(COMISSAO_API + ontem, respostaOk(COMISSOES_XML));
            stubHttp(PLENARIO_API + ontem, respostaOk(PLENARIO_XML));
            stubHttp(COMISSAO_API + hoje, respostaErro(500));
            stubHttp(PLENARIO_API + hoje, respostaErro(500));
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());

            service.pollParaDia(ontem);
            assertEquals(2, service.getAgendaComissoes().size(), "pré-condição: cache do dia anterior populado");
            assertEquals(1, service.getAgendaPlenario().size());

            service.pollParaDia(hoje);   // API fora do ar na virada

            assertEquals(List.of(), service.getAgendaComissoes(), "a agenda de ontem não pode ser servida como a de hoje");
            assertEquals(List.of(), service.getAgendaPlenario());
        }

        @Test
        @DisplayName("falha NO MESMO dia do cache: preserva o cache anterior")
        void falhaNoMesmoDia_preserva() throws Exception {
            String hoje = "20260711";
            stubSalas();
            stubHttp(COMISSAO_API + hoje, respostaOk(COMISSOES_XML), respostaErro(500));
            stubHttp(PLENARIO_API + hoje, respostaOk(PLENARIO_XML), respostaErro(500));
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());

            service.pollParaDia(hoje);
            service.pollParaDia(hoje);

            assertEquals(List.of(reuniaoCe(), reuniaoCas()), service.getAgendaComissoes());
            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }

        @Test
        @DisplayName("cache descartado na virada volta a ser publicado assim que a API responde")
        void aposDescarte_proximoSucessoRepublica() throws Exception {
            String ontem = "20260710";
            String hoje = "20260711";
            stubSalas();
            stubHttp(COMISSAO_API + ontem, respostaOk(COMISSOES_XML));
            stubHttp(PLENARIO_API + ontem, respostaOk(PLENARIO_XML));
            stubHttp(COMISSAO_API + hoje, respostaErro(500), respostaOk(COMISSOES_XML));
            stubHttp(PLENARIO_API + hoje, respostaErro(500), respostaOk(PLENARIO_XML));
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());

            service.pollParaDia(ontem);
            service.pollParaDia(hoje);                       // falha → descarta o cache de ontem
            assertEquals(List.of(), service.getAgendaComissoes());

            service.pollParaDia(hoje);                       // API volta

            // O hash foi zerado junto com o cache: o conteúdo idêntico ao de ontem volta a ser publicado
            // (sem o reset, o hash antigo bloquearia a republicação e a agenda ficaria vazia o dia todo).
            assertEquals(List.of(reuniaoCe(), reuniaoCas()), service.getAgendaComissoes());
            assertEquals(List.of(sessaoPlenarioSf()), service.getAgendaPlenario());
        }
    }

    @Nested
    @DisplayName("Fetch sob demanda — getAgendaParaData / getAgendaPlenarioParaData")
    class FetchSobDemanda {

        @Test
        @DisplayName("getAgendaParaData — data ≠ hoje faz fetch da data e NÃO toca o cache de hoje")
        void getAgendaParaData_outraData_fetchSemTocarCache() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubSalas();
            stubHttp(COMISSAO_API + data.format(DATE_FMT), respostaOk(COMISSOES_XML));
            when(cessaoSheetService.fetchCessoesParaData(data)).thenReturn(List.of(cessao(11, "Cessão antiga")));

            List<Map<String, Object>> result = service.getAgendaParaData(data, null);

            assertEquals(List.of(reuniaoCe(), reuniaoCas(), cessao(11, "Cessão antiga")), result);
            // cache de hoje intocado: a agenda "de hoje" continua vazia e nenhum fetch extra ocorreu
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());
            assertTrue(service.getAgendaComissoes().isEmpty());
            verify(httpClient, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("getAgendaParaData — com salaId filtra comissões E cessões da data pela sala")
        void getAgendaParaData_outraData_filtraPorSala() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubSalas();
            stubHttp(COMISSAO_API + data.format(DATE_FMT), respostaOk(COMISSOES_XML));
            when(cessaoSheetService.fetchCessoesParaData(data))
                    .thenReturn(List.of(cessao(9, "Cessão P15"), cessao(11, "Cessão outra sala")));

            List<Map<String, Object>> result = service.getAgendaParaData(data, 9);

            assertEquals(List.of(reuniaoCe(), cessao(9, "Cessão P15")), result);
        }

        @Test
        @DisplayName("getAgendaParaData — data = hoje lê o cache e as cessões de hoje, sem nenhum HTTP")
        void getAgendaParaData_hoje_leCacheSemHttp() {
            stubSalas();
            when(cessaoSheetService.getCessoes()).thenReturn(List.of(cessao(5, "SGM")));

            List<Map<String, Object>> result = service.getAgendaParaData(LocalDate.now(), null);

            assertEquals(List.of(cessao(5, "SGM")), result);
            verifyNoInteractions(httpClient);
            verify(cessaoSheetService, never()).fetchCessoesParaData(any());
        }

        @Test
        @DisplayName("getAgendaPlenarioParaData — data ≠ hoje faz fetch da data e NÃO toca o cache de hoje")
        void getAgendaPlenarioParaData_outraData_fetchSemTocarCache() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubHttp(PLENARIO_API + data.format(DATE_FMT), respostaOk(PLENARIO_XML));

            List<Map<String, Object>> result = service.getAgendaPlenarioParaData(data);

            assertEquals(List.of(sessaoPlenarioSf()), result);
            assertTrue(service.getAgendaPlenario().isEmpty(), "cache de hoje segue vazio");
            // caminho independente do scheduler: nem o mapeamento de salas é carregado
            verifyNoInteractions(salaRepository, cessaoSheetService);
        }

        @Test
        @DisplayName("getAgendaPlenarioParaData — data = hoje lê o cache, sem nenhum HTTP")
        void getAgendaPlenarioParaData_hoje_leCacheSemHttp() {
            List<Map<String, Object>> result = service.getAgendaPlenarioParaData(LocalDate.now());

            assertTrue(result.isEmpty());
            verifyNoInteractions(httpClient, salaRepository, cessaoSheetService);
        }

        // ── Falha no fetch SOB DEMANDA ───────────────────────────────────────────────
        // Aqui não há cache a preservar: o cache é de HOJE e a consulta é de outra data. O catch
        // antigo devolvia `cacheComissoes` — entregava as reuniões de HOJE como se fossem as da data
        // pedida. Agora o sentinela de falha vira lista vazia (ouVazio), e o contrato público
        // continua sem devolver null.

        @Test
        @DisplayName("getAgendaParaData: falha de PARSE de outra data devolve vazio, nunca o cache de HOJE")
        void getAgendaParaData_outraData_falhaDeParse_naoVazaCacheDeHoje() throws Exception {
            pollComDadosReais();                                  // cache de hoje: 2 reuniões
            LocalDate data = LocalDate.now().minusDays(7);
            stubHttp(COMISSAO_API + data.format(DATE_FMT), respostaOk(XML_MALFORMADO));
            when(cessaoSheetService.fetchCessoesParaData(data)).thenReturn(List.of());

            List<Map<String, Object>> result = service.getAgendaParaData(data, null);

            assertEquals(List.of(), result,
                    "o catch devolvia o cache de HOJE como se fosse a agenda da data consultada");
            when(cessaoSheetService.getCessoes()).thenReturn(List.of());
            assertEquals(2, service.getAgendaComissoes().size(), "e o cache de hoje segue intacto");
        }

        @Test
        @DisplayName("getAgendaParaData: HTTP 500 de outra data devolve vazio (contrato não-null)")
        void getAgendaParaData_outraData_http500_listaVazia() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubSalas();
            stubHttp(COMISSAO_API + data.format(DATE_FMT), respostaErro(500));
            when(cessaoSheetService.fetchCessoesParaData(data)).thenReturn(List.of(cessao(9, "Cessão")));

            List<Map<String, Object>> result = service.getAgendaParaData(data, null);

            assertNotNull(result, "o sentinela de falha é interno: o contrato público nunca devolve null");
            assertEquals(List.of(cessao(9, "Cessão")), result, "sem comissões, mas as cessões da data continuam");
        }

        @Test
        @DisplayName("getAgendaPlenarioParaData: HTTP 500 devolve lista vazia (contrato não-null)")
        void getAgendaPlenarioParaData_falha_listaVazia() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubHttp(PLENARIO_API + data.format(DATE_FMT), respostaErro(500));

            List<Map<String, Object>> result = service.getAgendaPlenarioParaData(data);

            assertNotNull(result, "o sentinela de falha é interno: o contrato público nunca devolve null");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Mapeamento local→sala (regex sobre o <local> da API × cadastro de salas)")
    class MapeamentoLocalParaSala {

        /** Fetch de outra data com o XML de variações de local (não polui o cache de hoje). */
        private List<Map<String, Object>> agendaComLocaisVariados() throws Exception {
            LocalDate data = LocalDate.now().minusDays(7);
            stubSalas();
            stubHttp(COMISSAO_API + data.format(DATE_FMT), respostaOk(MAPEAMENTO_XML));
            when(cessaoSheetService.fetchCessoesParaData(data)).thenReturn(List.of());
            return service.getAgendaParaData(data, null);
        }

        @Test
        @DisplayName("local \"Plenário nº N\" resolve pela numeração do cadastro (\"Plenário 03\" casa nº 3)")
        void local_plenarioNumerado_resolvePeloCadastro() throws Exception {
            List<Map<String, Object>> result = agendaComLocaisVariados();

            assertEquals(9, buscarPorCodigo(result, "M1").get("sala_id"));
            // zero à esquerda do cadastro: "Plenário 03" (sala 4) casa o "Plenário nº 3" da API
            assertEquals(4, buscarPorCodigo(result, "M7").get("sala_id"));
        }

        @Test
        @DisplayName("local \"Sala …\" (não catalogada) cai em Demais Salas")
        void local_salaGenerica_caiEmDemaisSalas() throws Exception {
            List<Map<String, Object>> result = agendaComLocaisVariados();

            assertEquals(11, buscarPorCodigo(result, "M2").get("sala_id"));
        }

        @Test
        @DisplayName("Auditório Petrônio Portella — casa com e sem acento, inclusive grafia \"Portela\"")
        void local_auditorioPetronio_variacoesDeGrafia() throws Exception {
            List<Map<String, Object>> result = agendaComLocaisVariados();

            assertEquals(1, buscarPorCodigo(result, "M3").get("sala_id"));
            assertEquals(1, buscarPorCodigo(result, "M4").get("sala_id"));
        }

        @Test
        @DisplayName("local sem padrão (\"Salão…\") ou plenário sem sala cadastrada (nº 99) → reunião descartada")
        void local_semMatch_reuniaoDescartada() throws Exception {
            List<Map<String, Object>> result = agendaComLocaisVariados();

            assertNull(buscarPorCodigoOuNull(result, "M5"), "Salão Negro não é \"Sala …\" nem plenário");
            assertNull(buscarPorCodigoOuNull(result, "M6"), "Plenário nº 99 não existe no cadastro");
            assertEquals(5, result.size(), "só M1–M4 e M7 mapeiam; M5/M6 são descartadas");
        }

        @Test
        @DisplayName("mapeamento é lazy — 2 polls consultam o SalaRepository uma única vez")
        void mapeamento_carregadoUmaVez() throws Exception {
            stubSalas();
            stubHttp(COMISSAO_API + hojeFmt(), respostaOk(COMISSOES_XML), respostaOk(COMISSOES_XML));
            stubHttp(PLENARIO_API + hojeFmt(), respostaOk(PLENARIO_XML), respostaOk(PLENARIO_XML));

            service.poll();
            service.poll();

            verify(salaRepository, times(1)).findAtivasOrdenadas();
        }

        private Map<String, Object> buscarPorCodigo(List<Map<String, Object>> agenda, String codigo) {
            Map<String, Object> item = buscarPorCodigoOuNull(agenda, codigo);
            assertNotNull(item, "reunião " + codigo + " deveria estar na agenda");
            return item;
        }

        private Map<String, Object> buscarPorCodigoOuNull(List<Map<String, Object>> agenda, String codigo) {
            return agenda.stream().filter(m -> codigo.equals(m.get("codigo"))).findFirst().orElse(null);
        }
    }
}
