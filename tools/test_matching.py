#!/usr/bin/env python3
"""
Offline copy of QuizBot's answer matching (AnswerBank.kt), for checking answers.json
before pushing. Usage:
  python tools/test_matching.py "Question text?" "Option 1" "Option 2" "Option 3" "Option 4"
  python tools/test_matching.py --check      # validate the JSON and report empty keys/answers
Keep this in sync with AnswerBank.kt (norm, tokens, matchOption, answer).
"""
import json, re, sys, unicodedata, os

HERE = os.path.dirname(os.path.abspath(__file__))
ANSWERS = os.path.join(HERE, "..", "app", "src", "main", "assets", "answers.json")
ARTICLES = {"al", "an", "ar", "as", "at", "ash", "ad", "az", "surah", "surat", "sura", "the"}

def norm(s):
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()
    s = re.sub(r"[’‘'`ʿʾ\"“”]", "", s)
    return re.sub(r"[^a-z0-9]+", " ", s).strip()

def tokens(s):
    t = [w for w in norm(s).split(" ") if w]
    return [w for i, w in enumerate(t) if not (i == 0 and w in ARTICLES and len(t) > 1)]

def match_option(variant, options):
    v = tokens(variant)
    if not v: return None
    vc = "".join(v); scores = []
    for o in options:
        ot = tokens(o)
        scores.append(0 if not ot else 3 if "".join(ot) == vc else 2 if set(v) <= set(ot) else 1 if set(ot) <= set(v) else 0)
    top = max(scores); idx = [i for i, s in enumerate(scores) if s == top]
    return idx[0] if top and len(idx) == 1 else None

def load():
    return [([norm(k) for k in e["keys"]], e["a"]) for e in json.load(open(ANSWERS, encoding="utf-8"))]

def answer(q, options, bank):
    qn = norm(q)
    ranked = sorted([(max(len(k) for k in ks if k and k in qn), a) for ks, a in bank if any(k and k in qn for k in ks)],
                    key=lambda x: -x[0])
    for _, a in ranked:
        for v in a:
            i = match_option(v, options)
            if i is not None: return options[i], v
    return None

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        data = json.load(open(ANSWERS, encoding="utf-8"))
        bad = [i for i, e in enumerate(data) if not e.get("keys") or not e.get("a")]
        print(f"{len(data)} entries; {len(bad)} with empty keys/answers: {bad[:20]}")
    elif len(sys.argv) >= 4:
        print(answer(sys.argv[1], sys.argv[2:], load()) or "NO MATCH (the bot would ask you)")
    else:
        print(__doc__)
