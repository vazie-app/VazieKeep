import { useEffect, useState } from 'preact/hooks';
import type { StringKey } from '../../shared/i18n';
import type { AppLink, FaqItem } from '../../shared/model';
import { SITE_ORIGIN } from '../../shared/origins';
import { Banner, Button, Card, Loading, Row, SectionLabel } from '../components/ui';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useApp, useT } from '../state/hooks';
import { useLanguage } from '../state/language';
import { go } from '../state/route';
import { openUrl } from '../util';
import { SettingsFrame } from './settings';

const SECTIONS: { section: AppLink['section']; title: StringKey }[] = [
  { section: 'AUTHOR', title: 'about.authorTitle' },
  { section: 'PROJECT', title: 'about.projectTitle' },
  { section: 'SECURITY', title: 'about.securityTitle' },
  { section: 'HELP', title: 'about.helpTitle' },
  { section: 'SUPPORT', title: 'about.supportTitle' },
  { section: 'FEEDBACK', title: 'about.feedbackTitle' },
];

export function AboutScreen() {
  const t = useT();
  const app = useApp();
  const language = useLanguage();
  const [links, setLinks] = useState<AppLink[] | null | undefined>(undefined);

  async function load(force = false) {
    setLinks(undefined);
    setLinks(force ? await host.links() : await host.links());
  }

  useEffect(() => {
    void load();
  }, []);

  if (!app) return null;
  return (
    <SettingsFrame title={t('about.title')}>
      <Card class="card-pad">
        <div class="stack-tight">
          <div class="cluster">
            <img src="../icons/classic-48.png" alt="" width="40" height="40" />
            <div>
              <strong>Vazovsky</strong>
              <div class="caption">{t('about.developerRole')}</div>
            </div>
          </div>
          <p>{t('about.developerBody')}</p>
        </div>
      </Card>
      <SectionLabel>{t('about.appTitle')}</SectionLabel>
      <Card class="card-pad">
        <p>{t('about.appBody')}</p>
      </Card>

      <SectionLabel>{t('about.coreTitle')}</SectionLabel>
      <Card class="card-pad">
        <div class="stack-tight">
          <p>{t('about.coreBody')}</p>
          <p class="caption">
            <strong>{t('about.coreWhyTitle')}.</strong> {t('about.coreWhyBody')}
          </p>
          <p class="caption">
            <strong>{t('about.coreBoundaryTitle')}.</strong> {t('about.coreBoundaryBody')}
          </p>
        </div>
      </Card>

      <SectionLabel>{t('about.factsTitle')}</SectionLabel>
      <Card>
        <Row title={t('about.version')} subtitle={app.version} />
        <Row title={t('about.coreVersion')} subtitle={app.coreVersion} />
        <Row title={t('about.format')} subtitle={t('about.formatValue')} />
        <Row title={t('about.license')} subtitle={t('about.licenseValue')} />
      </Card>

      {links === undefined ? <Loading /> : null}
      {links === null ? (
        <Banner
          tone="caution"
          action={t('faq.retry')}
          onAction={() => {
            void load(true);
          }}
        >
          {t('about.linksUnavailable')}
        </Banner>
      ) : null}
      {links
        ? SECTIONS.map(({ section, title }) => {
            const items = links.filter((link) => link.section === section);
            if (items.length === 0) return null;
            return (
              <div key={section} class="stack">
                <SectionLabel>{t(title)}</SectionLabel>
                <Card>
                  {items.map((link) => (
                    <Row
                      key={link.key}
                      onClick={() => {
                        openUrl(link.url);
                      }}
                      leading={
                        <Icon
                          name={link.url.startsWith('mailto:') ? 'mail' : 'external'}
                          size={22}
                        />
                      }
                      title={link.title[language]}
                      subtitle={link.subtitle?.[language]}
                    />
                  ))}
                </Card>
              </div>
            );
          })
        : null}
    </SettingsFrame>
  );
}

export function FaqScreen() {
  const t = useT();
  const language = useLanguage();
  const [items, setItems] = useState<FaqItem[] | null | undefined>(undefined);

  async function load() {
    setItems(undefined);
    setItems(await host.faq());
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <SettingsFrame title={t('faq.title')}>
      {items === undefined ? <Loading label={t('faq.loading')} /> : null}
      {items === null ? (
        <Banner
          tone="caution"
          action={t('faq.retry')}
          onAction={() => {
            void load();
          }}
        >
          {t('faq.unavailable')}
        </Banner>
      ) : null}
      {items && items.length === 0 ? <p class="caption">{t('faq.empty')}</p> : null}
      {items && items.length > 0 ? (
        <Card>
          {items.map((item) => (
            <details key={item.key} class="faq-item">
              <summary>
                {language === 'ru' ? item.question.ru : (item.question.en ?? item.question.ru)}
              </summary>
              <div class="faq-answer">
                {(language === 'ru' ? item.answer.ru : (item.answer.en ?? item.answer.ru))
                  .split('\n')
                  .map((line, index) => (
                    <p key={index}>{line}</p>
                  ))}
                {item.link ? (
                  <Button
                    variant="text"
                    size="pill"
                    icon="external"
                    onClick={() => {
                      openUrl(
                        item.link?.url.startsWith('/')
                          ? `${SITE_ORIGIN}${item.link.url}`
                          : (item.link?.url ?? ''),
                      );
                    }}
                  >
                    {language === 'ru'
                      ? item.link.label.ru
                      : (item.link.label.en ?? item.link.label.ru)}
                  </Button>
                ) : null}
              </div>
            </details>
          ))}
        </Card>
      ) : null}
    </SettingsFrame>
  );
}

const GUIDES = ['firstVault', 'sync', 'autofill', 'quick', 'import', 'plus'] as const;

export function GuidesScreen({ guide }: { guide: string | null }) {
  const t = useT();
  const picked = GUIDES.find((g) => g === guide);
  if (picked) {
    const body = t(`guide.${picked}Body` as StringKey);
    return (
      <SettingsFrame title={t(`guide.${picked}Title` as StringKey)}>
        <div class="stack guide-body">
          {body.split('\n\n').map((paragraph, index) => (
            <p key={index}>{paragraph}</p>
          ))}
        </div>
      </SettingsFrame>
    );
  }
  return (
    <SettingsFrame title={t('guides.title')}>
      <Card>
        {GUIDES.map((id) => (
          <Row
            key={id}
            onClick={() => {
              go({ name: 'settings', page: 'guides', guide: id });
            }}
            leading={<Icon name="book" size={22} />}
            title={t(`guide.${id}Title` as StringKey)}
            subtitle={t(`guide.${id}Summary` as StringKey)}
            chevron
          />
        ))}
      </Card>
    </SettingsFrame>
  );
}
