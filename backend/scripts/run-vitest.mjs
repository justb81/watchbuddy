#!/usr/bin/env node
// Translate jest-style `--json --outputFile=<path>` flags into their vitest
// equivalents. Some Claude Code runner smoke checks invoke
// `npm test -- --json --outputFile=<runner_temp>/jest.json` expecting Jest,
// and vanilla vitest errors out with "Unknown option `--json`" under CAC.
// Keeping the translation here — instead of in a workflow or harness config
// — means every `npm test` invocation gets the same behaviour regardless of
// who runs it (CI, web session, local dev).
import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const vitestBin = resolve(here, '..', 'node_modules', '.bin', 'vitest');

const passthrough = [];
let sawJson = false;
for (const arg of process.argv.slice(2)) {
  if (arg === '--json') {
    sawJson = true;
    continue;
  }
  passthrough.push(arg);
}
if (sawJson && !passthrough.some((a) => a.startsWith('--reporter'))) {
  passthrough.unshift('--reporter=json');
}

const child = spawn(vitestBin, ['run', ...passthrough], { stdio: 'inherit' });
child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 1);
});
