package cn.paperslice.app

import android.app.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private class ExportCleanupException(message: String, cause: Throwable) : IOException(message, cause)

class PdfService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var token = CancelToken()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationAt = 0L
    private var taskAction: String? = null
    companion object {
        const val ANALYZE = "analyze"
        const val EXTRACT = "extract"
        const val EXPORT = "export"
        const val CANCEL = "cancel"
        private const val CHANNEL = "pdf_tasks"
        private const val NOTIFICATION = 101
        private val running = AtomicBoolean(false)
        private const val RESERVE = 64L * 1024 * 1024
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == 1) runCatching { msg.replyTo?.send(Message.obtain().apply { what = 1; data = State.bundle() }) }
        true
    })
    override fun onBind(intent: Intent?) = messenger.binder
    private fun stopWorker(message: String, error: Boolean = false) {
        token.cancel()
        State.update(this) { it.copy(busy = false, percent = -1, stage = message, error = error) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        // Only this application's dedicated worker dies. Main UI stays alive.
        Process.killProcess(Process.myPid())
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PDF 提取进度", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (taskAction == EXPORT && running.get()) token.cancel()
            else if (running.get()) stopWorker("已取消，原文件未改动")
            else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        token = CancelToken()
        val taskToken = token
        val action = intent.action
        taskAction = action
        val repair = intent.getBooleanExtra("repair", false)
        // Password is never persisted or placed in notification pending intents.
        val password = intent.getStringExtra("password") ?: ""
        intent.removeExtra("password")
        State.update(this) { it.copy(busy = true, error = false, percent = -1, started = SystemClock.elapsedRealtime(), stage = "准备任务…") }
        try {
            startForeground(NOTIFICATION, notification("准备任务…", -1))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paperslice:task").apply {
                setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            running.set(false)
            State.update(this) { it.copy(busy = false, error = true, stage = "无法开始后台任务，请保持应用在前台后重试") }
            stopSelf(startId); return START_NOT_STICKY
        }
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            try {
                when (action) {
                    ANALYZE -> analyze(Uri.parse(intent.getStringExtra("uri")!!), password, repair, taskToken)
                    EXTRACT -> extract(intent.getStringExtra("selection") ?: "", password, repair, taskToken)
                    EXPORT -> export(Uri.parse(intent.getStringExtra("uri")!!), taskToken)
                }
            } catch (e: Exception) {
                val message = when {
                    e is ExportCleanupException -> e.message ?: "保存未完成，请清理目标位置的不完整文件"
                    taskToken.isCancelled() || e is TaskCancelled -> "已取消，原文件未改动"
                    e.message?.contains("invalid password", ignoreCase = true) == true -> "需要正确的 PDF 密码，请填写后重新读取文件"
                    e is IllegalArgumentException -> e.message ?: "输入有误"
                    e is SecurityException -> "文件访问权限已失效，请重新选择文件"
                    e is IOException && !repair && (e.message?.contains("xref", true) == true || e.message?.contains("trailer", true) == true) -> "PDF 索引异常。可开启修复模式后重新读取（可能较慢）"
                    e is IOException -> "文件处理失败：${e.message?.take(180) ?: "请检查文件完整性和剩余空间"}"
                    else -> "处理失败，请重新选择文件后重试"
                }
                State.update(this) { it.copy(stage = message, error = !taskToken.isCancelled() || e is ExportCleanupException) }
            } catch (e: OutOfMemoryError) {
                State.update(this) { it.copy(stage = "此 PDF 的结构超出当前可用内存，请缩小页码范围或换用电脑处理", error = true) }
            } finally {
                File(cacheDir, "pdf-scratch").deleteRecursively()
                File(cacheDir, "source.part").delete()
                File(filesDir, "results").listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
                Handler(Looper.getMainLooper()).post {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    State.update(this) { it.copy(busy = false, percent = -1) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    running.set(false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun analyze(uri: Uri, password: String, repair: Boolean, t: CancelToken) {
        State.update(this) { it.copy(source = uri.toString(), name = "PDF 文件", pages = 0, bytes = 0, result = "", outputPages = 0) }
        File(cacheDir, "source.pdf").delete()
        getSharedPreferences("cache", 0).edit().clear().apply()
        val meta = metadata(uri)
        State.update(this) { it.copy(name = meta.first, bytes = meta.second) }
        withInput(uri, t) { file ->
            progress("读取 PDF 信息…", -1)
            t.check()
            val count = PdfEngine.count(file, password, repair)
            t.check()
            require(count > 0) { "文件没有可提取的页面" }
            State.update(this) { it.copy(pages = count, stage = "文件已就绪，请选择页码", error = false) }
        }
    }

    private fun extract(selection: String, password: String, repair: Boolean, t: CancelToken) {
        val s = State.value
        val pages = PageSelection.parse(selection, s.pages)
        val results = File(filesDir, "results").apply { mkdirs() }
        val part = File(results, "${System.currentTimeMillis()}.part")
        val result = File(results, part.nameWithoutExtension + ".pdf")
        // Keep the previous successful output until the new one commits.
        ensureSpace(results, RESERVE)
        var warnings = false
        withInput(Uri.parse(s.source), t) { input ->
            progress("整理选中页与资源…", -1)
            warnings = PdfEngine.extract(input, password, pages, s.pages, part, repair, t) { percent ->
                ensureSpace(results, RESERVE)
                progress("写入新 PDF · ${formatBytes(part.length())}", percent)
            }
        }
        t.check()
        RandomAccessFile(part, "rw").use { it.fd.sync() }
        progress("校验输出页数…", -1)
        check(PdfEngine.count(part, "", false) == pages.size) { "结果页数校验失败，未生成有效结果" }
        t.check()
        check(part.renameTo(result)) { "无法完成文件保存" }
        State.update(this) { it.copy(result = result.absolutePath, outputPages = pages.size,
            stage = "提取完成 · ${pages.size} 页 · ${formatBytes(result.length())} · 用时 ${(SystemClock.elapsedRealtime() - s.started) / 1000} 秒" + if (warnings) "；文件有修复或结构警告，请打开核对" else "", error = false) }
        results.listFiles()?.filter { it != result }?.forEach { it.delete() }
    }

    private fun export(destination: Uri, t: CancelToken) {
        require(destination.toString() != State.value.source) { "不能覆盖源 PDF，请选择新的文件名" }
        val file = File(State.value.result)
        require(file.isFile) { "结果文件已不存在，请重新提取" }
        try {
            contentResolver.openOutputStream(destination, "wt")?.use { output ->
                FileInputStream(file).use { input ->
                    copy(input, output, file.length(), t, "保存到所选位置")
                }
                output.flush()
            } ?: throw IOException("无法打开保存位置")
            t.check()
            State.update(this) { it.copy(stage = "已保存 · ${it.outputPages} 页 · ${formatBytes(file.length())}", error = false) }
        } catch (e: Exception) {
            val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }.getOrDefault(false)
            if (!deleted) {
                throw ExportCleanupException("保存未完成，所选位置可能留下不完整文件，请手动删除；应用内结果仍可重新保存。", e)
            }
            throw e
        }
    }

    private fun <T> withInput(uri: Uri, t: CancelToken, block: (File) -> T): T {
        val prefs = getSharedPreferences("cache", 0)
        val cache = File(cacheDir, "source.pdf")
        if (prefs.getString("uri", "") == uri.toString() && cache.isFile) return block(cache)
        // Many SAF providers expose a real seekable descriptor: reopen through /proc, no 1 GB copy.
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开文件")
        descriptor.use { pfd ->
            val file = File("/proc/self/fd/${pfd.fd}")
            val seekable = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    require(length > 0); raf.seek(length - 1); require(raf.read() >= 0)
                }
            }.isSuccess
            if (seekable) return block(file)
        }
        val size = State.value.bytes
        ensureSpace(cacheDir, maxOf(size, 0L) + RESERVE)
        progress("准备文件副本…", -1)
        val temporary = File(cacheDir, "source.part")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temporary).use { output -> copy(input, output, size, t, "准备文件副本"); output.fd.sync() }
        } ?: throw IOException("无法读取文件")
        t.check()
        check(temporary.renameTo(cache)) { "无法保存临时副本" }
        prefs.edit().putString("uri", uri.toString()).apply()
        return block(cache)
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, t: CancelToken, label: String) {
        val buffer = ByteArray(1024 * 1024)
        var copied = 0L
        var last = 0L
        while (true) {
            t.check()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n); copied += n
            val now = SystemClock.elapsedRealtime()
            if (now - last > 250) {
                ensureSpace(cacheDir, RESERVE)
                progress("$label · ${formatBytes(copied)}", if (total > 0) (copied * 100 / total).coerceIn(0, 100).toInt() else -1)
                last = now
            }
        }
    }

    private fun metadata(uri: Uri): Pair<String, Long> {
        var name = "PDF 文件"; var bytes = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) bytes = c.getLong(si)
            }
        }
        return name to bytes
    }
    private fun ensureSpace(path: File, needed: Long) {
        if (path.usableSpace < needed) throw IOException("可用空间不足，请至少腾出 ${formatBytes(needed)} 后重试")
    }
    private fun progress(message: String, percent: Int) {
        State.progress(message, percent)
        val now = SystemClock.elapsedRealtime()
        if (now - notificationAt >= 750 && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, percent))
            notificationAt = now
        }
    }
    private fun notification(message: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, PdfService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("轻页 PDF")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        val suffix = if (taskAction == EXPORT) "；目标位置可能有不完整文件，请手动删除，应用内结果仍可重新保存" else ""
        stopWorker("后台任务达到系统时限，请返回应用重试$suffix", true)
    }
    override fun onDestroy() {
        token.cancel(); executor.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
    bytes > 0 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> "大小未知"
}
