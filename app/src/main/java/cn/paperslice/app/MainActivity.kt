package cn.paperslice.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private val ink = Color.rgb(29, 47, 42)
    private val muted = Color.rgb(100, 116, 107)
    private val green = Color.rgb(33, 106, 88)
    private lateinit var fileTitle: TextView
    private lateinit var fileMeta: TextView
    private lateinit var selection: EditText
    private lateinit var password: EditText
    private lateinit var repair: CheckBox
    private lateinit var selectionHint: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pick: Button
    private lateinit var read: Button
    private lateinit var extract: Button
    private lateinit var cancel: Button
    private lateinit var save: Button
    private lateinit var open: Button
    private lateinit var share: Button
    private lateinit var resultPanel: LinearLayout
    private val quickButtons = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastState: UiState? = null
    private var worker: Messenger? = null
    private var bound = false
    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == 1) { State.receive(msg.data); render() }; true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { worker = Messenger(binder) }
        override fun onServiceDisconnected(name: ComponentName?) {
            worker = null
            State.receive(State.bundle().apply { putBoolean("busy", true); putString("stage", "正在恢复任务状态…") })
        }
        override fun onBindingDied(name: ComponentName?) {
            worker = null
            if (bound) { unbindService(this); bound = false }
            bindWorker()
        }
    }
    private fun bindWorker() {
        if (!bound) bound = bindService(Intent(this, PdfService::class.java), connection, BIND_AUTO_CREATE)
    }
    private val poll = object : Runnable {
        override fun run() {
            runCatching { worker?.send(Message.obtain().apply { what = 1; replyTo = replies }) }
            render(); handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(245, 247, 242)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245, 247, 242)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()) else null
            v.setPadding(0, bars?.top ?: insets.systemWindowInsetTop, 0, bars?.bottom ?: insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(28)) }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))

        body.addView(label("PAPER / SLICE", 11, green, true).apply { letterSpacing = 0.16f })
        body.addView(label("轻页 PDF · QPDF", 28, ink, true), spacing(7, 0))
        body.addView(label("只留下你需要的页面。", 15, muted), spacing(8, 24))

        val fileCard = card(body)
        fileCard.addView(label("01  /  选择文件", 12, green, true))
        fileTitle = label("你的 PDF，留在你的手机里", 18, ink, true)
        fileCard.addView(fileTitle, spacing(14, 4))
        fileMeta = label("本地处理 · 不上传 · 保留原文件", 12, muted)
        fileCard.addView(fileMeta)
        pick = button("选择 PDF 文件", true) { chooseFile() }
        fileCard.addView(pick, spacing(16, 0))

        val pagesCard = card(body)
        pagesCard.addView(label("02  /  选择页面", 12, green, true))
        selection = EditText(this).apply {
            id = R.id.page_selection; hint = "例如：1,5,10-20"; setTextSize(18f); setSingleLine(false); maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(ink); setHintTextColor(muted); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(246, 248, 245), 12, Color.rgb(220, 228, 220))
            filters = arrayOf(InputFilter.LengthFilter(20_000))
        }
        pagesCard.addView(selection, spacing(14, 8))
        val quick = LinearLayout(this)
        listOf("全部", "奇数页", "偶数页").forEach { title ->
            val b = button(title, false) { selection.setText(title) }
            b.textSize = 12f
            quick.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
            quickButtons.add(b)
        }
        pagesCard.addView(quick)
        selectionHint = label("选择文件后，可检查页码范围", 12, muted)
        pagesCard.addView(selectionHint, spacing(12, 3))
        pagesCard.addView(label("按文件原顺序输出，重复页自动去重。\n页码从文件第 1 页开始计算。", 12, muted), spacing(4, 0))

        val passwordToggle = label("文件有密码？", 12, green, true).apply { setPadding(0, dp(16), 0, dp(8)); isClickable = true }
        pagesCard.addView(passwordToggle)
        val passwordRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        password = EditText(this).apply {
            hint = "PDF 密码（不会保存）"; textSize = 14f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        passwordRow.addView(password)
        read = button("重新读取文件", false) {
            if (State.value.source.isNotBlank()) start(PdfService.ANALYZE, "uri" to State.value.source)
        }
        pagesCard.addView(passwordRow)
        repair = CheckBox(this).apply {
            text = "尝试修复损坏文件（较慢）"; textSize = 12f; setTextColor(muted)
        }
        pagesCard.addView(repair)
        pagesCard.addView(read)
        passwordToggle.setOnClickListener { passwordRow.visibility = if (passwordRow.visibility == View.GONE) View.VISIBLE else View.GONE }

        extract = button("提取为新 PDF", true) {
            val s = State.value
            try {
                PageSelection.parse(selection.text.toString(), s.pages)
                start(PdfService.EXTRACT, "selection" to selection.text.toString())
            } catch (e: IllegalArgumentException) { selection.error = e.message }
        }
        body.addView(extract, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        status = label("选择一份 PDF，开始提取", 13, muted)
        body.addView(status, spacing(16, 7))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
        cancel = button("取消当前任务", false) {
            startService(Intent(this, PdfService::class.java).setAction(PdfService.CANCEL))
            cancel.isEnabled = false; status.text = "正在取消，清理临时文件…"
        }
        body.addView(cancel, spacing(10, 0))

        resultPanel = card(body)
        resultPanel.addView(label("新 PDF 已就绪", 17, ink, true))
        save = button("保存到…", true) { saveResult() }
        resultPanel.addView(save, spacing(12, 6))
        val resultActions = LinearLayout(this)
        open = button("打开查看", false) { openResult(false) }
        share = button("分享", false) { openResult(true) }
        resultActions.addView(open, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        resultActions.addView(share, LinearLayout.LayoutParams(0, dp(44), 1f))
        resultPanel.addView(resultActions)

        body.addView(label("轻量处理，为大文件留出空间。", 12, muted), spacing(18, 6))
        body.addView(label("原画质提取，输出不加密。书签、表单及跨页链接可能无法完整保留；原数字签名不保留。", 11, muted))
        body.addView(label("关于与开源许可", 12, green).apply {
            setPadding(0, dp(14), 0, dp(8)); setOnClickListener { showAbout() }
        })
        selection.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSelection(State.value) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
    }
    override fun onStart() { super.onStart(); bindWorker() }
    override fun onStop() {
        if (bound) { unbindService(connection); bound = false; worker = null }
        super.onStop()
    }
    override fun onResume() { super.onResume(); lastState = null; handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }

    private fun render() {
        val s = State.value
        if (s == lastState && !s.busy) return
        lastState = s
        fileTitle.text = s.name.ifBlank { "你的 PDF，留在你的手机里" }
        fileMeta.text = if (s.source.isEmpty()) "本地处理 · 不上传 · 保留原文件" else "${if (s.pages > 0) "${s.pages} 页  ·  " else ""}${formatBytes(s.bytes)}"
        status.text = s.stage + if (s.busy && s.started > 0) " · 已用 ${(SystemClock.elapsedRealtime() - s.started) / 1000} 秒" else ""
        status.setTextColor(if (s.error) Color.rgb(171, 64, 45) else muted)
        pick.isEnabled = !s.busy; read.isEnabled = !s.busy && s.source.isNotEmpty()
        selection.isEnabled = !s.busy && s.pages > 0; password.isEnabled = !s.busy; repair.isEnabled = !s.busy
        quickButtons.forEach { it.isEnabled = !s.busy && s.pages > 0 }
        progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = s.percent < 0
        if (s.percent >= 0) progress.progress = s.percent
        cancel.visibility = if (s.busy) View.VISIBLE else View.GONE
        cancel.isEnabled = s.busy
        resultPanel.visibility = if (s.result.isNotBlank()) View.VISIBLE else View.GONE
        save.isEnabled = !s.busy; open.isEnabled = !s.busy; share.isEnabled = !s.busy
        updateSelection(s)
    }
    private fun updateSelection(s: UiState) {
        if (!::selection.isInitialized || !::extract.isInitialized) return
        val chosen = runCatching { PageSelection.parse(selection.text.toString(), s.pages) }
        extract.isEnabled = !s.busy && chosen.isSuccess
        extract.alpha = if (extract.isEnabled) 1f else 0.5f
        selectionHint.text = when {
            s.pages == 0 -> "选择文件后，可检查页码范围"
            selection.text.isBlank() -> "可选范围：1～${s.pages} 页"
            chosen.isSuccess -> "已选择 ${chosen.getOrThrow().size} / ${s.pages} 页 · 按原顺序输出"
            else -> chosen.exceptionOrNull()?.message ?: "请检查页码"
        }
        selectionHint.setTextColor(if (s.pages > 0 && selection.text.isNotBlank() && chosen.isFailure) Color.rgb(171, 64, 45) else green)
    }
    private fun chooseFile() {
        val preferences = getSharedPreferences("ui", 0)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notificationAsked", false)) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 99)
            return
        }
        openPicker()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99) openPicker()
    }
    private fun openPicker() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 10)
        }.onFailure { toast("未找到系统文件选择器") }
    }
    private fun saveResult() {
        val name = State.value.name.substringBeforeLast('.', State.value.name).take(80).ifBlank { "PDF" }
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "${name}_提取.pdf")
            }, 11)
        }.onFailure { toast("未找到系统保存位置选择器") }
    }
    @Deprecated("Uses framework result API to keep app dependencies small")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 10) {
            val old = State.value.source
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (old.isNotBlank() && old != uri.toString()) runCatching {
                contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selection.setText(""); password.setText("")
            start(PdfService.ANALYZE, "uri" to uri.toString())
        } else if (requestCode == 11) start(PdfService.EXPORT, "uri" to uri.toString())
    }
    private fun start(action: String, vararg extras: Pair<String, String>) {
        if (State.value.busy) return
        val intent = Intent(this, PdfService::class.java).setAction(action).putExtra("password", password.text.toString()).putExtra("repair", repair.isChecked)
        extras.forEach { intent.putExtra(it.first, it.second) }
        try {
            startForegroundService(intent)
            State.receive(State.bundle().apply { putBoolean("busy", true); putString("stage", "准备任务…") })
            render()
        } catch (e: Exception) { toast("无法开始任务，请返回应用后重试") }
    }
    private fun openResult(sharing: Boolean) {
        val file = File(State.value.result)
        if (!file.isFile) { toast("结果已不存在，请重新提取"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = if (sharing) Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri) }
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("PDF", uri)
        try { startActivity(if (sharing) Intent.createChooser(intent, "分享 PDF") else intent) }
        catch (e: ActivityNotFoundException) { toast("请先安装支持查看 PDF 的应用") }
    }
    private fun showAbout() {
        val text = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("轻页 PDF · QPDF 2.0.0")
            .setMessage("离线页面提取工具。无需账号，不申请联网或全部文件访问权限。\n\n$text")
            .setPositiveButton("关闭", null).show()
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; minimumHeight = dp(48); minHeight = dp(48)
        setPadding(dp(10), dp(5), dp(10), dp(5)); elevation = 0f
        setTextColor(if (primary) Color.WHITE else green)
        background = rounded(if (primary) green else Color.rgb(233, 240, 232), 12)
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun spacing(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(19), dp(18), dp(19))
        background = rounded(Color.WHITE, 18, Color.rgb(226, 232, 224))
        parent.addView(this, spacing(0, 16))
    }
}
