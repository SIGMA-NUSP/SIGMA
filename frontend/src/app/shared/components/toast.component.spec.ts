import { ToastService } from './toast.component';

/**
 * T19 — ToastService: `new ToastService()` (§5.4: instanciável direto) + fake timers
 * para o auto-dismiss por `setTimeout`. §C1: `vi.useRealTimers()` em `afterEach` em
 * TODO spec com fake timers; §C2: durações default por tipo (success 8s, error 12s,
 * warning 10s) assertadas com o relógio virtual, nunca com esperas reais.
 */
describe('ToastService', () => {
  let svc: ToastService;

  beforeEach(() => {
    vi.useFakeTimers();
    svc = new ToastService();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('show adiciona a mensagem e agenda auto-dismiss no default de 4s', () => {
    svc.show('olá');
    expect(svc.messages().length).toBe(1);
    expect(svc.messages()[0]).toMatchObject({ text: 'olá', type: 'info' });
    vi.advanceTimersByTime(3999);
    expect(svc.messages().length).toBe(1); // ainda visível 1ms antes
    vi.advanceTimersByTime(1);
    expect(svc.messages().length).toBe(0); // dismiss exatamente aos 4000ms
  });

  it('success usa duração default de 8s e tipo success', () => {
    svc.success('ok');
    expect(svc.messages()[0].type).toBe('success');
    vi.advanceTimersByTime(7999);
    expect(svc.messages().length).toBe(1);
    vi.advanceTimersByTime(1);
    expect(svc.messages().length).toBe(0);
  });

  it('error usa duração default de 12s e tipo error', () => {
    svc.error('falhou');
    expect(svc.messages()[0].type).toBe('error');
    vi.advanceTimersByTime(11999);
    expect(svc.messages().length).toBe(1);
    vi.advanceTimersByTime(1);
    expect(svc.messages().length).toBe(0);
  });

  it('warning usa duração default de 10s e tipo warning', () => {
    svc.warning('cuidado');
    expect(svc.messages()[0].type).toBe('warning');
    vi.advanceTimersByTime(9999);
    expect(svc.messages().length).toBe(1);
    vi.advanceTimersByTime(1);
    expect(svc.messages().length).toBe(0);
  });

  it('dismiss remove apenas a mensagem do id informado', () => {
    svc.show('a');
    svc.show('b');
    const [a, b] = svc.messages();
    svc.dismiss(a.id);
    expect(svc.messages().length).toBe(1);
    expect(svc.messages()[0].id).toBe(b.id);
    expect(svc.messages()[0].text).toBe('b');
  });

  it('ids são únicos e crescentes; cada toast dismissa no seu próprio tempo', () => {
    svc.show('a', 'info', 5000);
    svc.show('b', 'info', 3000);
    const [a, b] = svc.messages();
    expect(b.id).toBeGreaterThan(a.id);
    vi.advanceTimersByTime(3000);                        // b sai
    expect(svc.messages().map(m => m.text)).toEqual(['a']);
    vi.advanceTimersByTime(2000);                        // a sai (5000ms no total)
    expect(svc.messages().length).toBe(0);
  });

  it('dismiss manual antes do timeout: o setTimeout dispara depois mas é no-op', () => {
    svc.show('x', 'info', 4000);
    const id = svc.messages()[0].id;
    svc.dismiss(id);
    expect(svc.messages().length).toBe(0);
    vi.advanceTimersByTime(4000); // o timeout roda o filter, que não encontra o id
    expect(svc.messages().length).toBe(0);
  });
});
