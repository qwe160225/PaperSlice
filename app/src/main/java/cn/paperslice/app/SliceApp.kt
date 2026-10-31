package cn.paperslice.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.File

data class UiState(
    val busy: Boolean = false, val stage: String = "选择一份 PDF，开始提取", val percent: Int = -1,
    val source: String = "", val name: String = "", val bytes: Long = 0, val pages: Int = 0,
    val started: Long = 0, val result: String = "", val outputPages: Int = 0, val error: Boolean = false
)

object State {
    @Volatile var value = UiState()
        private set
    @Synchronized fun update(context: Context, change: (UiState) -> UiState) {
        value = change(value)
        val s = value
        context.getSharedPreferences("session", 0).edit()
            .putBoolean("busy", s.busy).putString("stage", s.stage)
            .putString("source", s.source).putString("name", s.name).putLong("bytes", s.bytes)
            .putInt("pages", s.pages).putString("result", s.result).putInt("outputPages", s.outputPages).commit()
    }
    fun restore(context: Context) {
        val p = context.getSharedPreferences("session", 0)
        val interrupted = p.getBoolean("busy", false)
        value = UiState(source = p.getString("source", "") ?: "", name = p.getString("name", "") ?: "",
            bytes = p.getLong("bytes", 0), pages = p.getInt("pages", 0),
            result = (p.getString("result", "") ?: "").takeIf { File(it).isFile } ?: "",
            outputPages = p.getInt("outputPages", 0),
            stage = if (interrupted) "上次任务被系统中断，请重新开始" else p.getString("stage", "选择一份 PDF，开始提取") ?: "",
            error = interrupted)
    }
    fun bundle(): Bundle = Bundle().apply {
        val s = value
        putBoolean("busy", s.busy); putBoolean("error", s.error); putInt("percent", s.percent)
        putString("stage", s.stage); putString("source", s.source); putString("name", s.name)
        putLong("bytes", s.bytes); putInt("pages", s.pages); putString("result", s.result)
        putInt("outputPages", s.outputPages); putLong("started", s.started)
    }
    fun receive(b: Bundle) {
        value = UiState(busy=b.getBoolean("busy"), error=b.getBoolean("error"), percent=b.getInt("percent", -1),
            stage=b.getString("stage", ""), source=b.getString("source", ""), name=b.getString("name", ""),
            bytes=b.getLong("bytes"), pages=b.getInt("pages"), result=b.getString("result", ""),
            outputPages=b.getInt("outputPages"), started=b.getLong("started"))
    }
    // Frequent progress updates stay in memory; do not write preferences for each chunk.
    @Synchronized fun progress(stage: String, percent: Int) { value = value.copy(stage = stage, percent = percent) }
}

class SliceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        State.restore(this)
        if (!getProcessName().endsWith(":pdf")) return
        File(cacheDir, "pdf-scratch").deleteRecursively()
        File(cacheDir, "source.part").delete()
        File(filesDir, "results").listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
    }
}
