-- ============================================================
-- 039 — Ponto: PNT_BANCO_SALDO (saldo do banco de horas por pessoa)
--
-- 1 linha por pessoa (handoff 2026-06-30 §3.1): cache do saldo +
-- proveniência da âncora oficial. SALDO_ABERTURA_MIN vem do BANCO
-- impresso na folha oficial publicada mais recente (minutos com
-- sinal); ANCORA_DATA = DATA_FIM do lote dessa folha (até onde o
-- oficial cobre); ANCORA_PAGINA_ID = página parseada (sem FK: mera
-- proveniência; NULL quando a pessoa ainda não tem folha — nesse
-- caso abertura 0 + flag "sem folha oficial" no service).
-- SALDO_BANCO_MIN = cache recalculado nos eventos (publicar lote,
-- criar/deliberar/cancelar solicitação): abertura − Σ débitos vivos
-- com DATA_FOLGA > ANCORA_DATA (Q4).
-- ============================================================

CREATE TABLE PNT_BANCO_SALDO (
    ID                  VARCHAR2(36)  PRIMARY KEY,
    PESSOA_ID           VARCHAR2(36)  NOT NULL,
    PESSOA_TIPO         VARCHAR2(20)  NOT NULL,
    SALDO_ABERTURA_MIN  NUMBER(7)     NOT NULL,
    SALDO_BANCO_MIN     NUMBER(7)     NOT NULL,
    ANCORA_DATA         DATE,
    ANCORA_PAGINA_ID    VARCHAR2(36),
    CRIADO_EM           TIMESTAMP     NOT NULL,
    ATUALIZADO_EM       TIMESTAMP     NOT NULL,
    CONSTRAINT UK_PNT_SALDO_PESSOA UNIQUE (PESSOA_ID, PESSOA_TIPO),
    CONSTRAINT CK_PNT_SALDO_PESSOA_TIPO CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR'))
);
