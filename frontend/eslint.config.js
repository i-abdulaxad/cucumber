// @ts-check
import tseslint from 'typescript-eslint';
import jsxA11y from 'eslint-plugin-jsx-a11y';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/api/generated/**', 'playwright-report/**', 'test-results/**', 'coverage/**'] },
  ...tseslint.configs.strict,
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      // Guardrail: no `as` casts (only `as const`, which this rule always allows).
      '@typescript-eslint/consistent-type-assertions': ['error', { assertionStyle: 'never' }],
      '@typescript-eslint/no-non-null-assertion': 'error',
    },
  },
  {
    files: ['src/**/*.{ts,tsx,js}'],
    rules: {
      'no-console': 'error',
      'no-restricted-syntax': [
        'error',
        { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: 'dangerouslySetInnerHTML is not allowed.' },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'Only src/api calls fetch.' },
        { name: 'sessionStorage', message: 'Only src/auth/session.ts touches sessionStorage.' },
        { name: 'localStorage', message: 'The app never uses localStorage (D4).' },
      ],
    },
  },
  {
    files: ['src/**/*.tsx'],
    ...jsxA11y.flatConfigs.strict,
  },
  {
    // src/api owns fetch, but not Web Storage.
    files: ['src/api/**'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'sessionStorage', message: 'Only src/auth/session.ts touches sessionStorage.' },
        { name: 'localStorage', message: 'The app never uses localStorage (D4).' },
      ],
    },
  },
  {
    // session.ts owns sessionStorage; tests inspect storage and stub the network.
    files: ['src/auth/session.ts', 'src/**/*.test.{ts,tsx,js}', 'src/test/**'],
    rules: { 'no-restricted-globals': 'off' },
  },
);
