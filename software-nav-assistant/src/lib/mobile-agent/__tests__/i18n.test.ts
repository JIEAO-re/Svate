/**
 * Word-boundary hint matching tests for i18n.ts.
 *
 * Short Latin hints ("go", "pay", ...) must match whole words only so they do
 * not false-positive on "Google" or "prepaid", while still matching inside
 * snake_case resource ids like "search_btn". CJK hints have no word
 * delimiters and must keep substring matching.
 */
import { describe, it, expect } from "vitest";
import {
  containsAnyHint,
  SEARCH_INTENT_HINTS,
  SEARCH_SUBMIT_HINTS,
  HIGH_RISK_KEYWORDS,
} from "@/lib/mobile-agent/i18n";

describe("containsAnyHint Latin word-boundary matching", () => {
  it("does not match 'go' inside 'Google'", () => {
    expect(containsAnyHint("Google", SEARCH_SUBMIT_HINTS)).toBe(false);
    expect(containsAnyHint("Open Google app", SEARCH_SUBMIT_HINTS)).toBe(false);
  });

  it("matches a standalone 'Go' submit label", () => {
    expect(containsAnyHint("Go", SEARCH_SUBMIT_HINTS)).toBe(true);
    expect(containsAnyHint("Tap go to continue", SEARCH_SUBMIT_HINTS)).toBe(true);
  });

  it("does not match 'pay' inside 'prepaid'", () => {
    expect(containsAnyHint("prepaid card", HIGH_RISK_KEYWORDS)).toBe(false);
  });

  it("matches 'pay' as a standalone word", () => {
    expect(containsAnyHint("Pay now", HIGH_RISK_KEYWORDS)).toBe(true);
  });

  it("matches 'search' inside snake_case resource ids like 'search_btn'", () => {
    expect(containsAnyHint("com.example.app:id/search_btn", SEARCH_INTENT_HINTS)).toBe(true);
    expect(containsAnyHint("btn_search", SEARCH_INTENT_HINTS)).toBe(true);
  });

  it("does not match 'find' embedded in a longer word", () => {
    expect(containsAnyHint("wayfinding sign", SEARCH_INTENT_HINTS)).toBe(false);
  });

  it("matches hints case-insensitively", () => {
    expect(containsAnyHint("SEARCH here", SEARCH_INTENT_HINTS)).toBe(true);
  });
});

describe("containsAnyHint CJK substring matching", () => {
  it("matches a Chinese hint inside undelimited text", () => {
    expect(containsAnyHint("点击搜索按钮", SEARCH_INTENT_HINTS)).toBe(true);
  });

  it("matches a Chinese hint inside mixed-script text", () => {
    expect(containsAnyHint("tap查询now", SEARCH_INTENT_HINTS)).toBe(true);
  });

  it("matches a Chinese high-risk keyword as substring", () => {
    expect(containsAnyHint("立即支付订单", HIGH_RISK_KEYWORDS)).toBe(true);
  });

  it("does not match unrelated Chinese text", () => {
    expect(containsAnyHint("打开相册", SEARCH_INTENT_HINTS)).toBe(false);
  });
});

describe("containsAnyHint edge cases", () => {
  it("returns false for empty or whitespace-only text", () => {
    expect(containsAnyHint("", SEARCH_INTENT_HINTS)).toBe(false);
    expect(containsAnyHint("   ", SEARCH_INTENT_HINTS)).toBe(false);
  });

  it("returns false when no hint is present", () => {
    expect(containsAnyHint("open settings page", SEARCH_SUBMIT_HINTS)).toBe(false);
  });
});
