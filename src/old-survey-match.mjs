// Old survey numbers — §2. Desktop half; the app's is data/sync/OldSurveyMatcher.kt.
// Both are held to the `oldSurveyMatch` section of tools/identity/vectors.json.
//
// VF-7/12's `ddlOldScannedSno` numbers do not map 1:1 onto current survey numbers. We
// auto-match, download what we can, and THE USER decides. Rejections are stored, because
// otherwise auto-matching re-proposes the same wrong candidates forever.

import { surveyToken, surveyLinkUid } from './identity.mjs';

/** How many candidates the user gets to see before being asked to widen the fetch (§2). */
export const FETCH_WITHOUT_ASKING = 5;

export const leadingNumber = (token) => {
  const m = /^(\d+)/.exec(token);
  return m ? Number(m[1]) : null;
};

const distance = (a, b) => (a == null || b == null ? Number.MAX_SAFE_INTEGER : Math.abs(a - b));

/**
 * Exact token match first, then nearest numerically to the current survey's leading block
 * number, then by token — a stable order, so two equally-distant numbers never depend on
 * the order the DOM happened to list them in.
 */
export function rank(currentToken, options) {
  const current = surveyToken(currentToken);
  const target = leadingNumber(current);
  const seen = new Set();
  return options
    .map((raw) => ({ raw, token: surveyToken(raw), exact: surveyToken(raw) === current }))
    .filter((c) => c.token && !seen.has(c.token) && (seen.add(c.token), true))
    .sort((a, b) =>
      (a.exact === b.exact ? 0 : a.exact ? -1 : 1) ||
      distance(leadingNumber(a.token), target) - distance(leadingNumber(b.token), target) ||
      (a.token < b.token ? -1 : a.token > b.token ? 1 : 0));
}

/**
 * Candidates are fetched BEFORE the user curates, so he judges actual documents rather than
 * bare numbers — but a wide match must not silently become twenty captchas.
 *   <= 5 candidates -> fetch all, then ask.
 *   >  5 candidates -> fetch the first 5, then ask before fetching the rest.
 */
export function plan(currentToken, options) {
  const ranked = rank(currentToken, options);
  const fetchNow = ranked.slice(0, FETCH_WITHOUT_ASKING);
  const deferred = ranked.slice(FETCH_WITHOUT_ASKING);
  return { fetchNow, deferred, mustAsk: deferred.length > 0 };
}

export function linkRow(currentSurveyUid, oldToken, state, source) {
  return {
    uid: surveyLinkUid(currentSurveyUid, oldToken),
    current_survey_uid: currentSurveyUid,
    old_token: oldToken,
    state,
    source,
    deleted: 0,
  };
}

/**
 * Which ranked candidates still need a decision. A previously `rejected` token is
 * deliberately NOT re-offered — rule 1, and the whole reason rejections are stored.
 */
export function needsDecision(ranked, existing) {
  return ranked.filter((c) => (existing[c.token] ?? 'candidate') === 'candidate');
}
