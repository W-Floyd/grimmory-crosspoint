#!/usr/bin/env node
// Builds libs/foliate-js (served as /assets/foliate/) from upstream's vendored
// src/assets/foliate plus patches/foliate.patch, so the fork never edits upstream's copy.
//
//   node scripts/foliate-patch.mjs         apply (refuses to discard unsaved edits)
//   node scripts/foliate-patch.mjs save    rewrite the patch from edits made in libs/foliate-js
//
// Applied in-process rather than with patch(1): BSD, GNU and busybox (the Docker image) differ
// in fuzz, and a hunk that no longer matches upstream must fail rather than land loosely.

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const upstreamDir = path.join(root, 'src/assets/foliate');
const outDir = path.join(root, 'libs/foliate-js');
const patchFile = path.join(root, 'patches/foliate.patch');
// Outside outDir, which angular.json copies wholesale into the build.
const stampFile = path.join(root, 'libs/.foliate-js.stamp');

const listFiles = dir => fs.readdirSync(dir, { recursive: true, withFileTypes: true })
  .filter(e => e.isFile())
  .map(e => path.relative(dir, path.join(e.parentPath, e.name)))
  .sort();

const treeHash = dir => {
  const h = createHash('sha256');
  for (const f of listFiles(dir)) h.update(f).update('\0').update(fs.readFileSync(path.join(dir, f))).update('\0');
  return h.digest('hex');
};

const parsePatch = text => {
  const files = [];
  let file, hunk;
  for (const line of text.split('\n')) {
    if (line.startsWith('diff --git ')) {
      file = null;
    } else if (line.startsWith('+++ ')) {
      file = { name: line.slice(4).replace(/^b\//, ''), hunks: [] };
      files.push(file);
    } else if (line.startsWith('@@ ')) {
      const m = /^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@/.exec(line);
      hunk = { start: Number(m[1]), old: [], new: [] };
      file.hunks.push(hunk);
    } else if (hunk && file && /^[ +-]/.test(line)) {
      const body = line.slice(1);
      if (line[0] !== '+') hunk.old.push(body);
      if (line[0] !== '-') hunk.new.push(body);
    }
  }
  return files;
};

const matchesAt = (lines, at, expected) =>
  at >= 0 && at + expected.length <= lines.length && expected.every((l, i) => lines[at + i] === l);

// Exact context, nearest offset first: tolerates upstream adding lines elsewhere, never fuzz.
const applyHunks = (name, text, hunks) => {
  const lines = text.split('\n');
  let shift = 0;
  for (const h of hunks) {
    const want = h.start - 1 + shift;
    let at = -1;
    for (let d = 0; d < lines.length && at < 0; d++) {
      if (matchesAt(lines, want - d, h.old)) at = want - d;
      else if (matchesAt(lines, want + d, h.old)) at = want + d;
    }
    if (at < 0) throw new Error(`${name}: hunk at line ${h.start} no longer matches upstream`);
    lines.splice(at, h.old.length, ...h.new);
    shift = at - (h.start - 1) + h.new.length - h.old.length;
  }
  return lines.join('\n');
};

const apply = () => {
  if (fs.existsSync(outDir) && fs.existsSync(stampFile)
      && fs.readFileSync(stampFile, 'utf8') !== treeHash(outDir)) {
    throw new Error(`${path.relative(root, outDir)} has unsaved edits: run \`pnpm foliate:save\` or delete it`);
  }
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.cpSync(upstreamDir, outDir, { recursive: true });
  for (const { name, hunks } of parsePatch(fs.readFileSync(patchFile, 'utf8'))) {
    const target = path.join(outDir, name);
    fs.writeFileSync(target, applyHunks(name, fs.readFileSync(target, 'utf8'), hunks));
  }
  fs.writeFileSync(stampFile, treeHash(outDir));
};

const save = () => {
  let diff;
  try {
    diff = execFileSync('git', ['diff', '--no-index', '--src-prefix=a/', '--dst-prefix=b/',
      path.relative(root, upstreamDir), path.relative(root, outDir)], { cwd: root, encoding: 'utf8' });
  } catch (e) {
    if (e.status !== 1) throw e;
    diff = e.stdout; // 1 means "differences found"
  }
  const rel = p => p.replaceAll(`a/${path.relative(root, upstreamDir)}/`, 'a/')
    .replaceAll(`b/${path.relative(root, outDir)}/`, 'b/');
  fs.writeFileSync(patchFile, rel(diff).replace(/^index .*\n/gm, ''));
  fs.writeFileSync(stampFile, treeHash(outDir));
};

try {
  process.argv[2] === 'save' ? save() : apply();
} catch (e) {
  console.error(`foliate-patch: ${e.message}`);
  process.exit(1);
}
