-- ============================================================
-- 052 — Avisos: COMPLEMENTO por destinatário (FRM_AVISO_ALVO)
--
-- As mensagens de um cadastro são comuns a todo o público dele. O COMPLEMENTO é
-- o contrário: um texto dirigido a UM destinatário, que ele lê depois das
-- mensagens comuns, na mesma janela. É o que permite dizer a cada pessoa QUAIS
-- dias da folha dela ficaram sem registro de entrada ou de saída sem quebrar a
-- comunicação em um cadastro por pessoa — e sem que ninguém leia o dia do outro.
--
-- Nulo é o caso normal: quase nenhum alvo tem algo a acrescentar. Alvo coletivo
-- (TODOS_*) nunca tem, porque não há a quem personalizar.
--
-- A folga da coluna cobre o pior caso real com sobra: um mês inteiro de dias
-- listados ("dd/mm", 31 deles) não passa de um terço dela.
-- ============================================================

ALTER TABLE FRM_AVISO_ALVO ADD (COMPLEMENTO VARCHAR2(1000 CHAR))
