package cool.hin.memox.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 插入图片时的「感知无损」压缩。
 *
 * 目标：把动辄 3–4MB 的插入图压到 [THRESHOLD_BYTES] 以下，让 WebDAV / Cloud Function 同步不再失败，
 * 同时尽量不牺牲观感。注意这**不是**字节级无损（JPEG / WebP 有损分支必然改变像素）；对透明或
 * 文字/截图类内容会优先走真无损编码。
 *
 * 格式决策按**图像内容**而非源扩展名（PNG 可能是照片导出，JPG 也可能是文字截图）：
 * | 透明 | 少色（文字/截图/图表） | 输出 |
 * |------|------------------------|------|
 * | 有   | 是                     | WebP 无损 |
 * | 有   | 否                     | WebP 有损（JPEG 无 alpha 通道，会把透明烧成黑底） |
 * | 无   | 是                     | WebP 无损（文字边缘锐利，不发虚） |
 * | 无   | 否                     | JPEG q=[JPEG_QUALITY]，保持原分辨率 |
 *
 * 兜底顺序：首选编码 → 有损质量阶梯 → 降分辨率阶梯。任何情况下，若最终结果不比原文件更小，
 * 一律放弃压缩、保留原图（WebP 无损碰上复杂截图确实可能变大）。
 *
 * 关于 OxiPNG：它是字节级 PNG 重压器（重算 filter + zopfli，不动像素），Android 没有原生等价实现；
 * `Bitmap.compress(PNG)` 走 skia 默认压缩档，且图已 decode 成 ARGB_8888、丢掉了原 PNG 的调色板与
 * 位深优化，结果常比原文件更大。WebP 无损是这里唯一可用的原生替代（对文字截图通常还更小）。
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"

    /** 超过该体积才尝试压缩（1 MiB）。 */
    const val THRESHOLD_BYTES = 1L * 1024 * 1024

    const val MIME_TYPE_JPEG = "image/jpeg"
    const val MIME_TYPE_PNG = "image/png"
    const val MIME_TYPE_WEBP = "image/webp"

    /** 照片类（多色、不透明）走 JPEG 的质量。 */
    private const val JPEG_QUALITY = 92

    /** WEBP_LOSSLESS 的「压缩努力度」（非画质），见 [encodeLossless]。 */
    private const val LOSSLESS_EFFORT = 90

    /** 有损兜底阶梯，仅在首选编码结果仍超阈值时依次尝试。 */
    private val LOSSY_QUALITY_STEPS = intArrayOf(95, 90, 85)

    /** 降分辨率兜底阶梯（最长边），仅在所有质量档都无法达标时使用。 */
    private val DOWNSCALE_MAX_EDGES = intArrayOf(2400, 2000, 1600)

    /** 降分辨率阶段使用的固定质量。 */
    private const val DOWNSCALE_QUALITY = 88

    /** 判定「少色 / 图形类」的绝对唯一色数阈值。 */
    private const val LOW_COLOR_ABSOLUTE = 4096

    /** 判定「少色 / 图形类」的唯一色占比阈值：照片接近 1，截图通常远低于此。 */
    private const val LOW_COLOR_RATIO = 0.2f

    /** alpha 低于此值即视为存在真实透明像素。 */
    private const val ALPHA_OPAQUE_THRESHOLD = 250

    /** 统计唯一色时的目标采样像素数。 */
    private const val COLOR_SAMPLE_TARGET = 30_000

    /** 单次 decode 允许的最大像素数（约 24MP），超过先降采样以避免必然 OOM。 */
    private const val MAX_DECODE_PIXELS = 24_000_000L

    /** 压缩结果：新的 mime 与文件扩展名（两者必须与 [mimeTypeToFileExtension] 保持一致）。 */
    data class Result(val mimeType: String, val extension: String)

    /**
     * [compressFile] 的返回：压缩成功后文件的最终文件名（若换了容器格式则为新名字）、mime 与扩展名。
     * 调用方据此更新数据库里的附件记录（[FileAttachment.localName] / [FileAttachment.mimeType]）。
     */
    data class CompressedFile(val localName: String, val mimeType: String, val extension: String)

    /**
     * 尝试压缩 [source]（就地覆盖写入）。
     *
     * @param sourceMimeType 由 [BitmapFactory] 探测到的原始 mime。
     * @return 压缩成功时返回新的 mime/扩展名；不需要压缩、压缩失败或结果不比原文件小时返回 `null`，
     *   此时 [source] 内容保持原样，调用方按原 mime/扩展名处理即可。
     */
    fun compress(
        source: File,
        sourceMimeType: String,
        thresholdBytes: Long = THRESHOLD_BYTES,
    ): Result? {
        val encoded = tryEncode(source, sourceMimeType, thresholdBytes) ?: return null
        source.writeBytes(encoded.bytes)
        Log.d(
            TAG,
            "Compressed ${source.length()} bytes as ${encoded.mimeType} (in-place)",
        )
        return Result(encoded.mimeType, encoded.extension)
    }

    /**
     * 压缩一条**已存在的笔记图片附件**：就地覆盖；若压缩后换了容器格式（如 PNG→WebP）则自动用新
     * 扩展名另存并删除旧文件，返回最终的 [CompressedFile]（含新文件名）。
     *
     * 与 [compress] 的区别：[compress] 只把字节写回同一个 [source] 文件、由调用方决定是否改名；这里因为
     * 要回写数据库，必须自己把「换格式→改名」封装好，调用方直接拿 [CompressedFile.localName] 去更新
     * [FileAttachment] 即可。
     *
     * @return 压缩成功且结果比原文件更小则返回 [CompressedFile]；否则返回 `null`（原文件保持不变）。
     */
    fun compressFile(
        file: File,
        sourceMimeType: String,
        thresholdBytes: Long = THRESHOLD_BYTES,
    ): CompressedFile? {
        val encoded = tryEncode(file, sourceMimeType, thresholdBytes) ?: return null
        val currentExt = file.extension.lowercase()
        if (encoded.extension == currentExt) {
            file.writeBytes(encoded.bytes)
            Log.d(TAG, "Compressed existing '${file.name}' in-place as ${encoded.mimeType}")
            return CompressedFile(file.name, encoded.mimeType, encoded.extension)
        }
        // 换了格式：用新名字另存，删旧文件。
        val newName = "${UUID.randomUUID()}.${encoded.extension}"
        val newFile = File(file.parent, newName)
        newFile.writeBytes(encoded.bytes)
        file.delete()
        Log.d(
            TAG,
            "Compressed existing '${file.name}' -> '$newName' as ${encoded.mimeType}",
        )
        return CompressedFile(newName, encoded.mimeType, encoded.extension)
    }

    /**
     * 解码 + 编码的核心逻辑（不含写盘）。返回体积小于原文件的最优 [Encoded]，否则返回 `null`。
     * [compress] 与 [compressFile] 共用，避免重复 decode / 判定。
     */
    private fun tryEncode(
        source: File,
        sourceMimeType: String,
        thresholdBytes: Long,
    ): Encoded? {
        val originalSize = source.length()
        if (originalSize <= thresholdBytes) return null

        // 动画 WebP 与静态 WebP 无法通过 mime 区分，decode 只会拿到第一帧，压缩会毁掉动画。
        // WebP 源本身也已经足够小，直接跳过。
        if (sourceMimeType == MIME_TYPE_WEBP) return null

        val decoded = decodeWithOomGuard(source) ?: return null
        var bitmap: Bitmap = decoded
        try {
            bitmap = applyExifRotation(source, decoded)

            val hasTransparency = bitmap.hasRealTransparency()
            val lowColor = bitmap.isLowColorContent()
            val best = encodeWithFallbacks(bitmap, hasTransparency, lowColor, thresholdBytes)

            if (best == null) return null
            if (best.bytes.size >= originalSize) {
                Log.d(
                    TAG,
                    "Compression skipped: result ${best.bytes.size}B >= original ${originalSize}B",
                )
                return null
            }
            return best
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed for '${source.absolutePath}'", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Compression ran out of memory for '${source.absolutePath}'", e)
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private class Encoded(
        val bytes: ByteArray,
        val mimeType: String,
        val extension: String,
    )

    /**
     * 按「首选编码 → 有损质量阶梯 → 降分辨率阶梯」依次尝试，返回第一个达标（<= [thresholdBytes]）
     * 的结果；若全部不达标，则返回其中体积最小的一个。
     */
    private fun encodeWithFallbacks(
        bitmap: Bitmap,
        hasTransparency: Boolean,
        lowColor: Boolean,
        thresholdBytes: Long,
    ): Encoded? {
        var best: Encoded? = null

        fun consider(candidate: Encoded?): Encoded? {
            if (candidate == null) return null
            if (best == null || candidate.bytes.size < best!!.bytes.size) best = candidate
            return if (candidate.bytes.size <= thresholdBytes) candidate else null
        }

        // 阶段 1：全分辨率首选编码。
        val preferLossless = hasTransparency || lowColor
        val first =
            if (preferLossless) encodeLossless(bitmap)
            else encode(bitmap, Bitmap.CompressFormat.JPEG, JPEG_QUALITY, MIME_TYPE_JPEG, "jpg")
        consider(first)?.let {
            return it
        }

        // 阶段 2：全分辨率 + 有损质量阶梯。透明或少色内容用 WebP 有损（保 alpha / 文字更锐利），
        // 其余用 JPEG（兼容性最好、照片体积最优）。
        for (quality in LOSSY_QUALITY_STEPS) {
            // JPEG 首选已用过 92，无需再跑 95。
            if (!preferLossless && quality >= JPEG_QUALITY) continue
            consider(encodeLossy(bitmap, hasTransparency, lowColor, quality))?.let {
                return it
            }
        }

        // 阶段 3：降分辨率兜底。
        for (maxEdge in DOWNSCALE_MAX_EDGES) {
            if (max(bitmap.width, bitmap.height) <= maxEdge) continue
            val scaled = bitmap.scaleToMaxEdge(maxEdge) ?: continue
            try {
                consider(encodeLossy(scaled, hasTransparency, lowColor, DOWNSCALE_QUALITY))?.let {
                    return it
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }

        return best
    }

    /** 无损编码：API 30+ 用 WEBP_LOSSLESS，API 29 用 quality=100 的旧 WEBP，更低版本只能退回 PNG。 */
    @Suppress("DEPRECATION")
    private fun encodeLossless(bitmap: Bitmap): Encoded? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                // 此处 quality 表示「压缩努力度」而非画质，100 最慢。取 90 是体积/耗时的平衡点：
                // 相比 100 体积只差约 1%，但编码快接近一倍。
                encode(
                    bitmap,
                    Bitmap.CompressFormat.WEBP_LOSSLESS,
                    LOSSLESS_EFFORT,
                    MIME_TYPE_WEBP,
                    "webp",
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                // Android Q 起，旧 WEBP + quality=100 即为无损。
                encode(bitmap, Bitmap.CompressFormat.WEBP, 100, MIME_TYPE_WEBP, "webp")
            else ->
                // API 21–28 的旧 WEBP 即使 quality=100 仍是有损，无损只能用 PNG
                // （通常压不小，会被「结果不比原图小就保留原图」的规则拦掉，随后由有损阶梯接手）。
                encode(bitmap, Bitmap.CompressFormat.PNG, 100, MIME_TYPE_PNG, "png")
        }

    @Suppress("DEPRECATION")
    private fun encodeLossy(
        bitmap: Bitmap,
        hasTransparency: Boolean,
        lowColor: Boolean,
        quality: Int,
    ): Encoded? {
        // JPEG 没有 alpha 通道，透明会被烧成黑底；文字类图 WebP 有损也明显优于 JPEG。
        if (!hasTransparency && !lowColor) {
            return encode(bitmap, Bitmap.CompressFormat.JPEG, quality, MIME_TYPE_JPEG, "jpg")
        }
        val format =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        return encode(bitmap, format, quality, MIME_TYPE_WEBP, "webp")
    }

    private fun encode(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        mimeType: String,
        extension: String,
    ): Encoded? {
        return try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, out)) {
                Log.w(TAG, "Encoder rejected format=$format quality=$quality")
                return null
            }
            Encoded(out.toByteArray(), mimeType, extension)
        } catch (e: Exception) {
            Log.w(TAG, "Encoding failed for format=$format quality=$quality", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Encoding ran out of memory for format=$format quality=$quality", e)
            null
        }
    }

    /**
     * 全分辨率优先解码；仅当像素数超过 [MAX_DECODE_PIXELS] 或真的 OOM 时才降采样，
     * 以贴合「尽量不改分辨率」的诉求。
     */
    private fun decodeWithOomGuard(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize) > MAX_DECODE_PIXELS) sampleSize *= 2

        while (sampleSize <= 16) {
            try {
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                // decode 返回 null 属于「文件不是可解码的图片」，重试没有意义。
                return BitmapFactory.decodeFile(file.path, options)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Decode OOM at inSampleSize=$sampleSize, retrying smaller", e)
                sampleSize *= 2
            }
        }
        return null
    }

    /**
     * 重编码后的 JPEG / WebP 不会带上原文件的 EXIF，因此必须在像素层面把旋转烧进去，
     * 否则压缩后的图片会是歪的。
     */
    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val degrees =
            try {
                when (
                    ExifInterface(file.path)
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read EXIF orientation", e)
                0f
            }
        if (degrees == 0f) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Rotation ran out of memory, keeping original orientation", e)
            bitmap
        }
    }

    /**
     * [Bitmap.hasAlpha] 只说明存在 alpha 通道，不代表真的有透明像素。这里逐行扫描（每次只驻留一行，
     * 内存开销可忽略），一旦发现半透明像素立刻返回，避免把「有 alpha 通道但全不透明」的图误判成
     * 需要保 alpha 而错失 JPEG 分支。
     */
    private fun Bitmap.hasRealTransparency(): Boolean {
        if (!hasAlpha()) return false
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if ((pixel ushr 24) < ALPHA_OPAQUE_THRESHOLD) return true
            }
        }
        return false
    }

    /**
     * 稀疏抽样统计唯一色数，判断是不是「文字 / 截图 / 图表」这类少色内容。
     *
     * 直接在全分辨率位图上按步长抽样，而不是先缩略再统计——缩放插值会在文字边缘造出大量中间色，
     * 把截图的唯一色数显著高估。
     */
    private fun Bitmap.isLowColorContent(): Boolean {
        val total = width.toLong() * height
        val step = max(1, sqrt(total.toDouble() / COLOR_SAMPLE_TARGET).roundToInt())
        val colors = HashSet<Int>()
        val row = IntArray(width)
        var sampled = 0
        var y = 0
        while (y < height) {
            getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                colors.add(row[x] and 0x00FFFFFF)
                sampled++
                x += step
            }
            y += step
        }
        if (sampled == 0) return false
        return colors.size < LOW_COLOR_ABSOLUTE ||
            colors.size.toFloat() / sampled < LOW_COLOR_RATIO
    }

    /** 等比缩放到最长边不超过 [maxEdge]。失败返回 `null`，调用方跳过该档。 */
    private fun Bitmap.scaleToMaxEdge(maxEdge: Int): Bitmap? {
        val longest = max(width, height)
        if (longest <= maxEdge) return this
        val ratio = maxEdge.toFloat() / longest
        val targetWidth = max(1, (width * ratio).roundToInt())
        val targetHeight = max(1, (height * ratio).roundToInt())
        return try {
            Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Downscale ran out of memory at maxEdge=$maxEdge", e)
            null
        }
    }
}
