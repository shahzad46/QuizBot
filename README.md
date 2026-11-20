# QuizBot - offline auto-answer for the Nusuk quiz

An Android accessibility service. Developer options and USB debugging stay OFF.

## Build (Android Studio, on your PC)
1. Install Android Studio (developer.android.com/studio).
2. File > Open > select this `QuizBot` folder. Let Gradle sync (first time downloads a few hundred MB).
3. Build > Build App Bundle(s) / APK(s) > **Build APK(s)**.
   The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install (on the phone)
1. Copy `app-debug.apk` to the phone (USB "File transfer", Google Drive, WhatsApp to yourself...).
2. Open it from Files; allow "Install unknown apps" for that file manager when asked.
3. Open **QuizBot** > tap step 1 > Accessibility > Installed apps > **QuizBot auto-answer** > ON.
   (Android 13+: if it says "Restricted setting", go to Settings > Apps > QuizBot > ⋮ > Allow restricted settings, then retry.)

## First run: check what the bot can see
1. In QuizBot tap **Dump the next screen to the log**, then switch to the Nusuk quiz question.
2. Back in QuizBot, tap **Refresh log**. You should see the question, four options and
   "Check your answer" listed. If the dump is empty, Nusuk hides its text from accessibility
   and the bot needs an OCR upgrade (ask Claude).

## Run
Turn on **Bot running**, open the quiz. For each question it will:
- known question  -> tap the right option, then "Check your answer", then Next/Continue
- unknown question -> show a toast and wait; tap the answer yourself and it is **learned**
  (saved in learned.json) for next time.
It stops after the number of answers you set (default 30).

## Extending the answer bank
`app/src/main/assets/answers.json`, one line per fact:
`{"keys": ["phrase from the question"], "a": ["Answer", "Alternative spelling"]}`
The answer is only tapped if it matches one of the options on screen.
