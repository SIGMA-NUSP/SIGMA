import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, of, interval, Subscription } from 'rxjs';
import { environment } from '../../../environments/environment';
import { User, LoginResponse, WhoAmIResponse, JwtPayload } from '../models/user.model';

const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

/**
 * sessionStorage: avisos "manter após ciência" já confirmados NESTA sessão de login (o popup os
 * esconde até o próximo login). Vive aqui porque é o AuthService quem delimita a sessão: o login
 * limpa a chave — F5 não reexibe o aviso (o storage da aba sobrevive), logar de novo sim.
 */
export const AVISOS_CIENTES_SESSAO_KEY = 'avisos_cientes_sessao';
const REFRESH_MARGIN_SEC = 300;
const REFRESH_CHECK_MS = 60_000;
const INACTIVITY_LIMIT_MS = 5_400_000; // 1h30m

@Injectable({ providedIn: 'root' })
export class AuthService {

  private _user = signal<User | null>(null);
  private _role = signal<string | null>(null);
  private _token = signal<string | null>(null);
  private refreshSub?: Subscription;
  private lastActivity = Date.now();

  user = this._user.asReadonly();
  role = this._role.asReadonly();
  isLoggedIn = computed(() => !!this._token());
  isAdmin = computed(() => this._role() === 'administrador');
  isTecnico = computed(() => this._role() === 'tecnico');
  isMaster = computed(() => this.isAdmin() && !!this._user()?.isMaster);
  senhaProvisoria = computed(() => !!this._user()?.senhaProvisoria);
  /** Possui folha de ponto (Q35) — vem do whoami/login (tem_folha_ponto). */
  temFolhaPonto = computed(() => !!this._user()?.tem_folha_ponto);

  constructor(private http: HttpClient, private router: Router) {
    this.loadFromStorage();
    this.trackActivity();
  }

  // ══ Login / Logout ════════════════════════════════════════════

  login(usuario: string, senha: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${environment.apiBaseUrl}/api/login`, { usuario, senha })
      .pipe(tap(res => {
        if (res.token) {
          this.saveToken(res.token);
          const user = res.user;
          const role = res.role || user?.role || (this.parseJwt(res.token)?.perfil ?? null);
          this._user.set(user);
          this._role.set(role);
          localStorage.setItem(USER_KEY, JSON.stringify({ user, role }));
          // Novo login: os avisos "manter após ciência" voltam a ser exibidos (1× por sessão).
          // Chave específica — NUNCA sessionStorage.clear(): o guard de reload do app.config usa o mesmo storage.
          try { sessionStorage.removeItem(AVISOS_CIENTES_SESSAO_KEY); } catch { /* sem storage: nada a limpar */ }
          this.startRefreshTimer();
        }
      }));
  }

  logout(): void {
    const token = this._token();
    if (token) {
      this.http.post(`${environment.apiBaseUrl}/api/auth/logout`, {}).subscribe({ error: () => {} });
    }
    this.clearSession();
    this.router.navigate(['/login']);
  }

  whoAmI(): Observable<WhoAmIResponse | null> {
    return this.http.get<WhoAmIResponse>(`${environment.apiBaseUrl}/api/whoami`)
      .pipe(
        tap(res => {
          if (res.ok) {
            this._user.set(res.user);
            this._role.set(res.role);
            localStorage.setItem(USER_KEY, JSON.stringify({ user: res.user, role: res.role }));
          }
        }),
        catchError(() => { this.clearSession(); return of(null); })
      );
  }

  refresh(): Observable<unknown> {
    return this.http.post<LoginResponse>(`${environment.apiBaseUrl}/api/auth/refresh`, {})
      .pipe(
        tap(res => { if (res.ok && res.token) this.saveToken(res.token); }),
        catchError(() => { this.clearSession(); return of(null); })
      );
  }

  /** Limpa a flag senhaProvisoria após o usuário trocar a senha com sucesso. */
  clearSenhaProvisoria(): void {
    const u = this._user();
    if (!u) return;
    const updated: User = { ...u, senhaProvisoria: false };
    this._user.set(updated);
    localStorage.setItem(USER_KEY, JSON.stringify({ user: updated, role: this._role() }));
  }

  /** Atualiza a foto do usuário logado (reflete no header sem novo login). */
  setFotoUrl(fotoUrl: string): void {
    const u = this._user();
    if (!u) return;
    const updated: User = { ...u, foto_url: fotoUrl || undefined };
    this._user.set(updated);
    localStorage.setItem(USER_KEY, JSON.stringify({ user: updated, role: this._role() }));
  }

  /** Id do usuário logado (para detectar edição do próprio perfil). */
  currentUserId(): string | undefined { return this._user()?.id; }

  // ══ Token ═════════════════════════════════════════════════════

  getToken(): string | null { return this._token(); }

  private saveToken(token: string): void {
    this._token.set(token);
    localStorage.setItem(TOKEN_KEY, token);
  }

  private clearSession(): void {
    this._token.set(null);
    this._user.set(null);
    this._role.set(null);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.stopRefreshTimer();
  }

  private loadFromStorage(): void {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return;

    const payload = this.parseJwt(token);
    if (!payload || payload.exp * 1000 < Date.now()) {
      this.clearSession();
      return;
    }

    this._token.set(token);

    const stored = localStorage.getItem(USER_KEY);
    if (stored) {
      try {
        const { user, role } = JSON.parse(stored);
        this._user.set(user);
        this._role.set(role);
      } catch { /* ignore */ }
    }

    this.startRefreshTimer();
  }

  parseJwt(token: string): JwtPayload | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
      return payload as JwtPayload;
    } catch { return null; }
  }

  // ══ Refresh automático ════════════════════════════════════════

  private startRefreshTimer(): void {
    this.stopRefreshTimer();
    this.refreshSub = interval(REFRESH_CHECK_MS).subscribe(() => this.tryRefresh());
  }

  private stopRefreshTimer(): void {
    this.refreshSub?.unsubscribe();
    this.refreshSub = undefined;
  }

  private tryRefresh(): void {
    if (Date.now() - this.lastActivity > INACTIVITY_LIMIT_MS) {
      this.logout();
      return;
    }

    const token = this._token();
    if (!token) return;

    const payload = this.parseJwt(token);
    if (!payload) return;

    const secsLeft = payload.exp - Math.floor(Date.now() / 1000);
    if (secsLeft < REFRESH_MARGIN_SEC && secsLeft > 0) {
      this.refresh().subscribe();
    }
  }

  private trackActivity(): void {
    if (typeof window === 'undefined') return;
    ['click', 'keydown', 'mousemove', 'scroll'].forEach(evt =>
      window.addEventListener(evt, () => this.lastActivity = Date.now(), { passive: true })
    );
  }
}
