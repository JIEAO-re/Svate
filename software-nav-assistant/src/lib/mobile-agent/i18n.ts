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

export function containsAnyHint(text: string, hints: string[]): boolean {
  const normalized = text.trim().toLowerCase();
  if (!normalized) return false;
  return hints.some((hint) => normalized.includes(hint.toLowerCase()));
}
