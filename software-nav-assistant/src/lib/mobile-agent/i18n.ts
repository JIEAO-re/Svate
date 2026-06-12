export const SEARCH_INTENT_HINTS = [
  "search",
  "find",
  "query",
  "lookup",
  "搜索",
  "查找",
  "查询",
  "检索",
];

export const SEARCH_SUBMIT_HINTS = [
  "search",
  "go",
  "done",
  "enter",
  "submit",
  "搜索",
  "前往",
  "确认",
  "完成",
  "提交",
];

export const HIGH_RISK_KEYWORDS = [
  "pay",
  "payment",
  "transfer",
  "password",
  "authorize",
  "授权",
  "支付",
  "转账",
  "密码",
  "删除账号",
];

// CJK scripts (Han, Hiragana, Katakana, Hangul) have no word delimiters, so
// hints containing them must keep substring matching. Short Latin hints such
// as "go" or "pay" use word-boundary matching instead, so they no longer
// false-positive on "Google", "prepaid", "Logout", etc.
// Ranges: Hiragana/Katakana, CJK Ext A, CJK Unified, Hangul, Compatibility Ideographs.
const CJK_PATTERN =
  /[぀-ヿ㐀-䶿一-鿿가-힯豈-﫿]/;

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Cache compiled word-boundary patterns; the hint lists are small constants.
const wordBoundaryPatternCache = new Map<string, RegExp>();

function getWordBoundaryPattern(hint: string): RegExp {
  let pattern = wordBoundaryPatternCache.get(hint);
  if (!pattern) {
    // Custom boundaries instead of \b so hints still match inside snake_case
    // resource ids like "search_btn" while "go" cannot match "google".
    pattern = new RegExp(`(?:^|[^a-z0-9])${escapeRegExp(hint)}(?:[^a-z0-9]|$)`);
    wordBoundaryPatternCache.set(hint, pattern);
  }
  return pattern;
}

export function containsAnyHint(text: string, hints: string[]): boolean {
  const normalized = text.trim().toLowerCase();
  if (!normalized) return false;
  return hints.some((hint) => {
    const normalizedHint = hint.toLowerCase();
    if (CJK_PATTERN.test(normalizedHint)) {
      return normalized.includes(normalizedHint);
    }
    return getWordBoundaryPattern(normalizedHint).test(normalized);
  });
}
