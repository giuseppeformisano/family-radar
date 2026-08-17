package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max

object ImageUtils {

    /**
     * Creates a temporary image file in cache and returns its content Uri via FileProvider.
     * Used for full-resolution live camera captures.
     */
    fun createTempImageUri(context: Context): Uri? {
        return try {
            val storageDir = File(context.cacheDir, "camera_snaps").apply { mkdirs() }
            val tempFile = File.createTempFile("radar_snap_${System.currentTimeMillis()}_", ".jpg", storageDir)
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reads an image Uri, resizes it up to maxDimension (default 1280px),
     * compresses as JPEG with quality 85%, and encodes to a clean Base64 string.
     */
    suspend fun uriToBase64(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1280,
        quality: Int = 85
    ): String? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // 1. Decode bounds to prevent OutOfMemory
            var inputStream: InputStream? = contentResolver.openInputStream(uri) ?: return@withContext null
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            inputStream?.close()

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

            // 2. Compute sample size
            var sampleSize = 1
            val maxSide = max(origWidth, origHeight)
            if (maxSide > maxDimension) {
                sampleSize = maxSide / maxDimension
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = max(1, sampleSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            // 3. Decode bitmap with sample size
            inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val decodedBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream.close()

            if (decodedBitmap == null) return@withContext null

            // 4. Handle EXIF rotation
            val orientedBitmap = try {
                val exifStream = contentResolver.openInputStream(uri)
                if (exifStream != null) {
                    val exif = ExifInterface(exifStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    exifStream.close()
                    rotateBitmap(decodedBitmap, orientation)
                } else {
                    decodedBitmap
                }
            } catch (_: Exception) {
                decodedBitmap
            }

            // 5. Scale to exact max dimension if still oversized
            val finalBitmap = if (orientedBitmap.width > maxDimension || orientedBitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / max(orientedBitmap.width, orientedBitmap.height)
                val targetW = max(1, (orientedBitmap.width * scale).toInt())
                val targetH = max(1, (orientedBitmap.height * scale).toInt())
                val scaled = Bitmap.createScaledBitmap(orientedBitmap, targetW, targetH, true)
                if (scaled != orientedBitmap && orientedBitmap != decodedBitmap) {
                    orientedBitmap.recycle()
                }
                scaled
            } else {
                orientedBitmap
            }

            if (decodedBitmap != orientedBitmap && decodedBitmap != finalBitmap) {
                decodedBitmap.recycle()
            }

            // 6. Compress to JPEG and encode to Base64
            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            outputStream.close()

            if (finalBitmap != orientedBitmap) {
                finalBitmap.recycle()
            }
            if (orientedBitmap != decodedBitmap) {
                orientedBitmap.recycle()
            }

            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reads a Uri and returns an in-memory Bitmap scaled to maxDimension (default 1280px).
     */
    fun uriToBitmap(context: Context, uri: Uri, maxDimension: Int = 1280): Bitmap? {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val decoded = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (decoded == null) return null

            // EXIF rotation
            val orientedBitmap = try {
                val exifStream = contentResolver.openInputStream(uri)
                if (exifStream != null) {
                    val exif = ExifInterface(exifStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    exifStream.close()
                    rotateBitmap(decoded, orientation)
                } else {
                    decoded
                }
            } catch (_: Exception) {
                decoded
            }

            if (orientedBitmap.width > maxDimension || orientedBitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / max(orientedBitmap.width, orientedBitmap.height)
                val targetW = max(1, (orientedBitmap.width * scale).toInt())
                val targetH = max(1, (orientedBitmap.height * scale).toInt())
                val scaled = Bitmap.createScaledBitmap(orientedBitmap, targetW, targetH, true)
                if (scaled != orientedBitmap && orientedBitmap != decoded) {
                    orientedBitmap.recycle()
                }
                scaled
            } else {
                orientedBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts a direct Android Bitmap to Base64 string with high fidelity.
     */
    fun bitmapToBase64(
        bitmap: Bitmap,
        maxDimension: Int = 1280,
        quality: Int = 85
    ): String? {
        return try {
            val finalBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
                val targetW = max(1, (bitmap.width * scale).toInt())
                val targetH = max(1, (bitmap.height * scale).toInt())
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            outputStream.close()

            if (finalBitmap != bitmap) {
                finalBitmap.recycle()
            }

            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decodes a Base64 string into an Android Bitmap.
     * Safely handles MIME prefixes ("data:image/...;base64,"), newlines, spaces, and encoding artifacts.
     */
    fun base64ToBitmap(base64Str: String?): Bitmap? {
        if (base64Str.isNullOrBlank()) return null
        return try {
            var cleanStr = base64Str.trim()
            if (cleanStr.contains(",")) {
                cleanStr = cleanStr.substringAfter(",")
            }
            cleanStr = cleanStr.replace("\n", "").replace("\r", "").replace(" ", "").trim()

            val decodedBytes = Base64.decode(cleanStr, Base64.DEFAULT)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves a Bitmap directly to the device's MediaStore Pictures gallery.
     */
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        title: String = "Radar_Photo_${System.currentTimeMillis()}"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val filename = "${title}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FamilyRadar")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val imageUri = resolver.insert(collectionUri, contentValues) ?: return@withContext null

            resolver.openOutputStream(imageUri)?.use { outputStream: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            imageUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves a Base64 encoded string directly to the device's MediaStore Pictures gallery.
     */
    suspend fun saveBase64ToGallery(
        context: Context,
        base64Str: String,
        title: String = "Radar_Photo_${System.currentTimeMillis()}"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bmp = base64ToBitmap(base64Str) ?: return@withContext false
            val uri = saveBitmapToGallery(context, bmp, title)
            uri != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }
}
