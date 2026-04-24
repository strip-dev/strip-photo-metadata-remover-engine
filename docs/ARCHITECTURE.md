# Engine Architecture

## Entry points
- `scrubImage()` – primary scrubbing method; first tries lossless stripping for JPEGs without XMP/IPTC, then falls back to Bitmap reencoding.
- `getMetadataDetails()` – lightweight metadata summary for UI audit.
- `getDetailedMetadata()` – full structured metadata for deep inspection.
- `saveToGallery()` / `saveToUri()` – output helpers.

## Processing pipeline
1. **MIME check** – reject non-image or oversized files.
2. **Embedded metadata detection** – uses `ImageMetadataReader` on a copy of the file to determine presence of XMP/IPTC.
3. **Lossless path** (JPEG only, no non-Exif embedded metadata):
   - Clone file via streams.
   - Use `ExifInterface` to wipe all tags except those explicitly preserved.
   - Verify with `verifyScrubbedFile`.
4. **Bitmap fallback** (other formats or when lossless not possible):
   - Decode bitmap with adaptive downsampling (memory‑aware `inSampleSize` via `ActivityManager`).
   - Compress to chosen format/quality.
   - Re‑apply selected EXIF tags and/or re‑embed XMP/IPTC via Apache Commons Imaging.
   - Verify.
5. **Verification** – reads back the scrubbed file and confirms banned tags are absent, preserved tags match.

## Metadata reading
- EXIF: `ExifInterface` on both FileDescriptor and InputStream (for robustness), plus fallback to manual GPS parsing.
- XMP / IPTC: `metadata-extractor` library (`com.drewnoakes`).
- For selective preservation, we manually build XMP packets and IPTC records using Commons Imaging.

## File handling
- Temporary files are written to the app’s cache directory with prefixes `temp_strip_`, `exif_temp_`, `temp_lossless_`.
- Caller (in `:app` via Workers) handles cleanup.

## Risk scoring
- +60 for GPS
- +20 for device make/model
- +10 each for timestamp, software, XMP presence, IPTC presence
- Capped at 100
