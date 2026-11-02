// @vitest-environment node
// AC-9.5: gen:api:check fails when a generated file differs from a fresh generation.
import { describe, expect, it } from 'vitest';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const script = resolve(import.meta.dirname, '../../scripts/gen-api.mjs');

function run(args) {
  return spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
}

describe('gen:api:check (AC-9.5)', () => {
  it('passes on a fresh generation and fails after a one-line edit', () => {
    const out = mkdtempSync(join(tmpdir(), 'gen-api-'));
    try {
      execFileSync(process.execPath, [script, '--out', out]);
      const files = readdirSync(out).filter((f) => f.endsWith('.ts')).sort();
      expect(files).toEqual(['api-gateway.ts', 'auth-service.ts', 'report-service.ts', 'scenario-service.ts']);
      expect(run(['--check', '--out', out]).status).toBe(0);

      const target = join(out, 'scenario-service.ts');
      const lines = readFileSync(target, 'utf8').split('\n');
      lines[10] = `${lines[10]} // drift`;
      writeFileSync(target, lines.join('\n'));

      const drifted = run(['--check', '--out', out]);
      expect(drifted.status).not.toBe(0);
      expect(drifted.stderr).toContain('scenario-service.ts');
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  }, 60_000);

  it('fails when a generated file is missing', () => {
    const out = mkdtempSync(join(tmpdir(), 'gen-api-'));
    try {
      execFileSync(process.execPath, [script, '--out', out]);
      rmSync(join(out, 'auth-service.ts'));
      const missing = run(['--check', '--out', out]);
      expect(missing.status).not.toBe(0);
      expect(missing.stderr).toContain('auth-service.ts');
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  }, 60_000);
});
