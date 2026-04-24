# Strip: Photo Metadata Removal Engine

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Android](https://img.shields.io/badge/Android-API_26+-green.svg)](https://developer.android.com)

The engine that powers **[Strip: Photo Metadata Remover](https://play.google.com/store/apps/details?id=com.stripdev.strip)**.  
100 % offline · zero network calls · fully auditable.

---

## 🔍 What It Does

- **Reads** EXIF, XMP, and IPTC metadata from images on Android
- **Strips** GPS coordinates, device details, timestamps, camera/lens data, software info, authorship notes, and embedded XMP/IPTC fields
- **Supports selective preservation** – keep what you need, remove the rest
- **Verifies** scrubbed output to guarantee clean files
- **Audits** metadata and generates a risk score before and after scrubbing
- **Saves** cleaned images to the gallery or a custom file URI
- Runs entirely on‑device – **no network permission, no analytics, no data collection**

---
## 📚 Documentation

- [Engine Architecture](docs/ARCHITECTURE.md)
- [How to Verify](docs/VERIFICATION.md)

## 📦 Usage

The main entry point is `PhotoCleaner.scrubImage`. You give it a photo URI, tell it what to keep or remove, choose a quality and output format, and it returns a clean file.  
A companion method `PhotoCleaner.getMetadataDetails` can first inspect the photo and report every exposed tag and a privacy risk score.

All public methods are documented directly in the source file.

```kotlin
val result = PhotoCleaner.scrubImage(
    context = context,
    uri = photoUri,
    options = ScrubbingOptions(keepGps = false),
    qualityPreset = OutputQualityPreset.STANDARD,
    outputFormatPreset = OutputFormatPreset.ORIGINAL
)
if (result.file != null) {
    // scrubbed file ready for saving or sharing
}
```

Run a privacy audit before scrubbing:
```
val metadata = PhotoCleaner.getMetadataDetails(context, uri)
println("Risk score: ${metadata.riskScore}")
```

## 🏗️ Building & Reproducibility

The engine is a standard Android library module. You can build it with Android Studio or any Gradle‑compatible environment.  
The output is an AAR (Android Archive) ready to be included in any app.

To verify that the Play Store version of Strip uses this exact code, you can:

1. Build the library from source.
2. Extract the compiled Java/Kotlin classes from the resulting AAR.
3. Compare those classes against the engine classes found inside the Strip APK.
4. For fully reproducible builds, use Gradle 8.x, AGP 8.x, Android API 36, and JDK 17.

## 📂 Repository Structure
```text engine/
engine/
├── src/main/java/com/stripdev/strip/
│   └── PhotoCleaner.kt          # Full metadata removal engine
├── build.gradle.kts             # Engine module dependencies
└── README.md                    # You are here
``` 
No UI · no billing · no network · just the privacy‑sensitive core.

---

## 🔒 License

Copyright (C) 2026 **Amandeep Singh**

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU Affero General Public License** as published by the Free Software Foundation, either version 3 of the License, or any later version.

This program is distributed in the hope that it will be useful, but **without any warranty**; without even the implied warranty of merchantability or fitness for a particular purpose. See the [GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html) for full details.

---

## 📬 Contact

**Developer:** Amandeep Singh  
**Email:** [support@striptools.dev](mailto:support@striptools.dev)  
**Website:** [striptools.dev](https://striptools.dev)  
**Full app:** [Strip on Google Play](https://play.google.com/store/apps/details?id=com.stripdev.strip)

---

*“Privacy at the fullest” isn’t a slogan – it’s proven here.*

