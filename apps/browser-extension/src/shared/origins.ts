/** Where the Vazie Account, Keep+ and the website live. Set when the extension is built, never kept in git. */
const origins = typeof __VAZIE_ORIGINS__ === 'undefined' ? null : __VAZIE_ORIGINS__;

export const ACCOUNT_ORIGIN = origins?.account ?? '';
export const KEEP_ORIGIN = origins?.keep ?? '';
export const SITE_ORIGIN = origins?.site ?? '';
