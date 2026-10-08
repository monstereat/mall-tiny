export interface WhiteScreenCheckOptions {
  rootSelectors?: string[];
  skeletonSelectors?: string[];
}

const DEFAULT_ROOT_SELECTORS = ['#app', '#root', '[data-monitor-root]'];
const DEFAULT_SKELETON_SELECTORS = [
  '[data-skeleton]',
  '.skeleton',
  '.skeleton-screen',
  '.skeleton-loader',
  '.ant-skeleton'
];
const EMPTY_TAGS = new Set(['HTML', 'BODY']);
const SAMPLE_FRACTIONS = [0.2, 0.5, 0.8];
const VISUAL_TAGS = new Set(['IMG', 'SVG', 'CANVAS', 'VIDEO', 'IFRAME', 'INPUT', 'BUTTON']);

function matchesAny(node: Element, selectors: string[]): boolean {
  return selectors.some(selector => {
    try {
      return node.matches(selector);
    } catch {
      return false;
    }
  });
}

function isSkeleton(node: Element, selectors: string[]): boolean {
  let current: Element | null = node;
  while (current) {
    if (matchesAny(current, selectors)) return true;
    current = current.parentElement;
  }
  return false;
}

function hasNonSkeletonContent(root: Element, skeletonSelectors: string[]): boolean {
  const pending = [root];
  let visited = 0;
  while (pending.length > 0) {
    // A large tree is inconclusive; do not block rendering or report a guessed white screen.
    if (++visited > 2048) return true;
    const node = pending.pop()!;
    if (['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE'].includes(node.tagName)
        || (node as HTMLElement).hidden) continue;
    if (isSkeleton(node, skeletonSelectors)) continue;
    const children = Array.from(node.childNodes);
    if (children.some(child => child.nodeType === 3 && child.textContent?.trim())) return true;
    if (VISUAL_TAGS.has(node.tagName)) {
      const rect = node.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) return true;
    }
    for (const child of children) {
      if (child.nodeType === 1 && 'tagName' in child) pending.push(child as Element);
    }
  }
  return false;
}

export function isWhiteScreen(
  documentRef: Document,
  width: number,
  height: number,
  options: WhiteScreenCheckOptions = {}
): boolean {
  if (typeof documentRef.elementFromPoint !== 'function' || width <= 0 || height <= 0) return false;
  const rootSelectors = options.rootSelectors ?? DEFAULT_ROOT_SELECTORS;
  const skeletonSelectors = options.skeletonSelectors ?? DEFAULT_SKELETON_SELECTORS;
  const roots = rootSelectors.flatMap(selector => {
    try {
      return Array.from(documentRef.querySelectorAll(selector));
    } catch {
      return [];
    }
  });

  if (roots.length > 0) {
    return !roots.some(root => hasNonSkeletonContent(root, skeletonSelectors));
  }

  let contentCount = 0;
  for (const yFraction of SAMPLE_FRACTIONS) {
    for (const xFraction of SAMPLE_FRACTIONS) {
      const node = documentRef.elementFromPoint(width * xFraction, height * yFraction);
      if (!node || EMPTY_TAGS.has(node.tagName) || isSkeleton(node, skeletonSelectors)) continue;
      contentCount += 1;
    }
  }

  return contentCount <= 2;
}
