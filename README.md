# Book Reader (Android)

Main menu with a "Continue reading" card, an "Add new book" button and a library list.
Your place is saved automatically whenever you leave the app and restored when you come back.

- Supported files: PDF, EPUB and TXT. Bottom bar switches between Home and a cover-grid Library.
- Imported books are copied into the app's private storage.
- Long-press a book for shelf, cover and remove options. Library menu (⋮) has backup, restore and reading theme.

## Build the APK

### Option A: Android Studio
1. Open this folder in Android Studio and let Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. The file is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install it
   (allow "install unknown apps" when asked).

### Option B: No installs, via GitHub
1. Create a GitHub repo and push this folder to it.
2. Open the Actions tab, run "Build APK" (it also runs on every push).
3. Download the `book-reader-apk` artifact, unzip it and install `app-debug.apk`.

Note: this project was written without being compiled, so if the build reports an error, send it back and it can be fixed.
