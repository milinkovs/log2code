import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// jsdom has no layout engine: provide the browser APIs react-resizable-panels relies on.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

// The virtualized log list reads its viewport from offsetHeight/offsetWidth, which jsdom reports
// as 0 (nothing would render). Give the list a fixed viewport: 600 px = about 21 rows of 28 px.
export const LIST_VIEWPORT_HEIGHT = 600;
// Its scrollHeight is the height of the virtual canvas (the first child), as in a browser.
const listSize: Record<string, (list: HTMLElement) => number> = {
  offsetHeight: () => LIST_VIEWPORT_HEIGHT,
  clientHeight: () => LIST_VIEWPORT_HEIGHT,
  offsetWidth: () => 800,
  clientWidth: () => 800,
  scrollHeight: (list) =>
    parseFloat((list.firstElementChild as HTMLElement | null)?.style.height ?? '') || 0,
};
for (const [prop, size] of Object.entries(listSize)) {
  const owner = prop in HTMLElement.prototype ? HTMLElement.prototype : Element.prototype;
  const original = Object.getOwnPropertyDescriptor(owner, prop);
  Object.defineProperty(owner, prop, {
    configurable: true,
    get(this: HTMLElement) {
      if (this.classList.contains('log-list')) return size(this);
      return original?.get?.call(this) ?? 0;
    },
  });
}

// Radix menus and popovers call these; jsdom does not implement them.
Element.prototype.scrollIntoView ??= () => {};
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => {};

// jsdom lays every element out at (0,0) with size 0, and pointer events default to (0,0), so the
// resizable-panel separators would claim every pointerdown (and preventDefault it, which makes
// Radix ignore clicks on menu triggers). Move their hit area off-screen.
const originalRect = Element.prototype.getBoundingClientRect;
Element.prototype.getBoundingClientRect = function (this: Element) {
  if (this.hasAttribute('data-separator')) return new DOMRect(-10_000, -10_000, 8, 8);
  return originalRect.call(this);
};

// jsdom has no Element.scrollTo; the virtualizer uses it to bring a row into view.
Element.prototype.scrollTo ??= function (
  this: Element,
  options?: ScrollToOptions | number,
  y?: number,
) {
  const top = typeof options === 'number' ? y : options?.top;
  if (top === undefined) return;
  this.scrollTop = top;
  this.dispatchEvent(new Event('scroll'));
} as Element['scrollTo'];
