export interface User {
  id: string;
  username: string;
  name?: string;
  nome?: string;
  email: string;
  foto_url?: string;
  role?: string;
  canEditObsSupervisor?: boolean;
  canEditObsChefe?: boolean;
  isMaster?: boolean;
  senhaProvisoria?: boolean;
  tem_folha_ponto?: boolean;
}

export interface LoginResponse {
  ok: boolean;
  token: string;
  user: User;
  role: string;
}

export interface WhoAmIResponse {
  ok: boolean;
  user: User;
  role: string;
  exp: number;
}

export interface JwtPayload {
  sub: string;
  perfil: string;
  username: string;
  nome: string;
  email: string;
  sid: number;
  iat: number;
  exp: number;
}

export interface PaginationMeta {
  page: number;
  limit: number;
  total: number;
  pages: number;
  distinct?: Record<string, { value: string; label: string }[]>;
}

export interface PagedResponse<T = Record<string, unknown>> {
  ok: boolean;
  data: T[];
  meta: PaginationMeta;
}

export interface ApiResponse<T = unknown> {
  ok: boolean;
  data?: T;
  error?: string;
  message?: string;
}

/**
 * Estado das listagens server-side (paginação + sort + busca + filtros de coluna).
 * Estruturalmente compatível com o ListParams do ApiService; consumido pelo
 * TableStateController (core/helpers/table-state.controller.ts).
 */
export interface TableState {
  page: number;
  limit: number;
  sort: string;
  direction: string;
  search?: string;
  filters?: Record<string, unknown>;
}
