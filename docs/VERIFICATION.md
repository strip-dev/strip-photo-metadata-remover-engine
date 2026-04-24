# How to verify the engine

1. Build the library from source: `./gradlew :engine:assembleRelease`.
2. Extract `classes.jar` from the AAR (`engine/build/outputs/aar/engine-release.aar`).
3. Unzip the Play Store APK, locate `classes.jar` or the relevant dex file, and convert to jar (using `d2j-dex2jar` or similar).
4. Compare the compiled classes – they should match precisely if reproducible build conditions hold.
5. Alternatively, audit the source code directly:
   - Check for any `OkHttp`, `HttpURLConnection`, `Socket`, or any network imports → none.
   - Check for any use of `android.os.Process`, file exfiltration → none.
   - Verify that `scrubImage` never uses the network.
   - Verify that all file writes target only the app’s cache directory and the content:// URIs you provide.

A successful audit means the library does exactly what the privacy policy claims.
