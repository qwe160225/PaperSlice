package cn.paperslice.app

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TaskCancelled : IOException("已取消")
class CancelToken {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw TaskCancelled() }
    fun isCancelled() = cancelled.get()
}
fun interface NativeProgress { fun update(percent: Int) }

/** Loaded only in the :pdf worker process. Arguments are standard UTF-8. */
object PdfEngine {
    init { System.loadLibrary("paperslice") }
    private external fun countNative(path: ByteArray, password: ByteArray, repair: Boolean): Int
    private external fun extractNative(path: ByteArray, password: ByteArray, selection: ByteArray,
        output: ByteArray, expected: Int, repair: Boolean, progress: NativeProgress): Boolean
    fun count(file: File, password: String, repair: Boolean): Int =
        countNative(file.absolutePath.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8), repair)
    fun extract(file: File, password: String, pages: IntArray, expected: Int, output: File,
                repair: Boolean, token: CancelToken, progress: (Int) -> Unit): Boolean {
        require(pages.isNotEmpty() && pages.all { it in 1..expected } && pages.toList() == pages.distinct().sorted()) {
            "页码必须在范围内、按原顺序且不能重复"
        }
        val ranges = mutableListOf<String>()
        var i = 0
        while (i < pages.size) {
            val first = pages[i]
            var last = first
            while (++i < pages.size && pages[i] == last + 1) last = pages[i]
            ranges.add(if (first == last) "$first" else "$first-$last")
        }
        token.check()
        return extractNative(file.absolutePath.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8),
            ranges.joinToString(",").toByteArray(Charsets.UTF_8), output.absolutePath.toByteArray(Charsets.UTF_8),
            expected, repair, NativeProgress { token.check(); progress(it) })
    }
}
