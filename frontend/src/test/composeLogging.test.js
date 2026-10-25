import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Every compose service must bound its Docker json-file log. The nginx containers log to stdout, so
 * without `max-size` the access log grows without limit on the host.
 *
 * <p>No YAML parser is a frontend dependency, so this is a block-scoped text parse: a service is a
 * two-space-indented key under `services:`, and its block runs to the next such key.
 */
const servicesOf = (relativePath) => {
  const lines = readFileSync(resolve(process.cwd(), relativePath), 'utf8').split('\n');
  const start = lines.findIndex((l) => l === 'services:');
  expect(start, `${relativePath} must have a services: block`).toBeGreaterThan(-1);
  const services = {};
  let current = null;
  for (const line of lines.slice(start + 1)) {
    if (/^\S/.test(line)) break; // next top-level key (volumes:, networks:)
    const key = line.match(/^ {2}([A-Za-z0-9_-]+):\s*$/);
    if (key) {
      current = key[1];
      services[current] = [];
    } else if (current) {
      services[current].push(line);
    }
  }
  return services;
};

describe.each(['../docker-compose.yml', '../landing/docker-compose.yml'])('%s logging', (file) => {
  const services = servicesOf(file);

  it('declares at least one service', () => {
    expect(Object.keys(services).length).toBeGreaterThan(0);
  });

  it.each(Object.keys(services))('bounds the json-file log of service %s', (name) => {
    const body = services[name].join('\n');
    expect(body).toMatch(/^ {4}logging:\s*$/m);
    expect(body).toMatch(/^ {6}driver: json-file\s*$/m);
    expect(body).toMatch(/^ {6}options:\s*$/m);
    expect(body).toMatch(/^ {8}max-size: "?\d+[kmg]"?\s*$/m);
    expect(body).toMatch(/^ {8}max-file: "?\d+"?\s*$/m);
  });
});
