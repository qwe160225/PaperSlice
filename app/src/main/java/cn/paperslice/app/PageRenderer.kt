package cn.paperslice.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class PageQuality { THUMBNAIL, HD }
data class RenderRequest(val page: Int, val quality: PageQuality)

interface PageRenderer : AutoCloseable {
    fun open()
    fun render(request: RenderRequest): Bitmap
}

/** All methods run on the single render worker, including close(). */
class AndroidPageRenderer(
    private val context: Context,
    private val uri: Uri,
    private val total: Int,
    private var password: String,
    private val repair: Boolean,
    private val bytes: Long,
    private val cancelled: () -> Boolean
) : PageRenderer {
    private val temporary = File(context.cacheDir, "preview-work/${java.util.UUID.randomUUID()}")
    private var source: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var document: PdfRenderer? = null
    private val singlePageMode = password.isNotEmpty() || repair

    override fun open() {
        temporary.mkdirs()
        source = prepareInput()
        checkOpen()
        if (!singlePageMode) {
            val pfd = ParcelFileDescriptor.open(source!!, ParcelFileDescriptor.MODE_READ_ONLY)
            document = try { PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); throw e }
            require(document!!.pageCount == total) { "文件页数已变化，请返回重新读取" }
        }
    }

    override fun render(request: RenderRequest): Bitmap {
        checkOpen()
        require(request.page in 0 until total)
        if (!singlePageMode) return document!!.openPage(request.page).use { renderBitmap(it, request.quality) }
        val file = File(temporary, "page.pdf")
        try {
            PdfEngine.extract(source!!, password, intArrayOf(request.page + 1), total, file, repair, CancelToken()) { checkOpen() }
            checkOpen()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val single = try { PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); throw e }
            return single.use { it.openPage(0).use { page -> renderBitmap(page, request.quality) } }
        } finally { file.delete() }
    }

    private fun renderBitmap(page: PdfRenderer.Page, quality: PageQuality): Bitmap {
        val size = if (quality == PageQuality.HD) PreviewNavigation.hdBitmapSize(page.width, page.height)
            else PreviewNavigation.bitmapSize(page.width, page.height)
        val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }

    private fun prepareInput(): File {
        val cached = File(context.cacheDir, "source.pdf")
        if (context.getSharedPreferences("cache", 0).getString("uri", "") == uri.toString() && cached.isFile) return cached
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开源文件")
        if (runCatching { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET) }.isSuccess) {
            descriptor = pfd
            return File("/proc/self/fd/${pfd.fd}")
        }
        pfd.close()
        require(temporary.usableSpace > maxOf(bytes, 0) + 64L * 1024 * 1024) { "空间不足，无法准备预览副本" }
        val copy = File(temporary, "source.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(copy).use { out ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    checkOpen()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (temporary.usableSpace < 64L * 1024 * 1024) throw IOException("剩余空间不足")
                    out.write(buffer, 0, n)
                }
            }
        } ?: throw IOException("无法读取源文件")
        return copy
    }

    private fun checkOpen() { if (cancelled()) throw IOException("预览已关闭") }
    override fun close() {
        runCatching { document?.close() }
        runCatching { descriptor?.close() }
        password = ""
        temporary.deleteRecursively()
    }
}

/** Resolution is part of the key: an in-flight thumbnail must never satisfy an HD request. */
class RenderQueue {
    private val pending = java.util.ArrayDeque<RenderRequest>()
    private var active: RenderRequest? = null
    @Synchronized fun replace(requests: List<RenderRequest>) {
        pending.clear()
        requests.asSequence().filter { it.page >= 0 && it != active }.distinct().take(16).forEach { pending.add(it) }
    }
    @Synchronized fun next(): RenderRequest? {
        check(active == null)
        return pending.pollFirst()?.also { active = it }
    }
    @Synchronized fun complete(request: RenderRequest) { if (active == request) active = null }
}
