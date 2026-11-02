package zone.dal.driveguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 카드 이미지용 최소 로더. 메모리(LruCache) + HTTP 디스크 캐시(HttpResponseCache)를 쓰고,
 * 카드 폭에 맞춰 다운샘플링해 디코딩한다.
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    private val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun installDiskCache(context: Context) {
        if (HttpResponseCache.getInstalled() == null) {
            HttpResponseCache.install(File(context.cacheDir, "images"), 50L * 1024 * 1024)
        }
    }

    /** [onMissing]은 URL이 없거나 받기에 실패했을 때 호출된다(플레이스홀더 표시용). */
    fun load(scope: CoroutineScope, view: ImageView, url: String?, targetWidthPx: Int, onMissing: () -> Unit) {
        view.tag = url
        if (url == null) {
            onMissing()
            return
        }
        memoryCache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url, targetWidthPx) }
            // 그 사이 같은 뷰가 다른 장소에 재사용됐으면 무시한다.
            if (view.tag != url) return@launch
            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                view.setImageBitmap(bitmap)
            } else {
                onMissing()
            }
        }
    }

    private fun fetch(url: String, targetWidthPx: Int): Bitmap? = try {
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000
            readTimeout = 10_000
            useCaches = true
            try {
                inputStream.use { it.readBytes() }
            } finally {
                disconnect()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        Log.w(TAG, "Image load failed: $url", e)
        null
    }
}
