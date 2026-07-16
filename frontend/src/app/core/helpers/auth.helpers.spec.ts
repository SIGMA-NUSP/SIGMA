import { homeRouteForRole } from './auth.helpers';

describe('homeRouteForRole', () => {
  it('administrador retorna /admin', () => {
    expect(homeRouteForRole('administrador')).toBe('/admin');
  });

  it('tecnico retorna /tecnico', () => {
    expect(homeRouteForRole('tecnico')).toBe('/tecnico');
  });

  it('qualquer outro papel retorna /home', () => {
    expect(homeRouteForRole('operador')).toBe('/home');
  });

  it('é case-sensitive — variação de caixa cai no fallback /home', () => {
    expect(homeRouteForRole('Administrador')).toBe('/home');
  });

  it('null retorna /home', () => {
    expect(homeRouteForRole(null)).toBe('/home');
  });

  it('undefined retorna /home', () => {
    expect(homeRouteForRole(undefined)).toBe('/home');
  });
});
