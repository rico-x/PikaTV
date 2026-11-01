package ru.pikatv.feature.images

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.pikatv.feature.reader.TvText
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An image this much taller than wide is unreadable when fitted to a TV screen. */
internal fun isTall(width: Int?, height: Int?) = width != null && height != null && width > 0 && height.toFloat() / width > 2f

internal data class StripPlan(val sampleSize: Int, val strips: List<IntRange>)

/** Source rows per decoded strip and a power-of-two subsampling that keeps strips at least viewport-wide. */
internal fun stripPlan(width: Int, height: Int, viewportWidth: Int, stripRows: Int = 1024): StripPlan {
    require(width > 0 && height > 0 && viewportWidth > 0) { "Image and viewport sizes must be positive" }
    var sample = 1
    while (width / (sample * 2) >= viewportWidth) sample *= 2
    val step = stripRows * sample
    return StripPlan(sample, (0 until height step step).map { top -> top until minOf(height, top + step) })
}

/** Image hosts never receive Pikabu cookies; a body over [maxBytes] is refused instead of filling the cache. */
internal fun downloadImage(client: OkHttpClient, url: String, target: File, maxBytes: Long = MAX_IMAGE_BYTES) {
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Пустой ответ")
        if (body.contentLength() > maxBytes) throw IOException("Изображение слишком большое")
        body.byteStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("Изображение слишком большое")
                output.write(buffer, 0, read)
            }
        } }
    }
}

private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
private val imageClient by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() }

/** One downloaded image in the cache; only the latest one is kept on disk. */
private class RegionSource(private val decoder: BitmapRegionDecoder) : Closeable {
    val width = decoder.width
    val height = decoder.height

    // Fails softly: a strip may still be decoding when the viewer closes and recycles the decoder.
    fun decode(rows: IntRange, sampleSize: Int): ImageBitmap? = runCatching {
        decoder.decodeRegion(Rect(0, rows.first, width, rows.last + 1), BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
    }.getOrNull()

    override fun close() = decoder.recycle()

    companion object {
        fun open(context: Context, url: String): RegionSource {
            val dir = File(context.cacheDir, "viewer").apply { mkdirs() }
            val file = File(dir, url.hashCode().toUInt().toString(16))
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
            if (!file.exists()) {
                val part = File(dir, file.name + ".part")
                try { downloadImage(imageClient, url, part) } catch (e: IOException) { part.delete(); throw e }
                if (!part.renameTo(file)) throw IOException("Не удалось сохранить изображение")
            }
            // The pre-31 factory may return null for an unsupported format.
            val decoder: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
            return RegionSource(decoder ?: throw IOException("Формат изображения не поддерживается"))
        }
    }
}

/** Shows the image at viewport width in strips, so a tall screenshot is never decoded whole. Scrolling is driven by [state]. */
@Composable
internal fun WidthImage(url: String, viewportWidth: Int, state: LazyListState, onFailure: () -> Unit) {
    val context = LocalContext.current
    var source by remember(url) { mutableStateOf<RegionSource?>(null) }
    LaunchedEffect(url) {
        source = try { withContext(Dispatchers.IO) { RegionSource.open(context.applicationContext, url) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (source == null) onFailure()
    }
    DisposableEffect(source) { val opened = source; onDispose { opened?.close() } }
    val opened = source
    if (opened == null) {
        Text("Загрузка изображения…", color = TvText, fontSize = 14.sp, modifier = Modifier.padding(48.dp))
        return
    }
    val plan = remember(opened, viewportWidth) { stripPlan(opened.width, opened.height, viewportWidth) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(plan.strips.size) { index ->
            val rows = plan.strips[index]
            var bitmap by remember(opened, index) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(opened, index) { bitmap = withContext(Dispatchers.IO) { opened.decode(rows, plan.sampleSize) } }
            Box(Modifier.fillMaxWidth().aspectRatio(opened.width.toFloat() / (rows.last - rows.first + 1))) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            }
        }
    }
}
