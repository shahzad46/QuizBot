# QuizBot — project notes for Claude

Hobby Android app that plays the **Nusuk** app's daily Umrah-draw quiz on the owner's phones
(a Redmi 12C `xiaomi-22120rn86g` and an Infinix Hot 50). The owner builds nothing locally:
**GitHub Actions builds every APK**, and the phones install it from the Releases page.

## How it works
- `QuizService.kt` — an **AccessibilityService** (no Developer options / USB debugging: Nusuk refuses
  to open when those are on). It polls the screen every 1.2 s (Nusuk animates constantly, so
  "wait until the screen settles" never fires) and runs the loop:
  `Choose a door` → sheet "Do you want to use your key?" → **Open the door** → question →
  tap answer → **Check your answer** → result sheet ("Correct! …" / "Wrong answer …") →
  **Play again** → … Stops when keys reach 0 or the answer limit is hit (switches itself off).
- Nusuk package: `com.moh.nusukapp`. It exposes its text to accessibility (React Native).
  The result sheet can sit *over* the question, so the result check runs before the question check.
- `AnswerBank.kt` — offline answer list. Sources, in load order:
  1. `remote_answers.json` = `app/src/main/assets/answers.json` downloaded from GitHub raw (falls back to the bundled copy)
  2. `shared_learned.json` = the common learned list `learned/shared.json` from GitHub
  3. `learned.json` = this phone's own learned answers
  Matching: normalise text (`norm`), rank entries by the **longest key phrase contained in the
  question**, then the first answer variant that matches exactly one on-screen option wins
  (exact > variant ⊂ option > option ⊂ variant; ties = no match). A wrong lookup therefore can't
  tap a random option. Unknown question → the bot waits for the user to tap.
- Learning from the user's tap: on these phones Nusuk sends **no click event and no selected
  state**, so the reliable method is **screenshots** (`takeScreenshot`, Android 11+,
  `canTakeScreenshot` in `res/xml/quiz_service.xml`): sample each option's button pixels when the
  question appears, and the option whose pixels change is the choice. Fallbacks: click event text,
  click bounds, `isSelected`, accessibility "fingerprint" diff. Only saved if the result is **Correct**
  (waits up to 3 s for the result headline).
- `GitHubSync.kt` — one **common learned list** `learned/shared.json` in the repo. Any phone with a
  token (fine-grained PAT, Contents: read & write on this repo) merges its learned answers in:
  dedup by normalised question, new answer wordings appended, questions already in the main list
  skipped, optimistic concurrency via the file `sha` with retries on 409/422.
- `KeyCollector.kt` — "Get new keys", two steps the user starts separately:
  **1. Collect keys** opens nusukkey.xyz once, reads the featured code and the "More codes" list
  (codes are found *inside* longer texts, e.g. "NG-ab12CD34 · 206 uses Open"; the site has no "Show 20 new codes"
  button any more, so it reloads the page until no new codes appear; max 4 min, max 500 saved; if nothing is
  found after 15 s it dumps the browser screen to the log), saves them in prefs
  (`stored_codes`), shows a notification "N keys collected" and switches itself off.
  **2. Enter keys**: the user opens Nusuk's **Your keys** → "Add code to get new key" page themselves; the bot
  waits for the code box + **Check the code**, types each saved code (paste fallback) and presses Check with a real tap gesture (Nusuk ignores ACTION_CLICK there; keyboard hidden via softKeyboardController, retries twice); tried
  codes leave the list; stops after 10 failures in a row. Do NOT call nusukkey.xyz's internal API — only use
  the page like a person.
- `MainActivity.kt` — control panel built in code (no layout XML).
- `BotLog.kt` — the log shown in the app (newest first). Users paste it into chat; it's the main debug tool.

## Build & release
- Push to `main` → `.github/workflows/build.yml` builds `assembleDebug` and publishes a release
  `build-<run number>` with `QuizBot.apk`. Download link:
  `https://github.com/shahzad46/QuizBot/releases/download/build-N/QuizBot.apk`
- Commits that only touch `app/src/main/assets/answers.json` or `learned/**` do **not** build an APK
  (the app downloads those at runtime) — answer updates never need a reinstall.
- All builds are signed with `signing/quizbot.jks` (password `quizbot-hobby`) and `versionCode` =
  run number, so new APKs install as updates. Never change the key or phones must uninstall.
- No Android SDK in Claude's cloud sandbox; a CI failure shows the Kotlin error via
  `gh run view <id> --log-failed | grep "e: "`. Watch out for `/*` inside KDoc comments (nested comment!).
- The phones also push to `learned/shared.json`, so `git pull --rebase` before pushing.

## Owner's phone facts (Xiaomi/MIUI)
- After **every app update Android switches the accessibility service off** — tell the user to turn
  "QuizBot auto-answer" back on. Avoid app updates when only answers change.
- MIUI kills background services: Autostart ON, Battery saver "No restrictions", lock in Recents.
  "Display pop-up windows while running in the background" is needed for Get new keys.
- The status line checks a heartbeat (`QuizService.isAlive()`), not just the Settings flag.

## Adding answers (most common task)
- Format in `answers.json`: `{"keys": ["phrase from the question", ...], "a": ["exact option text", "variants"...]}`
- Best: add the **exact question text** as the first key and the **exact option text** as the first answer
  (copy from the bot's log line `UNKNOWN: "…"` and its `options:` line), plus a few looser key phrases.
- Check with `python tools/test_matching.py "question" "opt1" "opt2" "opt3" "opt4"` and
  `python tools/test_matching.py --check`, then commit only `answers.json` and push.
- Removed on purpose: Gemini/online lookup (free key had no quota) and Wikipedia lookup (confidently wrong).
