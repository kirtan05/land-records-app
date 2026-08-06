// Normalizes survey-number strings so handwritten input matches dropdown values.
// Dropdown values look like:  "21/૧ ~~ "  "11/p૩ ~~ "  "2/પ ~~ "  "6/૧/અ ~~ "
// Rules:
//   - strip the " ~~ " marker and surrounding whitespace
//   - Gujarati digits  ૦૧૨૩૪૫૬૭૮૯ -> 0123456789
//   - Gujarati "પ" (paiki) -> "p"
//   - collapse any run of spaces/slashes into a single "/"  (so "221 p" == "221/p")
//   - lowercase
const GU_DIGITS = { '૦': '0', '૧': '1', '૨': '2', '૩': '3', '૪': '4', '૫': '5', '૬': '6', '૭': '7', '૮': '8', '૯': '9' };

export function normalizeSurvey(raw) {
  if (raw == null) return '';
  let s = String(raw);
  s = s.replace(/~~/g, ' ');                       // drop the ~~ marker
  s = s.replace(/[૦-૯]/g, (c) => GU_DIGITS[c] ?? c); // gujarati -> latin digits
  s = s.replace(/પ/g, 'p');                         // paiki letter -> p
  s = s.toLowerCase();
  s = s.replace(/[\s/|\\]+/g, '/');                 // unify separators (incl. handwritten | bars)
  s = s.replace(/^\/+|\/+$/g, '');                  // trim leading/trailing slash
  s = s.replace(/p\/(?=\d)/g, 'p');                 // paiki attaches to its number: p/1 -> p1
  return s;
}

// Filesystem-safe token for a survey key:  "221/p" -> "221_P",  "222/3/p1" -> "222_3_P1"
export function surveyToken(keyOrRaw) {
  return normalizeSurvey(keyOrRaw).toUpperCase().replace(/\//g, '_').replace(/[^A-Z0-9_]/g, '');
}

// Build a lookup map: normalizedKey -> { value, text } from dropdown options.
export function buildCatalog(options) {
  const byKey = new Map();
  for (const o of options) {
    if (o.value === '-1' || /select/i.test(o.text)) continue;
    const key = normalizeSurvey(o.text);
    if (!key) continue;
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push({ value: o.value, text: o.text.trim() });
  }
  return byKey;
}
