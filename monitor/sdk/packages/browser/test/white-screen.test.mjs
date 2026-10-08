import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isWhiteScreen } from '../dist/white-screen.js';

function element(tagName, options = {}) {
  const node = {
    nodeType: 1,
    tagName,
    hidden: options.hidden ?? false,
    parentElement: null,
    childNodes: options.text ? [{ nodeType: 3, textContent: options.text }] : [],
    textContent: options.text ?? '',
    matches(selector) { return options.selectors?.includes(selector) ?? false; },
    getBoundingClientRect() { return { width: 20, height: 20 }; },
    querySelectorAll(selector) {
      if (selector !== '*') return [];
      const descendants = [];
      const visit = current => {
        for (const child of current.childNodes) {
          if (child.nodeType === 1) {
            descendants.push(child);
            visit(child);
          }
        }
      };
      visit(node);
      return descendants;
    }
  };
  for (const child of options.children ?? []) {
    child.parentElement = node;
    node.childNodes.push(child);
  }
  return node;
}

function documentWith(root, pointNode) {
  return {
    querySelectorAll(selector) { return selector === '#app' && root ? [root] : []; },
    elementFromPoint() { return pointNode; }
  };
}

test('a skeleton-only app root is treated as blank after the caller confirms it', () => {
  const skeleton = element('DIV', { selectors: ['.skeleton'], text: 'Loading' });
  const root = element('DIV', { selectors: ['#app'], children: [skeleton] });
  assert.equal(isWhiteScreen(documentWith(root, skeleton), 1000, 800), true);
});

test('rendered non-skeleton root content prevents a white-screen result', () => {
  const content = element('DIV', { text: 'Page content' });
  const root = element('DIV', { selectors: ['#app'], children: [content] });
  assert.equal(isWhiteScreen(documentWith(root, content), 1000, 800), false);
});

test('viewport sampling works when the application has no standard root selector', () => {
  const body = element('DIV');
  const documentRef = documentWith(undefined, body);
  assert.equal(isWhiteScreen(documentRef, 1000, 800), false);

  const emptyDocument = documentWith(undefined, element('BODY'));
  assert.equal(isWhiteScreen(emptyDocument, 1000, 800), true);
});

test('script/style text and hidden content do not count as rendered application content', () => {
  const script = element('SCRIPT', { text: 'console.log("code")' });
  const hidden = element('DIV', { text: 'Hidden content', hidden: true });
  const root = element('DIV', { selectors: ['#app'], children: [script, hidden] });
  assert.equal(isWhiteScreen(documentWith(root, root), 1000, 800), true);
});
