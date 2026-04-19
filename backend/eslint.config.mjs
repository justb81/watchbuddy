// WatchBuddy backend ESLint configuration (flat config, ESLint 9+).
// Scope: Node.js 22 ESM. Formatting is delegated to Prettier — this file
// only carries correctness rules, and eslint-config-prettier at the end
// disables any stylistic rule that would conflict with Prettier.
import js from '@eslint/js';
import nodePlugin from 'eslint-plugin-n';
import prettierConfig from 'eslint-config-prettier';
import globals from 'globals';

export default [
  {
    ignores: ['node_modules/**', 'coverage/**', 'dist/**'],
  },
  js.configs.recommended,
  nodePlugin.configs['flat/recommended'],
  {
    languageOptions: {
      ecmaVersion: 2024,
      sourceType: 'module',
      globals: {
        ...globals.node,
      },
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
      'no-console': 'off',
      'n/no-missing-import': 'off', // Trust Node's native ESM resolver.
      'n/no-unpublished-import': 'off', // devDependencies (vitest, supertest) are imported only in tests.
      'n/hashbang': 'off',
      'n/no-process-exit': 'off', // Startup fail-fast on missing config is intentional.
    },
  },
  {
    files: ['src/__tests__/**/*.js', '**/*.test.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      'n/no-extraneous-import': 'off',
    },
  },
  prettierConfig,
];
