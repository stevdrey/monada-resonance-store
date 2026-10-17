#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const MAX_FILES = 40;
const WARNING_FILES = 30;
const DEFAULT_BASE_REF = 'origin/main';

// This hook is a local convenience, not an authorization boundary: CI is authoritative.
const PUBLISH_COMMAND = /(?:^|[\s;&|()])(?:gh\s+pr\s+create|git\s+(?:(?:-C|-c|--git-dir|--work-tree)\s+(?:"[^"]*"|'[^']*'|\S+)\s+)*push)(?=$|[\s;&|()])/;

function git(cwd, ...args) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'buffer',
    timeout: 10_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    const detail = result.stderr?.toString('utf8').trim() || result.error?.message || 'Unknown Git error';
    throw new Error(`git ${args[0]} failed: ${detail}`);
  }
  return result.stdout;
}

function pathsFromNullSeparated(buffer) {
  return buffer.toString('utf8').split('\0').filter(Boolean);
}

function changedFiles(cwd, baseRef) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').toString('utf8').trim();
  const base = git(root, 'merge-base', baseRef, 'HEAD').toString('utf8').trim();
  if (!base) throw new Error(`No merge base found for ${baseRef}`);

  // Comparing a commit to the worktree includes committed, staged, and unstaged changes.
  const tracked = git(root, 'diff', '--name-only', '-z', base, '--');
  const untracked = git(root, 'ls-files', '--others', '--exclude-standard', '-z');
  return new Set([...pathsFromNullSeparated(tracked), ...pathsFromNullSeparated(untracked)]).size;
}

async function hookInput() {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const value = JSON.parse(input);
  if (!value || typeof value !== 'object' || !value.tool_input || typeof value.tool_input !== 'object') {
    throw new Error('Invalid PreToolUse input: tool_input is missing');
  }
  return value;
}

function baseReference(args) {
  const index = args.indexOf('--base');
  if (index !== -1) {
    if (index + 1 >= args.length || !args[index + 1] || args[index + 1].startsWith('--')) {
      throw new Error('Provide a Git reference after --base');
    }
    return args[index + 1];
  }
  return process.env.PR_PREFLIGHT_BASE || DEFAULT_BASE_REF;
}

async function main() {
  const args = process.argv.slice(2);
  const fromHook = args.includes('--hook');
  let cwd = process.cwd();

  if (fromHook) {
    const payload = await hookInput();
    const toolInput = payload.tool_input;
    const command = typeof toolInput.command === 'string'
      ? toolInput.command
      : Array.isArray(toolInput.cmd) ? toolInput.cmd.join(' ') : '';

    if (!PUBLISH_COMMAND.test(command)) return;
    if (typeof payload.cwd === 'string' && payload.cwd) cwd = payload.cwd;
  }

  const baseRef = baseReference(args);
  const count = changedFiles(cwd, baseRef);
  const result = `PR preflight: ${count}/${MAX_FILES} changed files relative to ${baseRef}.`;

  if (count > MAX_FILES) {
    console.error(`BLOCKED: ${result} Split this work into smaller, independently reviewable PRs before publishing. Preserve required tests and security controls.`);
    process.exitCode = 2;
  } else if (count > WARNING_FILES) {
    console.error(`WARNING: ${result} Review scope before adding more changes.`);
  } else if (!fromHook) {
    console.log(`PASS: ${result}`);
  }
}

try {
  await main();
} catch (error) {
  console.error(`BLOCKED: PR preflight could not verify the change scope: ${error.message}. Refresh the base reference with 'git fetch origin main' and retry.`);
  process.exitCode = 2;
}
