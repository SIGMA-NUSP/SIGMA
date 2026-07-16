// Sanidade de ambiente para a suíte de testes (D5). Falha cedo e com mensagem
// clara se o runner não estiver rodando com TZ=America/Sao_Paulo (protege
// date.helpers, que assume horário local — §5.2) ou sem ICU pt-BR (protege
// formatarDataExtensoBr). Rodar sempre via `npm test`, nunca `npx ng test` cru.

const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
if (tz !== 'America/Sao_Paulo') {
  throw new Error(
    `Sanidade de ambiente falhou: TZ efetiva é '${tz}', esperado 'America/Sao_Paulo'. ` +
      `Rode os testes via 'npm test' (fixa TZ=America/Sao_Paulo no processo) — não 'npx ng test' cru.`,
  );
}

const diaSemana = new Date(2026, 6, 16).toLocaleDateString('pt-BR', { weekday: 'long' });
if (!diaSemana.includes('feira')) {
  throw new Error(
    `Sanidade de ambiente falhou: ICU sem suporte a pt-BR (esperava conter 'feira', obtido '${diaSemana}'). ` +
      `Runtime Node provavelmente sem full-icu.`,
  );
}
