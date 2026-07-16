/**
 * Mapeia o papel (role) do usuário para a rota inicial dele.
 * Único lugar do app que decide "para onde ir após login" / "para onde redirecionar quando não autorizado".
 */
export function homeRouteForRole(role: string | null | undefined): string {
  if (role === 'administrador') return '/admin';
  if (role === 'tecnico')       return '/tecnico';
  return '/home';
}
