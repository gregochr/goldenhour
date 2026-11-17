#!/usr/bin/env node
// Decides whether an `npm audit --json` report is explained SOLELY by allowlisted advisories.
// Called by scripts/npm-audit.sh only after `npm audit` failed with a non-transport error.
//
// usage: node npm-audit-allowlist.js REPORT.json ALLOWLIST.txt LEVEL TODAY(YYYY-MM-DD)
// exit 0: every vulnerability at or above LEVEL is explained only by live allowlist entries
//         (stdout carries one ::warning:: line per tolerated advisory)
// exit 1: anything else (a non-allowlisted advisory, expired entry, unreadable report, nothing found)
'use strict';
const fs = require('fs');

const [reportPath, allowPath, level, today] = process.argv.slice(2);
const RANK = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 };

function fail(msg) {
  console.log(`npm-audit: ${msg}`);
  process.exit(1);
}

let report;
try {
  report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
} catch (e) {
  fail(`could not parse the audit JSON report (${e.message}); not tolerating anything.`);
}
if (report.error || !report.vulnerabilities || typeof report.vulnerabilities !== 'object') {
  fail('the audit JSON report carries no vulnerability data (error or malformed); not tolerating anything.');
}
if (!(level in RANK)) fail(`unknown audit level '${level}'.`);

// ---- allowlist ----
const live = new Map();
let lines = [];
try {
  lines = fs.readFileSync(allowPath, 'utf8').split('\n');
} catch (e) {
  fail(`no readable allowlist at ${allowPath}.`);
}
for (const raw of lines) {
  const line = raw.trim();
  if (!line || line.startsWith('#')) continue;
  const parts = line.split('|').map((s) => s.trim());
  const m = /^expires\s+(\d{4}-\d{2}-\d{2})$/.exec(parts[1] || '');
  if (!/^GHSA-[0-9a-z-]+$/i.test(parts[0]) || !m || !parts[2]) {
    console.log(`::error::npm-audit: malformed allowlist line ignored: ${line}`);
    continue;
  }
  const id = parts[0].toLowerCase();
  if (today > m[1]) {
    console.log(`::error::npm-audit: allowlist entry ${parts[0]} EXPIRED on ${m[1]} and is ignored; ` +
      "fix the dependency or renew the entry with the owner's approval.");
    continue;
  }
  live.set(id, { id: parts[0], expires: m[1], reason: parts[2] });
}

// ---- classify vulnerabilities ----
const advisoryId = (v) => {
  const tail = String(v.url || '').split('/').pop();
  return /^GHSA-/i.test(tail) ? tail.toLowerCase() : `source:${v.source}`;
};
const vulns = report.vulnerabilities;
const tolerated = new Set();
const tainted = new Set();
for (const [name, v] of Object.entries(vulns)) {
  const via = Array.isArray(v.via) ? v.via : [];
  if (via.length === 0) tainted.add(name);
  for (const entry of via) {
    if (typeof entry === 'object' && entry !== null) {
      const id = advisoryId(entry);
      if (live.has(id)) tolerated.add(id);
      else tainted.add(name);
    }
  }
}
// A dependent is tainted when any package it is `via` is tainted or unknown; iterate to a fixpoint.
for (let changed = true; changed;) {
  changed = false;
  for (const [name, v] of Object.entries(vulns)) {
    if (tainted.has(name)) continue;
    for (const entry of Array.isArray(v.via) ? v.via : []) {
      if (typeof entry === 'string' && (!(entry in vulns) || tainted.has(entry))) {
        tainted.add(name);
        changed = true;
        break;
      }
    }
  }
}

const atLevel = Object.entries(vulns).filter(([, v]) => (RANK[v.severity] ?? 99) >= RANK[level]);
if (atLevel.length === 0) fail('no vulnerability at or above the level in the report; refusing to treat that as a pass.');
const unexplained = atLevel.filter(([name]) => tainted.has(name)).map(([name]) => name);
if (unexplained.length > 0) {
  fail(`vulnerabilities not explained by the allowlist: ${unexplained.join(', ')}`);
}
for (const id of tolerated) {
  const e = live.get(id);
  console.log(`::warning::npm audit is TOLERATING ${e.id} until ${e.expires} (temporary allowlist): ${e.reason}`);
}
console.log(`npm-audit: ${atLevel.length} vulnerabilities at or above '${level}' are all explained by allowlisted advisories.`);
