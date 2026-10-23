package app.vazie.keep.siteicons

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The images entries carry in the vault file, decoded once and kept while the vault is open.
class EntryImages(private val background: CoroutineDispatcher = Dispatchers.Default, private val load: (String) -> ByteArray?) {
    private val cache = LruCache<String, ImageBitmap>(256)

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun image(id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        return withContext(background) {
            val bytes = load(id) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(id, it) }
        }
    }

    fun clear() = cache.evictAll()
}

val LocalEntryImages = staticCompositionLocalOf<EntryImages?> { null }
