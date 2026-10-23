import { useEffect, useRef, useState } from 'preact/hooks';
import { formatDate } from '../../shared/i18n';
import type { AccountState, Prices } from '../../shared/model';
import { SITE_ORIGIN } from '../../shared/origins';
import { Confirm, showToast } from '../components/dialogs';
import {
  Badge,
  Banner,
  Button,
  Card,
  Loading,
  Row,
  SectionLabel,
  TextField,
} from '../components/ui';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useApp, useT } from '../state/hooks';
import { useLanguage } from '../state/language';
import { go, replace } from '../state/route';
import { codeOf, openUrl } from '../util';
import { SettingsFrame } from './settings';

export function planOf(account: AccountState): 'free' | 'plus' | 'lifetime' {
  if (!account.access.plusFeatures) return 'free';
  return account.lifetime ? 'lifetime' : 'plus';
}

function formatRubles(kopecks: number): string {
  const rubles = Math.floor(kopecks / 100);
  const rest = kopecks % 100;
  const grouped = String(rubles).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0');
  return rest === 0 ? `${grouped}\u00a0₽` : `${grouped},${String(rest).padStart(2, '0')}\u00a0₽`;
}

export function problemText(
  t: ReturnType<typeof useT>,
  problem: AccountState['problem'],
): string | null {
  switch (problem) {
    case 'offline':
      return t('account.problemOffline');
    case 'sessionExpired':
      return t('account.problemSession');
    case 'clockOff':
      return t('account.problemClock');
    case 'failed':
      return t('account.problemFailed');
    default:
      return null;
  }
}

export function AccountScreen() {
  const t = useT();
  const app = useApp();
  const language = useLanguage();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);
  const [confirmOut, setConfirmOut] = useState(false);
  const account = app?.account;

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => {
      setCooldown(cooldown - 1);
    }, 1000);
    return () => {
      clearTimeout(timer);
    };
  }, [cooldown]);

  if (!account) return null;

  const failure = (cause: unknown) => {
    const kind = codeOf(cause);
    setError(
      kind === 'invalidCode'
        ? t('account.errorCode')
        : kind === 'invalidEmail'
          ? t('account.errorEmail')
          : kind === 'rateLimited'
            ? t('account.errorRate')
            : t('account.errorUnavailable'),
    );
  };

  async function sendCode() {
    setBusy(true);
    setError(null);
    try {
      await host.requestCode(email);
      setSent(true);
      setCooldown(60);
    } catch (cause) {
      failure(cause);
    } finally {
      setBusy(false);
    }
  }

  async function signIn() {
    setBusy(true);
    setError(null);
    try {
      await host.signIn(email, code);
      setCode('');
      setSent(false);
    } catch (cause) {
      failure(cause);
    } finally {
      setBusy(false);
    }
  }

  const plan = planOf(account);
  const problem = problemText(t, account.problem);

  return (
    <SettingsFrame title={t('account.title')}>
      {!account.signedIn ? (
        <>
          <Card tone="accent" class="card-pad">
            <div class="stack-tight">
              <strong>{t('account.introTitle')}</strong>
              <p>{t('account.introBody')}</p>
            </div>
          </Card>
          {!sent ? (
            <form
              class="stack"
              onSubmit={(event) => {
                event.preventDefault();
                void sendCode();
              }}
            >
              <TextField
                label={t('account.emailLabel')}
                value={email}
                onInput={setEmail}
                type="email"
                autocomplete="email"
                autofocus
                error={error}
              />
              <p class="caption">
                {t('account.privacyNote')}{' '}
                <a href={`${SITE_ORIGIN}/legal/privacy`} target="_blank" rel="noreferrer">
                  {t('account.privacyLink')}
                </a>
              </p>
              <Button
                variant="action"
                size="large"
                full
                type="submit"
                busy={busy}
                disabled={email.trim() === ''}
              >
                {t('account.sendCode')}
              </Button>
            </form>
          ) : (
            <form
              class="stack"
              onSubmit={(event) => {
                event.preventDefault();
                void signIn();
              }}
            >
              <p class="dialog-message">{t('account.codeSent', { email })}</p>
              <TextField
                label={t('account.codeLabel')}
                value={code}
                onInput={setCode}
                autocomplete="one-time-code"
                inputMode="numeric"
                autofocus
                mono
                error={error}
              />
              <Button
                variant="action"
                size="large"
                full
                type="submit"
                busy={busy}
                disabled={code.trim() === ''}
              >
                {t('account.signIn')}
              </Button>
              <div class="cluster">
                <Button
                  variant="text"
                  size="pill"
                  disabled={cooldown > 0 || busy}
                  onClick={() => {
                    void sendCode();
                  }}
                >
                  {cooldown > 0
                    ? t('account.sendAgainIn', { seconds: cooldown })
                    : t('account.sendAgain')}
                </Button>
                <Button
                  variant="text"
                  size="pill"
                  onClick={() => {
                    setSent(false);
                    setCode('');
                    setError(null);
                  }}
                >
                  {t('account.changeEmail')}
                </Button>
              </div>
            </form>
          )}
        </>
      ) : (
        <>
          <Card>
            <Row
              leading={<Icon name="user" size={22} />}
              title={account.email ?? t('account.rowSignedIn')}
              subtitle={t(
                plan === 'free'
                  ? 'account.planFree'
                  : plan === 'lifetime'
                    ? 'account.planLifetime'
                    : 'account.planPlus',
              )}
            />
            {plan !== 'free' && account.entitlementExpiresAt !== null && !account.lifetime ? (
              <Row
                leading={<Icon name="calendar" size={22} />}
                title={t('account.planUntil', {
                  date: formatDate(language, account.entitlementExpiresAt),
                })}
              />
            ) : null}
            {plan !== 'free' &&
            account.access.state === 'plusOfflineGrace' &&
            account.access.leaseValidUntil !== null ? (
              <Row
                leading={<Icon name="offline" size={22} />}
                title={t('account.offlineUntil', {
                  date: formatDate(language, account.access.leaseValidUntil),
                })}
              />
            ) : null}
          </Card>
          {account.access.needsOnlineVerification && !problem ? (
            <Banner tone="caution">{t('account.expired')}</Banner>
          ) : null}
          {problem ? <Banner tone="caution">{problem}</Banner> : null}
          <div class="stack-tight">
            <Button
              variant="tonal"
              full
              icon="refresh"
              busy={account.refreshing}
              onClick={() => {
                void host.refreshAccount(true);
              }}
            >
              {t('account.refresh')}
            </Button>
            {plan === 'free' ? (
              <Button
                variant="action"
                full
                icon="star"
                onClick={() => {
                  go({ name: 'settings', page: 'plus' });
                }}
              >
                {t('account.getPlus')}
              </Button>
            ) : null}
            <Button
              variant="raised"
              full
              onClick={() => {
                setConfirmOut(true);
              }}
            >
              {t('account.signOut')}
            </Button>
            <Button
              variant="caution"
              full
              onClick={() => {
                go({ name: 'settings', page: 'deleteAccount' });
              }}
            >
              {t('account.delete')}
            </Button>
          </div>
        </>
      )}
      {confirmOut ? (
        <Confirm
          title={t('account.signOutTitle')}
          message={t('account.signOutMessage')}
          confirm={t('account.signOut')}
          tone="caution"
          onCancel={() => {
            setConfirmOut(false);
          }}
          onConfirm={() => {
            setConfirmOut(false);
            void host.signOut();
          }}
        />
      ) : null}
    </SettingsFrame>
  );
}

export function DeleteAccountScreen() {
  const t = useT();
  const app = useApp();
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const email = app?.account.email ?? '';
  const matches = email !== '' && typed.trim().toLowerCase() === email.toLowerCase();
  return (
    <SettingsFrame title={t('account.deleteTitle')}>
      <p>{t('account.deleteBody')}</p>
      <TextField
        label={t('account.deleteTypePrompt', { email })}
        value={typed}
        onInput={setTyped}
        type="email"
        error={error}
      />
      <Button
        variant="caution"
        size="large"
        full
        busy={busy}
        disabled={!matches}
        onClick={() => {
          setBusy(true);
          setError(null);
          void host
            .deleteAccount()
            .then((ok) => {
              if (ok) replace({ name: 'settings', page: 'home' });
              else setError(t('account.deleteFailed'));
            })
            .catch(() => {
              setError(t('account.deleteFailed'));
            })
            .finally(() => {
              setBusy(false);
            });
        }}
      >
        {t('account.deleteConfirm')}
      </Button>
    </SettingsFrame>
  );
}

export function PlusScreen() {
  const t = useT();
  const app = useApp();
  const language = useLanguage();
  const [prices, setPrices] = useState<Prices | null | undefined>(undefined);
  const [plan, setPlan] = useState<string>('year');
  const [awaiting, setAwaiting] = useState(false);
  const [checking, setChecking] = useState(false);
  const [notSeen, setNotSeen] = useState(false);
  const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const account = app?.account;

  async function load() {
    setPrices(undefined);
    setPrices(await host.prices());
  }

  useEffect(() => {
    void load();
    return () => {
      clearInterval(timer.current);
    };
  }, []);

  useEffect(() => {
    if (account && account.access.plusFeatures && awaiting) {
      setAwaiting(false);
      clearInterval(timer.current);
      showToast(t('plus.activated'));
    }
  }, [account?.access.plusFeatures]);

  if (!account) return null;
  const current = planOf(account);

  async function check() {
    setChecking(true);
    setNotSeen(false);
    try {
      await host.refreshAccount(true);
    } finally {
      setChecking(false);
    }
  }

  function startPolling() {
    setAwaiting(true);
    let attempts = 0;
    clearInterval(timer.current);
    timer.current = setInterval(() => {
      attempts += 1;
      void host.refreshAccount(true);
      if (attempts >= 6) {
        clearInterval(timer.current);
        setNotSeen(true);
      }
    }, 5000);
  }

  function checkout() {
    openUrl(`${SITE_ORIGIN}/keep/plus?plan=${plan}`);
    startPolling();
  }

  return (
    <SettingsFrame title={current === 'free' ? t('plus.title') : t('plus.manageTitle')}>
      {current !== 'free' ? (
        <Card tone="accent" class="card-pad">
          <div class="stack-tight">
            <div class="cluster">
              <Badge tone="accent">Keep+</Badge>
              <strong>
                {t(
                  current === 'lifetime'
                    ? 'plus.manageLifetime'
                    : account.entitlementExpiresAt === null
                      ? 'plus.manageOpen'
                      : 'plus.manageUntil',
                  {
                    date:
                      account.entitlementExpiresAt === null
                        ? ''
                        : formatDate(language, account.entitlementExpiresAt),
                  },
                )}
              </strong>
            </div>
          </div>
        </Card>
      ) : (
        <p>{t('plus.intro')}</p>
      )}

      <Card>
        <Row leading={<Icon name="swap" size={22} />} title={t('guide.plusMultiVault')} wrap />
        <Row leading={<Icon name="clock" size={22} />} title={t('guide.plusTotp')} wrap />
        <Row leading={<Icon name="history" size={22} />} title={t('guide.plusHistory')} wrap />
        <Row
          leading={<Icon name="paperclip" size={22} />}
          title={t('guide.plusAttachments')}
          wrap
        />
      </Card>

      {current !== 'lifetime' ? (
        <>
          {!account.signedIn ? (
            <Banner
              tone="info"
              action={t('plus.signIn')}
              onAction={() => {
                go({ name: 'settings', page: 'account' });
              }}
            >
              {t('plus.signInFirst')}
            </Banner>
          ) : null}
          <SectionLabel>{t('plus.plans')}</SectionLabel>
          {prices === undefined ? (
            <Loading label={t('plus.loading')} />
          ) : prices === null ? (
            <Banner
              tone="caution"
              action={t('plus.retry')}
              onAction={() => {
                void load();
              }}
            >
              {t('plus.unavailable')}
            </Banner>
          ) : (
            <>
              <Card>
                {(['month', 'year', 'lifetime'] as const).map((id) => {
                  const item = prices.plans.find(
                    (p) => p.id.toLowerCase().includes(id) || (id === 'lifetime' && p.lifetime),
                  );
                  if (!item) return null;
                  const noteKey =
                    id === 'month'
                      ? 'plus.monthNote'
                      : id === 'year'
                        ? 'plus.yearNote'
                        : 'plus.lifetimeNote';
                  return (
                    <Row
                      key={id}
                      onClick={() => {
                        setPlan(id);
                      }}
                      selected={plan === id}
                      leading={<Icon name={plan === id ? 'check' : 'star'} size={20} />}
                      title={`${t(id === 'month' ? 'plus.planMonth' : id === 'year' ? 'plus.planYear' : 'plus.planLifetime')} · ${formatRubles(item.amountKopecks)}`}
                      subtitle={
                        id === 'lifetime' && item.earlyRemaining !== null
                          ? t('plus.earlyLeft', { count: item.earlyRemaining })
                          : t(noteKey)
                      }
                    />
                  );
                })}
              </Card>
              <Button
                variant="action"
                size="large"
                full
                icon="external"
                disabled={!account.signedIn || !prices.checkoutAvailable}
                onClick={checkout}
              >
                {t('plus.continue')}
              </Button>
              {!prices.checkoutAvailable ? (
                <p class="caption">{t('plus.checkoutSoon')}</p>
              ) : (
                <p class="caption">{t('plus.onSite')}</p>
              )}
            </>
          )}
          {awaiting ? (
            <Banner tone="info" icon="clock">
              {notSeen ? t('plus.notSeen') : t('plus.awaiting')}
            </Banner>
          ) : null}
          {account.signedIn ? (
            <Button
              variant="tonal"
              full
              icon="refresh"
              busy={checking || account.refreshing}
              onClick={() => {
                void check();
              }}
            >
              {t('plus.check')}
            </Button>
          ) : null}
        </>
      ) : null}
    </SettingsFrame>
  );
}
