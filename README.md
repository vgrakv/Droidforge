# DroidForge

An Android IDE that builds real Gradle-based Android apps **on the device**, with a built-in
**on-device AI helper that explains build errors** (Hebrew or English, no tokens, no accounts).

DroidForge is a fork of [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE)
(GPL-3.0, no longer maintained upstream). All of AndroidIDE's features are kept. This fork is
licensed under the same **GPL-3.0**; see `LICENSE`.

## What was added

An **AI helper that explains build errors** (Hebrew or English), with five modes chosen by the user
on first run (nothing is downloaded before that) and changeable any time in
*Settings -> AI helper* or the "AI settings" button of the *Explain errors* tab:

| Mode | What it does | Needs |
|---|---|---|
| 0. Off | Built-in hand-written hints only | nothing |
| 1. Basic | Small local model (Qwen2.5 0.5B) decodes the error, in English | ~0.55 GB, works offline |
| 2. Basic + Hebrew | Mode 1 + a second local model (Qwen2.5 1.5B) translates the answer to Hebrew | ~2.2 GB, works offline |
| 3. Heavy | One large local model (Phi-4 mini) explains in Hebrew **and proposes code fixes** you can apply/undo | ~3.9 GB, strong phone, offline |
| 4. Online | Your own API token with Anthropic, OpenAI, Gemini, Groq, OpenRouter, Mistral, DeepSeek, Together, Cerebras, Hugging Face, or any OpenAI-compatible server | internet + token |

A **"Check my device"** button rates every model/mode as recommended / not recommended / impossible
(RAM, free space, CPU). The online screen lists each provider's abilities (decoding / Hebrew /
auto-fix), whether it has a free tier, and a button that opens the page where you create the token.
Tokens are stored encrypted with the Android Keystore. After the first setup a short **app tour** runs
(replayable from the settings).

### Models: download them yourself, or load them from a file
Nothing has to be downloaded by the app. In *AI settings* (and on the first-run screen) there is a
**"Load a model from a file"** button: download a `.task` file with a browser (or on a computer), pick it
with the file picker, and the app copies and checks it (ZIP header, minimum size, free space). The app
recognises the model from the file name (the Hugging Face name, also with a browser's "(1)" suffix) or
asks which model it is. The same screen lists the direct download link of each model with
"Open link" / "Copy link" buttons.

| Model | Used by | File name on Hugging Face | Size |
|---|---|---|---|
| Qwen2.5 0.5B | modes 1, 2 | `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` | ~0.55 GB |
| Qwen2.5 1.5B | mode 2 | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task` | ~1.6 GB |
| Phi-4 mini | mode 3 | `Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task` | ~3.9 GB |

All three are in `https://huggingface.co/litert-community/<repo>/resolve/main/<file name>`
(repos `Qwen2.5-0.5B-Instruct`, `Qwen2.5-1.5B-Instruct`, `Phi-4-mini-instruct`), public and ungated.

### Automatic fixes
Modes 3 and 4 add a "Suggest an automatic fix" button on each error. The model answers with
SEARCH/REPLACE edit blocks for the file the error is in; the app validates that they apply exactly once,
shows a `-`/`+` preview, and only changes the file after you tap "Apply fix". "Undo fix" restores it.

### Code layout
Everything lives in `core/app/src/main/java/com/itsaky/androidide/ai/`:

| File | Role |
|---|---|
| `BuildErrorParser.kt`, `ErrorKnowledgeBase.kt` | Parse javac/kotlinc/aapt2/Gradle output; ~20 hand-written explanations (he/en) |
| `PromptBuilder.kt`, `FixParser.kt` | Prompts (explain / translate / fix) and the SEARCH/REPLACE parser + safe applier |
| `ModelCatalog.kt`, `ModelDownloader.kt`, `ModelManager.kt`, `ModelDownloadWorker.kt`, `ModelImporter.kt` | Model list, resumable download, copy-from-APK, background install, import from a file |
| `LlmEngine.kt` | MediaPipe LLM Inference (on-device) |
| `OnlineProvider.kt`, `OnlineClient.kt`, `SecretStore.kt` | Provider catalog, request/response building, HTTP, encrypted token storage |
| `DeviceAdvisor.kt`, `DeviceProbe.kt` | Device check and verdicts |
| `ErrorExplainer.kt` | Orchestrates modes: collect log, parse, hints, models, translation, fixes |
| `AiExplainFragment.kt`, `AiSetupActivity.kt`, `AiTourActivity.kt`, `AiStrings.kt` | The UI (built from code) |

Hooks into existing code: `EditorBuildEventListener`, `EditorBottomSheetTabAdapter`, `IDEApplication`,
`MainActivity` (first-run chooser), `rootPrefExts.kt` + `aiPrefExts.kt` (settings entry),
`AndroidManifest.xml`, `core/app/build.gradle.kts`, `gradle/libs.versions.toml`, `proguard-rules.pro`.

Local modes never send anything over the network except the one-time model download from
Hugging Face (public, ungated Apache-2.0/MIT models from `litert-community`). Online mode sends the error
and the code around it to the provider you chose.

## Building

Requirements: JDK 17+, Android SDK (compileSdk 34), and an internet connection for Gradle.

```
./gradlew :core:app:assembleDebug
```

APKs are produced per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) under `core/app/build/outputs/apk`.
Install the `arm64-v8a` one on a modern phone.

Unit tests for the AI logic: `./gradlew :core:app:testDebugUnitTest --tests "com.itsaky.androidide.ai.*"`

### Offline APKs with the models inside
```
./gradlew :core:app:assembleDebug -PaiBundle=basic    # small model inside (~0.6 GB APK)
./gradlew :core:app:assembleDebug -PaiBundle=hebrew   # small model + Hebrew translator (~2.2 GB APK)
```
The models are downloaded at build time (see `core/app/ai-models.properties`), stored uncompressed in
the APK, and copied out on first run (no internet needed). Bundled builds are `arm64-v8a` only. The heavy
model (~3.9 GB) cannot be bundled. Without `-PaiBundle` you get the small "lite" APK that downloads the
chosen models on demand. The GitHub workflow in this package builds all three variants.

## Notes

* The application id is still `com.itsaky.androidide`. The terminal/toolchain packages that
  AndroidIDE downloads are built for that exact install path, so changing it breaks them. The
  visible app name is changed (`app_name` in `core/resources/.../values/strings.xml`).
  Because of the shared id, it cannot be installed next to the original AndroidIDE.
* `targetSdk` stays at 28 (inherited), which is what allows running binaries from the app's
  data directory. This means the app cannot be published on Google Play.
* To change a model, edit `ModelCatalog.kt` (and `ai-models.properties` for bundled builds; a test checks they match). The file must be a MediaPipe
  `.task` bundle whose context window matches `contextTokens`.
* Small models can be wrong, and Hebrew from Qwen is modest; the "quick explanation" above the AI text is
  hand-written and reliable. Better Hebrew models (Gemma 4, Qwen3) exist on Hugging Face only in the
  LiteRT-LM format, which this version does not use yet.
* Only arm64 phones can run local models (MediaPipe).

* The 27 MB test fixture `testing/resources/test-project/app/src/main/resources/android.jar` was removed from this
  archive to keep it small. It is only used by the LSP tests; restore it from upstream AndroidIDE if you run them.
* **Build fix (Oct 2026):** upstream AndroidIDE depended on the sora-editor snapshot
  `0.23.4-ce8de8e-SNAPSHOT`, published to Sonatype's OSSRH (`s01.oss.sonatype.org`), which was shut down in
  2025, so Gradle failed while resolving `:editor:treesitter` (and the other modules that use the editor).
  This fork uses the release `io.github.Rosemoe.sora-editor:editor:0.23.5` instead (it contains commit
  `ce8de8e`) and no longer lists the dead repositories.
* When built from a source archive (no git history) the app version falls back to `2.7.1-beta`, a version
  whose Gradle plugin and log-sender artifacts exist on Maven Central (the app downloads them to build projects).
