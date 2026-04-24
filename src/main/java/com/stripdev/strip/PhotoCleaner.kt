package com.stripdev.strip

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.iptc.IptcDirectory
import com.drew.metadata.xmp.XmpDirectory
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

object PhotoCleaner {

    private val metadataInfoCache = ConcurrentHashMap<String, MetadataInfo>()
    private val detailedMetadataCache = ConcurrentHashMap<String, List<MetadataSection>>()

    private data class EmbeddedMetadataPresence(
        val hasXmp: Boolean,
        val hasIptc: Boolean,
        val detectionSucceeded: Boolean
    ) {
        val hasNonExifMetadata: Boolean
            get() = hasXmp || hasIptc
    }

    private data class EmbeddedMetadataDetails(
        val presence: EmbeddedMetadataPresence,
        val xmpCreator: String? = null,
        val xmpTitle: String? = null,
        val xmpRights: String? = null,
        val iptcAuthor: String? = null,
        val iptcCaption: String? = null,
        val iptcKeywords: String? = null,
        val iptcCopyright: String? = null
    )

    private data class EmbeddedMetadataPreservation(
        val xmpCreator: String? = null,
        val xmpTitle: String? = null,
        val xmpRights: String? = null,
        val iptcAuthor: String? = null,
        val iptcCaption: String? = null,
        val iptcKeywords: List<String> = emptyList(),
        val iptcCopyright: String? = null
    ) {
        val hasXmpContent: Boolean
            get() = !xmpCreator.isNullOrBlank() || !xmpTitle.isNullOrBlank() || !xmpRights.isNullOrBlank()

        val hasIptcContent: Boolean
            get() = !iptcAuthor.isNullOrBlank() ||
                !iptcCaption.isNullOrBlank() ||
                iptcKeywords.isNotEmpty() ||
                !iptcCopyright.isNullOrBlank()

        val hasAnyContent: Boolean
            get() = hasXmpContent || hasIptcContent
    }

    enum class ScrubFailureReason {
        UNSUPPORTED_FORMAT,
        FILE_TOO_LARGE,
        ACCESS_DENIED,
        LOW_STORAGE,
        VERIFICATION_FAILED,
        PROCESSING_ERROR
    }

    data class ScrubResult(
        val file: File? = null,
        val failureReason: ScrubFailureReason? = null
    )

    enum class OutputQualityPreset(
        val compressionQuality: Int
    ) {
        FAST(90),
        STANDARD(95),
        LOSSLESS(100);

        companion object {
            val default = STANDARD

            fun fromName(name: String?): OutputQualityPreset =
                entries.firstOrNull { it.name == name } ?: default
        }
    }

    enum class OutputFormatPreset {
        ORIGINAL,
        JPG,
        WEBP,
        HEIC;

        companion object {
            val default = ORIGINAL

            fun fromName(name: String?): OutputFormatPreset =
                entries.firstOrNull { it.name == name } ?: default
        }
    }

    data class MetadataInfo(
        val device: String = "Unknown",
        val dateTime: String = "Unknown",
        val gps: String = "None",
        val software: String = "Unknown",
        val exposure: String = "Unknown",
        val lens: String = "Unknown",
        val dimensions: String = "Unknown",
        val fileSize: String = "Unknown",
        val hasXmp: Boolean = false,
        val hasIptc: Boolean = false,
        val xmpCreator: String = "None Detected",
        val xmpTitle: String = "None Detected",
        val xmpRights: String = "None Detected",
        val iptcAuthor: String = "None Detected",
        val iptcCaption: String = "None Detected",
        val iptcKeywords: String = "None Detected",
        val iptcCopyright: String = "None Detected",
        val hasSensitiveData: Boolean = false,
        val riskScore: Int = 0 // 0 to 100
    )

    data class MetadataField(
        val label: String,
        val value: String
    )

    data class MetadataSection(
        val title: String,
        val fields: List<MetadataField>
    )

    data class ScrubbingOptions(
        val keepGps: Boolean = false,
        val keepDeviceDetails: Boolean = false,
        val keepDateTime: Boolean = false,
        val keepCameraLens: Boolean = false,
        val keepExposureSettings: Boolean = false,
        val keepSoftwareInfo: Boolean = false,
        val keepAuthorshipNotes: Boolean = false,
        val keepXmpCreator: Boolean = false,
        val keepXmpTitle: Boolean = false,
        val keepXmpRights: Boolean = false,
        val keepIptcAuthor: Boolean = false,
        val keepIptcCaption: Boolean = false,
        val keepIptcKeywords: Boolean = false,
        val keepIptcCopyright: Boolean = false
    ) {
        val keepCameraSettings: Boolean
            get() = keepCameraLens || keepExposureSettings

        val keepEmbeddedMetadata: Boolean
            get() = keepXmpCreator ||
                keepXmpTitle ||
                keepXmpRights ||
                keepIptcAuthor ||
                keepIptcCaption ||
                keepIptcKeywords ||
                keepIptcCopyright
    }

    private val SENSITIVE_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION
    )

    private val DEEP_METADATA_TAGS = listOf(
        "Location" to listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD
        ),
        "Device" to listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_SOFTWARE
        ),
        "Date & Time" to listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED
        ),
        "Camera & Lens" to listOf(
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL
        ),
        "Image Properties" to listOf(
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_COMPRESSION
        ),
        "Authorship & Notes" to listOf(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_COPYRIGHT
        )
    )

    fun exportFileName(
        context: Context,
        sourceUri: Uri?,
        extension: String,
        index: Int? = null,
        filenameTemplate: String? = null
    ): String {
        val originalName = sourceUri?.let { uri ->
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                    }
            }.getOrNull()
        }

        val baseName = originalName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }

        val now = Date()
        val safeOriginal = baseName ?: "photo"
        val safeExtension = extension.lowercase(Locale.US)
        val indexValue = index?.plus(1)?.toString() ?: "1"
        val template = filenameTemplate?.trim().orEmpty()

        if (template.isNotEmpty()) {
            val dateValue = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
            val timeValue = SimpleDateFormat("HHmmss", Locale.US).format(now)
            val dateTimeValue = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

            val rendered = template
                .replace("{original}", safeOriginal)
                .replace("{date}", dateValue)
                .replace("{time}", timeValue)
                .replace("{datetime}", dateTimeValue)
                .replace("{index}", indexValue)
                .replace("{ext}", safeExtension)

            val sanitized = rendered
                .substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_', '.', ' ')
                .takeIf { it.isNotBlank() }

            if (sanitized != null) {
                return "$sanitized.$safeExtension"
            }
        }

        return if (baseName != null) {
            val indexSuffix = index?.let { "_${it + 1}" } ?: ""
            "Strip_${baseName}${indexSuffix}.$extension"
        } else {
            val indexSuffix = index?.let { "_${it + 1}" } ?: ""
            "Strip_${System.currentTimeMillis()}${indexSuffix}.$extension"
        }
    }

    fun scrubImage(
        context: Context,
        uri: Uri,
        options: ScrubbingOptions = ScrubbingOptions(),
        qualityPreset: OutputQualityPreset = OutputQualityPreset.default,
        outputFormatPreset: OutputFormatPreset = OutputFormatPreset.default
    ): ScrubResult {
        val mimeType = context.contentResolver.getType(uri)
        val isJpeg = mimeType == "image/jpeg" || mimeType == "image/jpg"
        val isSupported = mimeType?.startsWith("image/") == true

        if (!isSupported) {
            return ScrubResult(failureReason = ScrubFailureReason.UNSUPPORTED_FORMAT)
        }

        val fileSizeBytes = getFileSizeBytes(context, uri)
        if (fileSizeBytes != null && fileSizeBytes > 50L * 1024L * 1024L) {
            return ScrubResult(failureReason = ScrubFailureReason.FILE_TOO_LARGE)
        }

        // Try lossless first only when output format is not being forced.
        if (isJpeg && outputFormatPreset == OutputFormatPreset.ORIGINAL) {
            val embeddedMetadata = detectEmbeddedMetadata(context, uri)
            val canUseLosslessPath =
                embeddedMetadata.detectionSucceeded && !embeddedMetadata.hasNonExifMetadata

            if (canUseLosslessPath) {
                val losslessFile = stripMetadataLossless(context, uri, options)
                if (losslessFile != null) {
                    return if (verifyScrubbedFile(context, uri, losslessFile, options)) {
                        ScrubResult(file = losslessFile)
                    } else {
                        losslessFile.delete()
                        ScrubResult(failureReason = ScrubFailureReason.VERIFICATION_FAILED)
                    }
                }
            }
        }

        val fallbackOutputFormat = resolveBitmapOutputFormat(mimeType, outputFormatPreset)

        // Fallback to Bitmap processing for other formats or if lossless fails
        val cleanFile = stripMetadataWithBitmap(
            context = context,
            uri = uri,
            outputFormat = fallbackOutputFormat,
            quality = qualityPreset.compressionQuality,
            options = options
        )
        if (cleanFile != null) {
            return try {
                if (verifyScrubbedFile(context, uri, cleanFile, options)) {
                    ScrubResult(file = cleanFile)
                } else {
                    cleanFile.delete()
                    ScrubResult(failureReason = ScrubFailureReason.VERIFICATION_FAILED)
                }
            } catch (_: SecurityException) {
                cleanFile.delete()
                ScrubResult(failureReason = ScrubFailureReason.ACCESS_DENIED)
            } catch (_: IOException) {
                cleanFile.delete()
                ScrubResult(failureReason = ScrubFailureReason.LOW_STORAGE)
            } catch (_: Exception) {
                cleanFile.delete()
                ScrubResult(failureReason = ScrubFailureReason.PROCESSING_ERROR)
            }
        }

        return ScrubResult(failureReason = ScrubFailureReason.PROCESSING_ERROR)
    }

    private fun verifyScrubbedFile(
        context: Context,
        sourceUri: Uri,
        file: File,
        options: ScrubbingOptions
    ): Boolean {
        return runCatching {
            val exif = ExifInterface(file.absolutePath)

            if (!options.keepGps && extractLatLong(exif) != null) return false
            if (!options.keepDeviceDetails && hasAnyTag(exif, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) return false
            if (!options.keepDateTime && hasAnyTag(
                    exif,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME,
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_OFFSET_TIME_DIGITIZED
                )
            ) return false
            if (!options.keepCameraLens && hasAnyTag(exif, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL)) return false
            if (!options.keepExposureSettings && hasAnyTag(
                    exif,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_WHITE_BALANCE
                )
            ) return false
            if (!options.keepSoftwareInfo && hasAnyTag(exif, ExifInterface.TAG_SOFTWARE)) return false
            if (!options.keepAuthorshipNotes && hasAnyTag(
                    exif,
                    ExifInterface.TAG_USER_COMMENT,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION
                )
            ) return false
            val sourceEmbedded = readEmbeddedMetadataDetails(context, sourceUri)
            val cleanedEmbedded = readEmbeddedMetadataDetails(file)

            if (!options.keepEmbeddedMetadata && cleanedEmbedded.presence.hasNonExifMetadata) return false
            if (!verifyEmbeddedMetadata(sourceEmbedded, cleanedEmbedded, options)) return false

            true
        }.getOrDefault(false)
    }

    private fun verifyEmbeddedMetadata(
        source: EmbeddedMetadataDetails,
        cleaned: EmbeddedMetadataDetails,
        options: ScrubbingOptions
    ): Boolean {
        if (options.keepXmpCreator) {
            if (!source.xmpCreator.isNullOrBlank() && source.xmpCreator != cleaned.xmpCreator) return false
        } else if (!cleaned.xmpCreator.isNullOrBlank()) {
            return false
        }

        if (options.keepXmpTitle) {
            if (!source.xmpTitle.isNullOrBlank() && source.xmpTitle != cleaned.xmpTitle) return false
        } else if (!cleaned.xmpTitle.isNullOrBlank()) {
            return false
        }

        if (options.keepXmpRights) {
            if (!source.xmpRights.isNullOrBlank() && source.xmpRights != cleaned.xmpRights) return false
        } else if (!cleaned.xmpRights.isNullOrBlank()) {
            return false
        }

        if (options.keepIptcAuthor) {
            if (!source.iptcAuthor.isNullOrBlank() && source.iptcAuthor != cleaned.iptcAuthor) return false
        } else if (!cleaned.iptcAuthor.isNullOrBlank()) {
            return false
        }

        if (options.keepIptcCaption) {
            if (!source.iptcCaption.isNullOrBlank() && source.iptcCaption != cleaned.iptcCaption) return false
        } else if (!cleaned.iptcCaption.isNullOrBlank()) {
            return false
        }

        if (options.keepIptcKeywords) {
            if (!source.iptcKeywords.isNullOrBlank() && source.iptcKeywords != cleaned.iptcKeywords) return false
        } else if (!cleaned.iptcKeywords.isNullOrBlank()) {
            return false
        }

        if (options.keepIptcCopyright) {
            if (!source.iptcCopyright.isNullOrBlank() && source.iptcCopyright != cleaned.iptcCopyright) return false
        } else if (!cleaned.iptcCopyright.isNullOrBlank()) {
            return false
        }

        return true
    }

    private fun hasAnyTag(exif: ExifInterface, vararg tags: String): Boolean {
        return tags.any { tag ->
            exif.getAttribute(tag)?.isNotBlank() == true
        }
    }

    private fun stripMetadataLossless(
        context: Context,
        uri: Uri,
        options: ScrubbingOptions
    ): File? {
        return try {
            val tempFile = File(context.cacheDir, "temp_lossless_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val exif = ExifInterface(tempFile.absolutePath)

            // 1. Determine tags to KEEP based on options
            val tagsToKeep = mutableSetOf<String>()
            if (options.keepDeviceDetails) tagsToKeep.addAll(listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL))
            if (options.keepDateTime) tagsToKeep.addAll(listOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED
            ))
            if (options.keepGps) tagsToKeep.addAll(listOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
            ))
            if (options.keepSoftwareInfo) tagsToKeep.add(ExifInterface.TAG_SOFTWARE)
            if (options.keepCameraLens) tagsToKeep.addAll(listOf(
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL
            ))
            if (options.keepExposureSettings) tagsToKeep.addAll(listOf(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE
            ))
            if (options.keepAuthorshipNotes) tagsToKeep.addAll(listOf(
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_IMAGE_DESCRIPTION
            ))

            // 2. Wipe everything in SENSITIVE_TAGS that isn't in tagsToKeep
            for (tag in SENSITIVE_TAGS) {
                if (!tagsToKeep.contains(tag)) {
                    exif.setAttribute(tag, null)
                }
            }

            // 3. Special handling for GPS: clear GPS tags without writing placeholder coordinates.
            if (!options.keepGps) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
            }

            exif.saveAttributes()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun stripMetadataWithBitmap(
        context: Context,
        uri: Uri,
        outputFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 95,
        options: ScrubbingOptions = ScrubbingOptions()
    ): File? {
        return try {
            val contentResolver = context.contentResolver
            val decodeOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }

            // Adapt decode size to device memory class to avoid OOM while keeping as much resolution as possible.
            val maxDimension = calculateAdaptiveMaxDimension(context)
            decodeOptions.inSampleSize = calculateInSampleSize(
                decodeOptions.outWidth,
                decodeOptions.outHeight,
                maxDimension,
                maxDimension
            )
            decodeOptions.inJustDecodeBounds = false

            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream?.close()

            if (bitmap == null) return null

            val tempExtension = extensionForBitmapFormat(outputFormat)
            val cleanFile = File(context.cacheDir, "temp_strip_${System.currentTimeMillis()}.$tempExtension")
            cleanFile.outputStream().use { outputStream ->
                bitmap.compress(outputFormat, quality, outputStream)
            }
            bitmap.recycle()

            // If selective scrubbing is on, re-apply selected metadata directly on the temp file.
            if (
                options.keepGps ||
                options.keepDeviceDetails ||
                options.keepDateTime ||
                options.keepCameraSettings ||
                options.keepSoftwareInfo ||
                options.keepAuthorshipNotes ||
                options.keepEmbeddedMetadata
            ) {
                val exifReapplied = reApplyExifTags(context, uri, cleanFile, options) ?: cleanFile
                return reApplyEmbeddedMetadata(context, uri, exifReapplied, options) ?: exifReapplied
            }

            cleanFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun reApplyExifTags(
        context: Context,
        sourceUri: Uri,
        cleanFile: File,
        options: ScrubbingOptions
    ): File? {
        return try {
            val sourceExif = context.contentResolver.openInputStream(sourceUri)?.use { ExifInterface(it) } ?: return cleanFile
            val destExif = ExifInterface(cleanFile.absolutePath)

            if (options.keepDeviceDetails) copyTags(sourceExif, destExif, listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL))
            if (options.keepDateTime) copyTags(sourceExif, destExif, listOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED
            ))
            if (options.keepSoftwareInfo) copyTags(sourceExif, destExif, listOf(
                ExifInterface.TAG_SOFTWARE
            ))
            if (options.keepCameraLens) copyTags(sourceExif, destExif, listOf(
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL
            ))
            if (options.keepExposureSettings) copyTags(sourceExif, destExif, listOf(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE
            ))
            if (options.keepGps) copyTags(sourceExif, destExif, listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP))
            if (options.keepAuthorshipNotes) copyTags(sourceExif, destExif, listOf(
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_IMAGE_DESCRIPTION
            ))

            destExif.saveAttributes()
            cleanFile
        } catch (e: Exception) {
            e.printStackTrace()
            cleanFile
        }
    }

    private fun copyTags(source: ExifInterface, dest: ExifInterface, tags: List<String>) {
        for (tag in tags) {
            source.getAttribute(tag)?.let { value ->
                dest.setAttribute(tag, value)
            }
        }
    }

    private fun reApplyEmbeddedMetadata(
        context: Context,
        sourceUri: Uri,
        cleanFile: File,
        options: ScrubbingOptions
    ): File? {
        if (!options.keepEmbeddedMetadata) return cleanFile

        return runCatching {
            val preservation = readEmbeddedMetadataPreservation(context, sourceUri, options)
            if (!preservation.hasAnyContent) return@runCatching cleanFile

            val inputFile = cleanFile
            var currentFile = inputFile

            if (preservation.hasXmpContent) {
                val outputFile = File(context.cacheDir, "embedded_xmp_${System.currentTimeMillis()}.jpg")
                outputFile.outputStream().use { output ->
                    JpegXmpRewriter().updateXmpXml(currentFile, output, buildXmpPacket(preservation))
                }
                if (currentFile != inputFile) currentFile.delete()
                currentFile = outputFile
            }

            if (preservation.hasIptcContent) {
                val outputFile = File(context.cacheDir, "embedded_iptc_${System.currentTimeMillis()}.jpg")
                outputFile.outputStream().use { output ->
                    JpegIptcRewriter().writeIptc(
                        currentFile,
                        output,
                        PhotoshopApp13Data(buildIptcRecords(preservation), emptyList())
                    )
                }
                if (currentFile != inputFile) currentFile.delete()
                currentFile = outputFile
            }

            if (currentFile != inputFile && inputFile.exists()) inputFile.delete()
            currentFile
        }.getOrElse { cleanFile }
    }

    private fun readEmbeddedMetadataPreservation(
        context: Context,
        sourceUri: Uri,
        options: ScrubbingOptions
    ): EmbeddedMetadataPreservation {
        val details = readEmbeddedMetadataDetails(context, sourceUri)
        return EmbeddedMetadataPreservation(
            xmpCreator = details.xmpCreator?.takeIf { options.keepXmpCreator && it.isNotBlank() },
            xmpTitle = details.xmpTitle?.takeIf { options.keepXmpTitle && it.isNotBlank() },
            xmpRights = details.xmpRights?.takeIf { options.keepXmpRights && it.isNotBlank() },
            iptcAuthor = details.iptcAuthor?.takeIf { options.keepIptcAuthor && it.isNotBlank() },
            iptcCaption = details.iptcCaption?.takeIf { options.keepIptcCaption && it.isNotBlank() },
            iptcKeywords = details.iptcKeywords
                ?.takeIf { options.keepIptcKeywords && it.isNotBlank() }
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            iptcCopyright = details.iptcCopyright?.takeIf { options.keepIptcCopyright && it.isNotBlank() }
        )
    }

    private fun buildXmpPacket(preservation: EmbeddedMetadataPreservation): String {
        val body = buildString {
            if (!preservation.xmpCreator.isNullOrBlank()) {
                append("<dc:creator><rdf:Seq><rdf:li>")
                append(escapeXml(preservation.xmpCreator))
                append("</rdf:li></rdf:Seq></dc:creator>")
            }
            if (!preservation.xmpTitle.isNullOrBlank()) {
                append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(escapeXml(preservation.xmpTitle))
                append("</rdf:li></rdf:Alt></dc:title>")
            }
            if (!preservation.xmpRights.isNullOrBlank()) {
                append("<dc:rights><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(escapeXml(preservation.xmpRights))
                append("</rdf:li></rdf:Alt></dc:rights>")
            }
        }

        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  $body
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    private fun buildIptcRecords(preservation: EmbeddedMetadataPreservation): List<IptcRecord> {
        val records = mutableListOf<IptcRecord>()
        preservation.iptcAuthor?.takeIf { it.isNotBlank() }?.let {
            records += IptcRecord(IptcTypes.BYLINE, it)
        }
        preservation.iptcCaption?.takeIf { it.isNotBlank() }?.let {
            records += IptcRecord(IptcTypes.CAPTION_ABSTRACT, it)
        }
        preservation.iptcKeywords.forEach { keyword ->
            records += IptcRecord(IptcTypes.KEYWORDS, keyword)
        }
        preservation.iptcCopyright?.takeIf { it.isNotBlank() }?.let {
            records += IptcRecord(IptcTypes.COPYRIGHT_NOTICE, it)
        }
        return records
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun calculateAdaptiveMaxDimension(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = activityManager?.memoryClass ?: 128
        val bytesBudget = (memoryClassMb * 1024 * 1024L) / 8L
        val pixelsBudget = bytesBudget / 4L
        val dimension = sqrt(pixelsBudget.toDouble()).toInt()
        return dimension.coerceIn(3000, 6000)
    }

    private fun resolveBitmapOutputFormat(
        mimeType: String?,
        outputFormatPreset: OutputFormatPreset
    ): Bitmap.CompressFormat {
        if (outputFormatPreset != OutputFormatPreset.ORIGINAL) {
            return when (outputFormatPreset) {
                OutputFormatPreset.JPG -> Bitmap.CompressFormat.JPEG
                OutputFormatPreset.WEBP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmapCompressFormatOrNull("WEBP_LOSSLESS")
                            ?: bitmapCompressFormatOrNull("WEBP_LOSSY")
                            ?: Bitmap.CompressFormat.WEBP
                    } else {
                        Bitmap.CompressFormat.JPEG
                    }
                }
                OutputFormatPreset.HEIC -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmapCompressFormatOrNull("HEIC")
                            ?: bitmapCompressFormatOrNull("HEIF")
                            ?: Bitmap.CompressFormat.JPEG
                    } else {
                        Bitmap.CompressFormat.JPEG
                    }
                }
                OutputFormatPreset.ORIGINAL -> Bitmap.CompressFormat.JPEG
            }
        }

        val normalized = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            normalized == "image/heic" || normalized == "image/heif" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmapCompressFormatOrNull("HEIC")
                        ?: bitmapCompressFormatOrNull("HEIF")
                        ?: Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
            }
            normalized == "image/webp" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmapCompressFormatOrNull("WEBP_LOSSLESS")
                        ?: bitmapCompressFormatOrNull("WEBP_LOSSY")
                        ?: Bitmap.CompressFormat.WEBP
                } else {
                    Bitmap.CompressFormat.JPEG
                }
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private fun extensionForBitmapFormat(format: Bitmap.CompressFormat): String {
        return when (format.name) {
            "HEIC", "HEIF" -> "heic"
            "WEBP", "WEBP_LOSSLESS", "WEBP_LOSSY" -> "webp"
            "PNG" -> "png"
            else -> "jpg"
        }
    }

    private fun bitmapCompressFormatOrNull(name: String): Bitmap.CompressFormat? {
        return runCatching { Bitmap.CompressFormat.valueOf(name) }.getOrNull()
    }

    fun saveToGallery(
        context: Context,
        bytes: ByteArray,
        format: String = "jpg",
        sourceUri: Uri? = null,
        filenameTemplate: String? = null,
        index: Int? = null
    ): Uri? {
        return try {
            val normalizedFormat = format.lowercase(Locale.US)
            val extension = when {
                "heif" in normalizedFormat || "heic" in normalizedFormat -> "heic"
                "webp" in normalizedFormat -> "webp"
                "png" in normalizedFormat -> "png"
                else -> "jpg"
            }
            val mimeType = when (extension) {
                "heic" -> "image/heif"
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            val fileName = exportFileName(
                context = context,
                sourceUri = sourceUri,
                extension = extension,
                index = index,
                filenameTemplate = filenameTemplate
            )

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Strip")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri)?.use { it.write(bytes) }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(targetUri, contentValues, null, null)
                targetUri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveToUri(context: Context, bytes: ByteArray, targetUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(targetUri)?.use {
                it.write(bytes)
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getMetadataDetails(context: Context, uri: Uri): MetadataInfo {
        val cacheKey = uri.toString()
        metadataInfoCache[cacheKey]?.let { return it }

        val metadata = try {
            val contentResolver = context.contentResolver
            var sizeStr = "Unknown"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) {
                    val size = cursor.getLong(sizeIndex)
                    sizeStr = formatFileSize(size)
                }
            }

            val exifSources = loadExifInterfaces(contentResolver, uri)
            val embeddedMetadata = readEmbeddedMetadataDetails(context, uri)
            exifSources.firstOrNull()?.let { primaryExif ->
                val model = firstExifAttribute(exifSources, ExifInterface.TAG_MODEL)
                val make = firstExifAttribute(exifSources, ExifInterface.TAG_MAKE)
                val dateTime = firstExifAttribute(
                    exifSources,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED
                )
                val latLong = exifSources.firstNotNullOfOrNull { extractLatLong(it) }
                val software = firstExifAttribute(exifSources, ExifInterface.TAG_SOFTWARE)
                val fNumber = firstExifAttribute(exifSources, ExifInterface.TAG_F_NUMBER)
                val iso = firstExifAttribute(exifSources, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                val lensModel = firstExifAttribute(exifSources, ExifInterface.TAG_LENS_MODEL)
                val width = firstExifAttribute(exifSources, ExifInterface.TAG_IMAGE_WIDTH)
                val height = firstExifAttribute(exifSources, ExifInterface.TAG_IMAGE_LENGTH)

                var score = 0
                if (latLong != null) score += 60
                if (model != null || make != null) score += 20
                if (dateTime != null) score += 10
                if (software != null) score += 10
                if (embeddedMetadata.presence.hasXmp) score += 10
                if (embeddedMetadata.presence.hasIptc) score += 10
                score = score.coerceAtMost(100)

                MetadataInfo(
                    device = if (make != null || model != null) "${make ?: ""} ${model ?: ""}".trim() else "None Detected",
                    dateTime = dateTime ?: "None Detected",
                    gps = latLong?.let { formatLatLong(it[0], it[1]) } ?: "None Detected",
                    software = software ?: "None Detected",
                    exposure = if (fNumber != null || iso != null) "f/$fNumber ISO $iso" else "None Detected",
                    lens = lensModel ?: "None Detected",
                    dimensions = if (width != null && height != null) "${width}x$height" else "None Detected",
                    fileSize = sizeStr,
                    hasXmp = embeddedMetadata.presence.hasXmp,
                    hasIptc = embeddedMetadata.presence.hasIptc,
                    xmpCreator = embeddedMetadata.xmpCreator ?: "None Detected",
                    xmpTitle = embeddedMetadata.xmpTitle ?: "None Detected",
                    xmpRights = embeddedMetadata.xmpRights ?: "None Detected",
                    iptcAuthor = embeddedMetadata.iptcAuthor ?: "None Detected",
                    iptcCaption = embeddedMetadata.iptcCaption ?: "None Detected",
                    iptcKeywords = embeddedMetadata.iptcKeywords ?: "None Detected",
                    iptcCopyright = embeddedMetadata.iptcCopyright ?: "None Detected",
                    hasSensitiveData = latLong != null || model != null || dateTime != null || embeddedMetadata.presence.hasNonExifMetadata,
                    riskScore = score
                )
            } ?: MetadataInfo(
                fileSize = sizeStr,
                hasXmp = embeddedMetadata.presence.hasXmp,
                hasIptc = embeddedMetadata.presence.hasIptc,
                xmpCreator = embeddedMetadata.xmpCreator ?: "None Detected",
                xmpTitle = embeddedMetadata.xmpTitle ?: "None Detected",
                xmpRights = embeddedMetadata.xmpRights ?: "None Detected",
                iptcAuthor = embeddedMetadata.iptcAuthor ?: "None Detected",
                iptcCaption = embeddedMetadata.iptcCaption ?: "None Detected",
                iptcKeywords = embeddedMetadata.iptcKeywords ?: "None Detected",
                iptcCopyright = embeddedMetadata.iptcCopyright ?: "None Detected",
                hasSensitiveData = embeddedMetadata.presence.hasNonExifMetadata,
                riskScore = buildList {
                    if (embeddedMetadata.presence.hasXmp) add(10)
                    if (embeddedMetadata.presence.hasIptc) add(10)
                }.sum().coerceAtMost(100)
            )
        } catch (e: Exception) {
            MetadataInfo()
        }
        metadataInfoCache[cacheKey] = metadata
        return metadata
    }

    fun getDetailedMetadata(context: Context, uri: Uri): List<MetadataSection> {
        val cacheKey = uri.toString()
        detailedMetadataCache[cacheKey]?.let { return it }

        val sections = try {
            val exifSources = loadExifInterfaces(context.contentResolver, uri)
            val sections = DEEP_METADATA_TAGS.mapNotNull { (title, tags) ->
                val fields = tags.mapNotNull { tag ->
                    firstExifAttribute(exifSources, tag)?.let { value ->
                        MetadataField(label = displayLabelForTag(tag), value = value)
                    }
                }
                if (fields.isEmpty()) null else MetadataSection(title = title, fields = fields)
            }.toMutableList()

            val summary = getMetadataDetails(context, uri)
            val summaryFields = listOfNotNull(
                summary.gps.takeUnless { it == "None" || it == "None Detected" || it == "Unknown" }?.let {
                    MetadataField("GPS (formatted)", it)
                },
                summary.fileSize.takeUnless { it == "Unknown" }?.let {
                    MetadataField("File size", it)
                },
                summary.dimensions.takeUnless { it == "Unknown" || it == "None Detected" }?.let {
                    MetadataField("Dimensions", it)
                },
                summary.hasXmp.takeIf { it }?.let {
                    MetadataField("XMP", "Detected")
                },
                summary.hasIptc.takeIf { it }?.let {
                    MetadataField("IPTC", "Detected")
                }
            )
            if (summaryFields.isNotEmpty()) {
                sections.add(0, MetadataSection(title = "Readable Summary", fields = summaryFields))
            }
            sections.addAll(loadEmbeddedMetadataSections(context, uri))
            sections
        } catch (_: Exception) {
            emptyList()
        }
        detailedMetadataCache[cacheKey] = sections
        return sections
    }

    fun preloadMetadataDetails(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            getMetadataDetails(context, uri)
        }
    }

    fun invalidateMetadata(uri: Uri?) {
        if (uri == null) return
        val cacheKey = uri.toString()
        metadataInfoCache.remove(cacheKey)
        detailedMetadataCache.remove(cacheKey)
    }

    private fun loadExifInterfaces(
        contentResolver: ContentResolver,
        uri: Uri
    ): List<ExifInterface> {
        val exifSources = mutableListOf<ExifInterface>()

        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                exifSources += ExifInterface(descriptor.fileDescriptor)
            }
        } catch (_: Exception) {
        }

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                exifSources += ExifInterface(stream)
            }
        } catch (_: Exception) {
        }

        return exifSources
    }

    private fun firstExifAttribute(
        exifSources: List<ExifInterface>,
        vararg tags: String
    ): String? {
        for (exif in exifSources) {
            for (tag in tags) {
                exif.getAttribute(tag)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun extractLatLong(exif: ExifInterface): DoubleArray? {
        exif.latLong?.takeIf { isMeaningfulLatLong(it[0], it[1]) }?.let { return it }

        val latitude = parseExifCoordinate(
            coordinate = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
            reference = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        )
        val longitude = parseExifCoordinate(
            coordinate = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE),
            reference = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
        )

        return if (latitude != null && longitude != null && isMeaningfulLatLong(latitude, longitude)) {
            doubleArrayOf(latitude, longitude)
        } else {
            null
        }
    }

    private fun isMeaningfulLatLong(latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false

        // Many broken EXIF paths report cleared/missing GPS as 0,0. Treat that as absent.
        val epsilon = 0.000001
        return !(abs(latitude) < epsilon && abs(longitude) < epsilon)
    }

    private fun parseExifCoordinate(
        coordinate: String?,
        reference: String?
    ): Double? {
        if (coordinate.isNullOrBlank()) return null

        val parts = coordinate.split(",")
        if (parts.size < 3) return null

        val degrees = parseRational(parts[0].trim()) ?: return null
        val minutes = parseRational(parts[1].trim()) ?: return null
        val seconds = parseRational(parts[2].trim()) ?: return null

        var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
        if (reference.equals("S", ignoreCase = true) || reference.equals("W", ignoreCase = true)) {
            decimal *= -1
        }
        return decimal
    }

    private fun parseRational(value: String): Double? {
        val parts = value.split("/")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val numerator = parts[0].toDoubleOrNull() ?: return null
                val denominator = parts[1].toDoubleOrNull() ?: return null
                if (denominator == 0.0) null else numerator / denominator
            }
            else -> null
        }
    }

    private fun formatLatLong(latitude: Double, longitude: Double): String {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    private fun displayLabelForTag(tag: String): String {
        return tag
            .removePrefix("TAG_")
            .lowercase(Locale.US)
            .split("_")
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun getFileSizeBytes(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) cursor.getLong(sizeIndex) else null
            }
        }.getOrNull()
    }

    private fun detectEmbeddedMetadata(context: Context, uri: Uri): EmbeddedMetadataPresence {
        return readEmbeddedMetadataDetails(context, uri).presence
    }

    private fun readEmbeddedMetadataDetails(context: Context, uri: Uri): EmbeddedMetadataDetails {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readEmbeddedMetadataDetails(stream.readBytes())
            } ?: EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(hasXmp = false, hasIptc = false, detectionSucceeded = false)
            )
        }.getOrDefault(
            EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(hasXmp = false, hasIptc = false, detectionSucceeded = false)
            )
        )
    }

    private fun readEmbeddedMetadataDetails(file: File): EmbeddedMetadataDetails {
        return runCatching {
            val metadata = ImageMetadataReader.readMetadata(file)
            val xmp = metadata.getFirstDirectoryOfType(XmpDirectory::class.java)
            val iptc = metadata.getFirstDirectoryOfType(IptcDirectory::class.java)
            EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(
                    hasXmp = xmp != null,
                    hasIptc = iptc != null,
                    detectionSucceeded = true
                ),
                xmpCreator = firstDirectoryDescription(xmp, "Creator", "Author"),
                xmpTitle = firstDirectoryDescription(xmp, "Title", "Object Name"),
                xmpRights = firstDirectoryDescription(xmp, "Rights", "Usage Terms", "Copyright"),
                iptcAuthor = firstDirectoryDescription(iptc, "By-line", "Writer/Editor", "Credit"),
                iptcCaption = firstDirectoryDescription(iptc, "Caption/Abstract", "Object Name"),
                iptcKeywords = firstDirectoryDescription(iptc, "Keywords"),
                iptcCopyright = firstDirectoryDescription(iptc, "Copyright Notice")
            )
        }.getOrDefault(
            EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(hasXmp = false, hasIptc = false, detectionSucceeded = false)
            )
        )
    }

    private fun detectEmbeddedMetadata(file: File): EmbeddedMetadataPresence {
        return runCatching {
            val metadata = ImageMetadataReader.readMetadata(file)
            EmbeddedMetadataPresence(
                hasXmp = metadata.getFirstDirectoryOfType(XmpDirectory::class.java) != null,
                hasIptc = metadata.getFirstDirectoryOfType(IptcDirectory::class.java) != null,
                detectionSucceeded = true
            )
        }.getOrDefault(EmbeddedMetadataPresence(hasXmp = false, hasIptc = false, detectionSucceeded = false))
    }

    private fun detectEmbeddedMetadata(bytes: ByteArray): EmbeddedMetadataPresence {
        return readEmbeddedMetadataDetails(bytes).presence
    }

    private fun readEmbeddedMetadataDetails(bytes: ByteArray): EmbeddedMetadataDetails {
        return runCatching {
            val metadata = ImageMetadataReader.readMetadata(bytes.inputStream())
            val xmp = metadata.getFirstDirectoryOfType(XmpDirectory::class.java)
            val iptc = metadata.getFirstDirectoryOfType(IptcDirectory::class.java)
            EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(
                    hasXmp = xmp != null,
                    hasIptc = iptc != null,
                    detectionSucceeded = true
                ),
                xmpCreator = firstDirectoryDescription(xmp, "Creator", "Author"),
                xmpTitle = firstDirectoryDescription(xmp, "Title", "Object Name"),
                xmpRights = firstDirectoryDescription(xmp, "Rights", "Usage Terms", "Copyright"),
                iptcAuthor = firstDirectoryDescription(iptc, "By-line", "Writer/Editor", "Credit"),
                iptcCaption = firstDirectoryDescription(iptc, "Caption/Abstract", "Object Name"),
                iptcKeywords = firstDirectoryDescription(iptc, "Keywords"),
                iptcCopyright = firstDirectoryDescription(iptc, "Copyright Notice")
            )
        }.getOrDefault(
            EmbeddedMetadataDetails(
                presence = EmbeddedMetadataPresence(hasXmp = false, hasIptc = false, detectionSucceeded = false)
            )
        )
    }

    private fun loadEmbeddedMetadataSections(context: Context, uri: Uri): List<MetadataSection> {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val metadata = ImageMetadataReader.readMetadata(stream)
                buildList {
                    directoryToSection(
                        directory = metadata.getFirstDirectoryOfType(XmpDirectory::class.java),
                        title = "Raw XMP"
                    )?.let(::add)
                    directoryToSection(
                        directory = metadata.getFirstDirectoryOfType(IptcDirectory::class.java),
                        title = "Raw IPTC"
                    )?.let(::add)
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun directoryToSection(directory: Directory?, title: String): MetadataSection? {
        if (directory == null) return null
        val fields = directory.tags.mapNotNull { tag ->
            tag.description
                ?.takeIf { it.isNotBlank() }
                ?.let { MetadataField(label = tag.tagName, value = it) }
        }
        return if (fields.isEmpty()) null else MetadataSection(title = title, fields = fields)
    }

    private fun firstDirectoryDescription(directory: Directory?, vararg tagNames: String): String? {
        if (directory == null) return null
        return tagNames.firstNotNullOfOrNull { name ->
            directory.tags.firstOrNull { it.tagName.equals(name, ignoreCase = true) }?.description
                ?.takeIf { it.isNotBlank() }
        }
    }
}
