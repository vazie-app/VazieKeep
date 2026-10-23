import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig(
  { ignores: ['dist/**', 'scripts/**', 'tests/e2e/**'] },
  js.configs.recommended,
  tseslint.configs.strictTypeChecked,
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.serviceworker, chrome: 'readonly' },
      parserOptions: {
        project: ['./tsconfig.json', './tsconfig.node.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      'no-console': ['error', { allow: ['warn', 'error'] }],
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      'no-restricted-globals': [
        'error',
        { name: 'localStorage', message: 'Vault data must never touch web storage.' },
        { name: 'sessionStorage', message: 'Vault data must never touch web storage.' },
      ],
    },
  },
  {
    // Host handlers are async so that anything they throw reaches the caller as a rejection.
    files: ['src/host/index.ts'],
    rules: { '@typescript-eslint/require-await': 'off' },
  },
);
