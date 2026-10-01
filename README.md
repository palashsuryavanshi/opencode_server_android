<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/2bee671e-e2bd-4e21-ad4f-d8209f78e507

## Working on this backend

The managed runtime, OpenCode server and API client live under
`app/src/main/java/com/example/backend/`. Before picking up backend work, read
[RESUME.md](RESUME.md) — it records the toolchain requirements, the commands, the last verified
build state, and the specific gaps that are still open.

| Document | Covers |
| --- | --- |
| [RESUME.md](RESUME.md) | Toolchain, commands, verified state, outstanding work |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layering and the manager-per-concern structure |
| [RUNTIME.md](RUNTIME.md) | The Linux userland, terminal and PTY |
| [OPENCODE.md](OPENCODE.md) | Install, version detection and the HTTP/SSE client |
| [BACKEND_INTEGRATION.md](BACKEND_INTEGRATION.md) | Wiring the existing frontend to the backend |
| [SECURITY.md](SECURITY.md) | Bind address, CORS, credential storage |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Symptom-driven diagnosis |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Bundled-component licences |

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.
