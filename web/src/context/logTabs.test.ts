import { describe, expect, it } from 'vitest';
import type { StackFrameDto } from '../api/types';
import { formatOffset, serviceColorIndex, serviceColors } from './sameRequest';
import { artifactOf, frameItems, frameText } from './stackTrace';

const f = (inProject: boolean, name = 'X'): StackFrameDto => ({
  className: `a.${name}`,
  method: 'm',
  file: `${name}.java`,
  line: 1,
  inProject,
  codeUnit: null,
  fileId: null,
  githubUrl: null,
});

const shape = (items: ReturnType<typeof frameItems>) =>
  items.map((item) =>
    item.kind === 'frame' ? item.index : `fold ${item.start}+${item.frames.length}`,
  );

describe('frameItems', () => {
  const frames = [f(false), f(false), f(true), f(false), f(true), f(false), f(false), f(false)];

  it('shows every frame when library frames are not hidden', () => {
    expect(shape(frameItems(frames, false))).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
  });

  it('folds runs of two or more library frames, keeps single ones', () => {
    expect(shape(frameItems(frames, true))).toEqual(['fold 0+2', 2, 3, 4, 'fold 5+3']);
  });

  it('shows an expanded run frame by frame', () => {
    expect(shape(frameItems(frames, true, new Set([5])))).toEqual(['fold 0+2', 2, 3, 4, 5, 6, 7]);
  });

  it('folds a trace without project frames into one row', () => {
    expect(shape(frameItems([f(false), f(false), f(false)], true))).toEqual(['fold 0+3']);
  });
});

describe('frameText', () => {
  it('prints a frame like Java does', () => {
    expect(frameText(f(true, 'Owner'))).toBe('a.Owner.m(Owner.java:1)');
    expect(frameText({ ...f(false), line: null })).toBe('a.X.m(X.java)');
    expect(frameText({ ...f(false), file: null, line: null })).toBe('a.X.m(Unknown Source)');
  });

  it('shortens a code unit to its artifact', () => {
    expect(artifactOf('org.springframework:spring-webmvc')).toBe('spring-webmvc');
    expect(artifactOf('spring-petclinic-microservices')).toBe('spring-petclinic-microservices');
    expect(artifactOf(null)).toBeNull();
  });
});

describe('service colors', () => {
  const PETCLINIC = [
    'config-server',
    'discovery-server',
    'customers-service',
    'visits-service',
    'vets-service',
    'api-gateway',
  ];

  it('are stable for a name and within 1..8', () => {
    for (const name of PETCLINIC) {
      expect(serviceColorIndex(name)).toBe(serviceColorIndex(name));
      expect(serviceColorIndex(name)).toBeGreaterThanOrEqual(1);
      expect(serviceColorIndex(name)).toBeLessThanOrEqual(8);
    }
  });

  it('give the services of one list different colors, whatever their order', () => {
    const colors = serviceColors(PETCLINIC);
    expect(new Set(colors.values()).size).toBe(PETCLINIC.length);
    expect(serviceColors([...PETCLINIC].reverse())).toEqual(colors);
    // A service alone keeps its hash color.
    expect(serviceColors(['vets-service']).get('vets-service')).toBe(
      `var(--service-${serviceColorIndex('vets-service')})`,
    );
  });
});

describe('formatOffset', () => {
  const t0 = '2026-09-23T20:05:44.700Z';
  it('formats the time since the first log', () => {
    expect(formatOffset(t0, t0)).toBe('+0 ms');
    expect(formatOffset(t0, '2026-09-23T20:05:44.712Z')).toBe('+12 ms');
    expect(formatOffset(t0, '2026-09-23T20:05:46.100Z')).toBe('+1.400 s');
    expect(formatOffset(t0, '2026-09-23T20:07:48.100Z')).toBe('+2 min 3.4 s');
    expect(formatOffset(t0, null)).toBe('');
  });
});
