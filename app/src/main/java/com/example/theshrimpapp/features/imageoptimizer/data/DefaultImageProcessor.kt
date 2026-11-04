package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessor
import com.example.theshrimpapp.features.imageoptimizer.domain.LosslessImageOptimizer
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceStage
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceTracker
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import javax.inject.Inject

class DefaultImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    private val losslessImageOptimizer: LosslessImageOptimizer
) : ImageProcessor {

    data class ImageMetadataSnapshot(
        val width: Int,
        val height: Int,
        val orientation: Int,
        val attributes: Map<String, String>
    )

    companion object {
        private val EXIF_TAG_NAMES: List<String> by lazy {
            ExifInterface::class.java.fields
                .asSequence()
                .filter { field ->
                    field.name.startsWith("TAG_") &&
                            field.type == String::class.java &&
                            Modifier.isStatic(field.modifiers)
                }
                .mapNotNull { field -> cancellationAwareRunCatching { field.get(null) as? String }.getOrNull() }
                .distinct()
                .toList()
        }

        private val EXIF_SKIP_TAGS = setOf(
            ExifInterface.TAG_ORIENTATION,
            "JPEGInterchangeFormat",
            "JPEGInterchangeFormatLength",
            "StripOffsets",
            "StripByteCounts",
            "TileOffsets",
            "TileByteCounts",
            "ThumbnailImageWidth",
            "ThumbnailImageLength",
            "ThumbnailOrientation",
            "ThumbnailImage",
            "MakerNote",
            "Xmp"
        )
    }

    override suspend fun process(
        inputUri: Uri,
        outputUri: Uri,
        options: ImageProcessingOptions
    ): ImageProcessingResult = withContext(Dispatchers.IO) {
        if (inputUri == outputUri) {
            return@withContext ImageProcessingResult.Failed(
                "Input and output must be different URIs. The source was not modified."
            )
        }

        val originalSize = getFileSize(inputUri)

        // If processing mode is LOSSLESS_OPTIMIZE, delegate to LosslessImageOptimizer
        if (options.processingMode == ProcessingMode.LOSSLESS_OPTIMIZE) {
            return@withContext when (val result =
                losslessImageOptimizer.optimize(inputUri, outputUri, options)) {
                is ImageOptimizationResult.Optimized -> ImageProcessingResult.Success(
                    originalSizeBytes = result.originalSizeBytes,
                    outputSizeBytes = result.outputSizeBytes,
                    format = result.format,
                    width = result.width,
                    height = result.height,
                    bytesSaved = result.bytesSaved,
                    percentageReduction = result.percentageReduction,
                    mode = ProcessingMode.LOSSLESS_OPTIMIZE
                )

                is ImageOptimizationResult.Unchanged -> ImageProcessingResult.Unchanged(result.reason)
                is ImageOptimizationResult.Unsupported -> ImageProcessingResult.Unchanged(result.reason)
                is ImageOptimizationResult.Failed -> ImageProcessingResult.Failed(result.error)
            }
        }

        // Reduce Size Mode pipeline
        val tempCandidateFile = File.createTempFile("reduce-size-", ".tmp", context.cacheDir)
        val tempPostOptFile = File.createTempFile("post-opt-", ".tmp", context.cacheDir)
        val ownedBitmaps = mutableListOf<Bitmap>()

        try {
            // 1. Read bounds and EXIF orientation / attributes once
            val metadata = PerformanceTracker.trackStage(PerformanceStage.BOUNDS_EXIF) {
                readImageMetadata(inputUri, options.preserveMetadata)
            }
            val srcWidth = metadata.width
            val srcHeight = metadata.height
            val exifOrientation = metadata.orientation

            if (srcWidth <= 0 || srcHeight <= 0) {
                return@withContext ImageProcessingResult.Failed("Could not read valid image dimensions.")
            }

            // 2. Calculate target dimensions (longest side <= maxDimension, preserving aspect ratio, no upscaling)
            val swapped = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE

            val actualWidth = if (swapped) srcHeight else srcWidth
            val actualHeight = if (swapped) srcWidth else srcHeight

            val maxDim = options.maxDimension
            val targetWidth: Int
            val targetHeight: Int

            if (!options.keepOriginalSize && (actualWidth > maxDim || actualHeight > maxDim)) {
                if (actualWidth >= actualHeight) {
                    targetWidth = maxDim
                    targetHeight =
                        (maxDim.toFloat() * actualHeight / actualWidth).toInt().coerceAtLeast(1)
                } else {
                    targetHeight = maxDim
                    targetWidth =
                        (maxDim.toFloat() * actualWidth / actualHeight).toInt().coerceAtLeast(1)
                }
            } else {
                // No upscaling by default; keep original dimensions
                targetWidth = actualWidth
                targetHeight = actualHeight
            }

            // 3. Calculate sample size for efficient decoding
            val sampleTargetWidth = if (swapped) targetHeight else targetWidth
            val sampleTargetHeight = if (swapped) targetWidth else targetHeight
            val sampleSize = calculateSampleSize(
                srcWidth, srcHeight, sampleTargetWidth, sampleTargetHeight
            )

            // 4. Decode bitmap with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = PerformanceTracker.trackStage(PerformanceStage.DECODE) {
                openInputStream(inputUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
                ?: return@withContext ImageProcessingResult.Failed("Could not open input stream for decoding.")
            ownedBitmaps.add(decodedBitmap)

            // 5. Transform bitmap (orientation + resize)
            val transformedBitmap = PerformanceTracker.trackStage(PerformanceStage.TRANSFORM) {
                transformBitmap(decodedBitmap, exifOrientation, targetWidth, targetHeight)
            }
            ownedBitmaps.add(transformedBitmap)
            if (transformedBitmap !== decodedBitmap) {
                decodedBitmap.recycle()
            }

            // 6. Handle JPEG transparency background compositing if needed
            val finalBitmap = if (options.outputFormat == OutputFormat.JPEG) {
                PerformanceTracker.trackStage(PerformanceStage.TRANSFORM) {
                    compositeBackground(transformedBitmap, options.backgroundColor)
                }
            } else {
                transformedBitmap
            }
            ownedBitmaps.add(finalBitmap)
            if (finalBitmap !== transformedBitmap) {
                transformedBitmap.recycle()
            }

            // 7. Compress and write to temporary candidate file
            val compressFormat = mapCompressFormat(options.outputFormat)
            val quality =
                if (options.outputFormat.supportsQuality) options.quality.coerceIn(1, 100) else 100

            PerformanceTracker.trackStage(PerformanceStage.ENCODE) {
                FileOutputStream(tempCandidateFile).use { fos ->
                    if (!finalBitmap.compress(compressFormat, quality, fos)) {
                        throw IOException("Image encoding failed.")
                    }
                }
            }
            finalBitmap.recycle()

            // 8. Apply metadata once using the cached snapshot
            PerformanceTracker.trackStage(PerformanceStage.EXIF_SAVE) {
                applyExifMetadata(
                    metadata,
                    tempCandidateFile,
                    targetWidth,
                    targetHeight,
                    options.preserveMetadata
                )
            }

            val preOptSize = tempCandidateFile.length()
            if (preOptSize <= 0) {
                return@withContext ImageProcessingResult.Failed("Failed to encode image output.")
            }

            // 9. Lossless post-optimization stage on final encoded candidate (if TRY_LOSSLESS policy is selected)
            var winningFile = tempCandidateFile
            if (options.postOptimizationPolicy == com.example.theshrimpapp.features.imageoptimizer.domain.PostOptimizationPolicy.TRY_LOSSLESS) {
                val postOptResult = PerformanceTracker.trackStage(PerformanceStage.POST_OPTIMIZATION) {
                    try {
                        losslessImageOptimizer.optimize(
                            Uri.fromFile(tempCandidateFile),
                            Uri.fromFile(tempPostOptFile),
                            options
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // Keep the valid encoded candidate if optional post-optimization fails.
                    }
                }

                if (postOptResult is ImageOptimizationResult.Optimized) {
                    val postOptSize = tempPostOptFile.length()
                    if (postOptSize > 0 && postOptSize < preOptSize) {
                        winningFile = tempPostOptFile
                    }
                }

                // If winningFile is post-optimized file, ensure metadata is applied/retained
                if (winningFile !== tempCandidateFile) {
                    PerformanceTracker.trackStage(PerformanceStage.EXIF_SAVE) {
                        applyExifMetadata(
                            metadata,
                            winningFile,
                            targetWidth,
                            targetHeight,
                            options.preserveMetadata
                        )
                    }
                }
            }

            // 10. Write winning file to target outputUri
            val finalOutputBytes = winningFile.length()
            openOutputStream(outputUri)?.use { outputStream ->
                winningFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
                ?: return@withContext ImageProcessingResult.Failed("Could not write to output location.")

            val bytesSaved = originalSize - finalOutputBytes
            val percentageReduction = if (originalSize > 0) {
                (1.0f - finalOutputBytes.toFloat() / originalSize.toFloat()) * 100f
            } else {
                0f
            }

            ImageProcessingResult.Success(
                originalSizeBytes = originalSize,
                outputSizeBytes = finalOutputBytes,
                format = options.outputFormat,
                width = targetWidth,
                height = targetHeight,
                bytesSaved = bytesSaved,
                percentageReduction = percentageReduction,
                mode = ProcessingMode.REDUCE_SIZE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            ImageProcessingResult.Failed("Not enough memory to process this image. Try a smaller dimension.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            ImageProcessingResult.Failed(e.localizedMessage ?: "Unknown processing error occurred.")
        } finally {
            ownedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            cancellationAwareRunCatching { tempCandidateFile.delete() }
            cancellationAwareRunCatching { tempPostOptFile.delete() }
        }
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            File(path).inputStream()
        } else {
            contentResolver.openInputStream(uri)
        }
    }

    private fun openOutputStream(uri: Uri): OutputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            File(path).outputStream()
        } else {
            contentResolver.openOutputStream(uri, "wt")
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: return 0L).length()
            } else {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            0L
        }
    }

    private fun readImageMetadata(uri: Uri, preserveMetadata: Boolean): ImageMetadataSnapshot {
        var width = 0
        var height = 0
        var orientation = ExifInterface.ORIENTATION_NORMAL
        val attributes = mutableMapOf<String, String>()

        openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            width = options.outWidth
            height = options.outHeight
        }

        openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            if (preserveMetadata) {
                for (tag in EXIF_TAG_NAMES) {
                    if (tag in EXIF_SKIP_TAGS) continue
                    val value = cancellationAwareRunCatching { exif.getAttribute(tag) }.getOrNull()
                    if (value != null) {
                        attributes[tag] = value
                    }
                }
            }
        }

        return ImageMetadataSnapshot(width, height, orientation, attributes)
    }

    private fun calculateSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var inSampleSize = 1
        if (srcWidth > targetWidth || srcHeight > targetHeight) {
            val halfWidth = srcWidth / 2
            val halfHeight = srcHeight / 2
            while ((halfWidth / inSampleSize) >= targetWidth && (halfHeight / inSampleSize) >= targetHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    // CHANGED: Apply all eight EXIF orientations in a separate pass, then scale.
    // EXIF orientations 5 and 7 require both rotation and reflection (transpose/transverse).
    private fun transformBitmap(
        bitmap: Bitmap,
        orientation: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val orientationMatrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> orientationMatrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> orientationMatrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> orientationMatrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                orientationMatrix.setRotate(90f)
                orientationMatrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> orientationMatrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                orientationMatrix.setRotate(-90f)
                orientationMatrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> orientationMatrix.setRotate(270f)
            else -> Unit // Normal or undefined orientation.
        }

        val orientedBitmap = if (orientationMatrix.isIdentity) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                orientationMatrix,
                true
            )
        }

        // Scale only after orientation, using actual oriented dimensions.
        var result: Bitmap? = null
        try {
            result =
                if (orientedBitmap.width != targetWidth || orientedBitmap.height != targetHeight) {
                    orientedBitmap.scale(targetWidth, targetHeight)
                } else {
                    orientedBitmap
                }
            return result
        } finally {
            if (result !== orientedBitmap && orientedBitmap !== bitmap) orientedBitmap.recycle()
        }
    }

    // CHANGED: BitmapFactory/Bitmap.compress() discard source EXIF. Copy readable EXIF
    // attributes to the encoded temporary file, update dimension tags, and clear orientation
    // because the image pixels have already been rotated/reflected.
    private fun applyExifMetadata(
        snapshot: ImageMetadataSnapshot,
        outputFile: File,
        outputWidth: Int,
        outputHeight: Int,
        preserveMetadata: Boolean
    ) {
        if (!preserveMetadata || snapshot.attributes.isEmpty()) return

        val outputExif = ExifInterface(outputFile.absolutePath)
        for ((tag, value) in snapshot.attributes) {
            val valueToWrite = when (tag) {
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_PIXEL_X_DIMENSION -> outputWidth.toString()

                ExifInterface.TAG_IMAGE_LENGTH,
                ExifInterface.TAG_PIXEL_Y_DIMENSION -> outputHeight.toString()

                else -> value
            }
            cancellationAwareRunCatching {
                outputExif.setAttribute(tag, valueToWrite)
            }
        }

        outputExif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        outputExif.saveAttributes()
    }

    private fun compositeBackground(
        bitmap: Bitmap,
        backgroundColor: Color
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = createBitmap(width, height)
        try {
            val canvas = Canvas(output)

            val colorInt = android.graphics.Color.argb(
                (backgroundColor.alpha * 255).toInt(),
                (backgroundColor.red * 255).toInt(),
                (backgroundColor.green * 255).toInt(),
                (backgroundColor.blue * 255).toInt()
            )
            canvas.drawColor(colorInt)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return output
        } catch (e: Throwable) {
            output.recycle()
            throw e
        }
    }

    private fun mapCompressFormat(format: OutputFormat): Bitmap.CompressFormat {
        return when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.WEBP_LOSSY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            OutputFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        }
    }
}

