import { DEFAULT_SETTINGS, type Settings } from '../shared/model';
import { sanitizeSettings } from '../shared/settings';
import { kvGet, kvSet } from './storage';

export class SettingsStore {
  #value: Settings = DEFAULT_SETTINGS;

  async load(): Promise<Settings> {
    this.#value = sanitizeSettings(await kvGet<Partial<Settings>>('settings'));
    return this.#value;
  }

  get value(): Settings {
    return this.#value;
  }

  async update(patch: Partial<Settings>): Promise<Settings> {
    this.#value = sanitizeSettings({ ...this.#value, ...patch });
    await kvSet('settings', this.#value);
    return this.#value;
  }
}
