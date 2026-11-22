#!/usr/bin/env node
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
// Gathers the fork's MPL-covered files for the public mirror (decision 0139, N2-2): every file in
// this folder except build output and dependencies. Run at each store build:
//   node mirror.mjs <outDir>
// then commit <outDir> to the public mirror repository with the store build's version as its tag.
import { cpSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const here = new URL(".", import.meta.url).pathname;
const out = process.argv[2];
if (!out) {
  console.error("Usage: node mirror.mjs <outDir>");
  process.exit(1);
}
const SKIP = new Set([
  "node_modules",
  "build",
  ".gradle",
  ".build",
  ".swiftpm",
  ".turbo",
  "local.properties",
]);

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    if (SKIP.has(name)) continue;
    const path = join(dir, name);
    if (statSync(path).isDirectory()) yield* walk(path);
    else yield path;
  }
}

let n = 0;
for (const path of walk(here)) {
  const rel = relative(here, path);
  mkdirSync(join(out, rel, ".."), { recursive: true });
  cpSync(path, join(out, rel));
  n += 1;
}
console.log(`Copied ${n} files to ${out}.`);
