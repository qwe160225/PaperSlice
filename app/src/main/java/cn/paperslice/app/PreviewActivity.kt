package cn.paperslice.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.*
import android.util.LruCache
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI in :preview; one background renderer, with independent thumbnail and HD caches. */
open class PreviewActivity : ComponentActivity() {
    private val green = Color.rgb(33,106,88)
    private val ink = Color.rgb(29,47,42)
    private val muted = Color.rgb(100,116,107)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val queue = RenderQueue()
    private val drainLock = Any()
    private var draining = false
    private var renderer: PageRenderer? = null
    private var ready = false
    private var foreground = false
    private var total = 0
    private var focused = 0
    private var hdMode = false
    private var changingMode = false
    private var silentInput = false
    private var pendingJump: Int? = null
    private var password = ""
    private lateinit var grid: RecyclerView
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var pager: ViewPager2
    private lateinit var pageInput: EditText
    private lateinit var search: LinearLayout
    private lateinit var title: TextView
    private lateinit var position: TextView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var hdControls: LinearLayout
    private lateinit var previous: Button
    private lateinit var next: Button
    private val failures = mutableMapOf<RenderRequest,String>()
    private val thumbCache = bitmapCache(12 * 1024 * 1024)
    private val hdCache = bitmapCache(48 * 1024 * 1024)
    private val thumbnailAdapter = ThumbnailAdapter()
    private val hdAdapter = HdAdapter()
    private val request = Runnable { requestVisible() }
    private val autoJump = Runnable {
        if (::pageInput.isInitialized && foreground && !hdMode) {
            if (pageInput.text.isBlank()) pageInput.error = null else jump(false)
        }
    }

    private fun bitmapCache(bytes: Int) = object : LruCache<Int,Bitmap>(bytes) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        // Views may still hold an evicted image; never manually recycle displayed bitmaps.
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        total = intent.getIntExtra("pages",0)
        if (total <= 0 || intent.getStringExtra("uri").isNullOrBlank()) { finish(); return }
        password = intent.getStringExtra("password") ?: ""
        intent.removeExtra("password")
        focused = (state?.getInt("focused") ?: 0).coerceIn(0,total-1)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        grid.post {
            if (state?.getBoolean("hd") == true) openHd(focused)
            else navigateTo(focused, false)
        }
        renderer = createRenderer()
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            try {
                renderer!!.open()
                main.post {
                    if (!closed.get()) {
                        ready = true
                        spinner.visibility = View.GONE
                        updateStatus()
                        requestVisible()
                    }
                }
            } catch (e: Exception) { showOpenError(e) }
            catch (_: OutOfMemoryError) { showOpenError(IOException("此文件预览所需内存过大，请返回继续提取")) }
        }
    }

    /** Test subclasses can supply a deterministic renderer without changing production routing. */
    protected open fun createRenderer(): PageRenderer = AndroidPageRenderer(
        this, Uri.parse(intent.getStringExtra("uri")), total, password,
        intent.getBooleanExtra("repair",false), intent.getLongExtra("bytes",0), closed::get)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245,247,242))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()) else null
                v.setPadding(dp(12),bars?.top ?: insets.systemWindowInsetTop,dp(12),bars?.bottom ?: insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { id=R.id.preview_back; text="返回"; setOnClickListener { back() } },LinearLayout.LayoutParams(dp(74),dp(50)))
        title = label("页面预览",21,ink)
        header.addView(title,LinearLayout.LayoutParams(0,dp(50),1f))
        root.addView(header)
        search = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        pageInput = EditText(this).apply {
            id=R.id.preview_page; hint="输入页码 1～$total"; isSingleLine=true; textSize=16f
            inputType=InputType.TYPE_CLASS_NUMBER; imeOptions=EditorInfo.IME_ACTION_GO
            filters=arrayOf(InputFilter.LengthFilter(10)); setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, event ->
                if (action==EditorInfo.IME_ACTION_GO || (event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_UP)) { jump(true); true } else false
            }
        }
        pageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!silentInput) { main.removeCallbacks(autoJump); main.postDelayed(autoJump,600) }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        search.addView(pageInput,LinearLayout.LayoutParams(0,dp(52),1f))
        search.addView(Button(this).apply { id=R.id.preview_jump; text="跳转"; setTextColor(green); setOnClickListener { jump(true) } },LinearLayout.LayoutParams(dp(82),dp(52)))
        root.addView(search)
        position=label("共 $total 页",13,green).apply { id=R.id.preview_position }
        status=label("正在打开预览…可先搜索页码",12,muted)
        root.addView(position); root.addView(status)
        spinner=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true }
        root.addView(spinner,LinearLayout.LayoutParams(-1,dp(3)))
        val content=FrameLayout(this)
        gridLayout=GridLayoutManager(this,2)
        grid=RecyclerView(this).apply {
            id=R.id.preview_grid; layoutManager=gridLayout; adapter=thumbnailAdapter
            itemAnimator=null; setHasFixedSize(true); clipToPadding=false; setPadding(0,dp(8),0,dp(12))
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state==RecyclerView.SCROLL_STATE_DRAGGING) pendingJump=null
            }
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) { scheduleVisible() }
        })
        grid.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            // Keep the search anchor through subsequent keyboard/inset layouts.
            // Seeing the target once does not mean the window has finished resizing.
            // A deliberate drag or a new selection releases the anchor.
            val target=pendingJump
            if (target!=null && !targetIsVisible(target)) grid.post { applyPendingJump() }
            scheduleVisible()
        }
        content.addView(grid,FrameLayout.LayoutParams(-1,-1))
        pager=ViewPager2(this).apply {
            id=R.id.preview_hd; orientation=ViewPager2.ORIENTATION_HORIZONTAL
            layoutDirection=View.LAYOUT_DIRECTION_LTR; offscreenPageLimit=1; visibility=View.GONE
            setBackgroundColor(Color.rgb(25,29,27))
        }
        (pager.getChildAt(0) as RecyclerView).setItemViewCacheSize(0)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(index: Int) {
                if (!hdMode || changingMode || index!=pager.currentItem) return
                focused=index
                setInputSilently(index+1)
                retainHdNeighbours()
                updateStatus(); requestVisible()
            }
            override fun onPageScrollStateChanged(state: Int) { if (state==ViewPager2.SCROLL_STATE_IDLE) requestVisible() }
        })
        content.addView(pager,FrameLayout.LayoutParams(-1,-1))
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        hdControls=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; visibility=View.GONE }
        previous=Button(this).apply { id=R.id.preview_previous; text="上一页"; setOnClickListener { pager.setCurrentItem((pager.currentItem-1).coerceAtLeast(0),true) } }
        next=Button(this).apply { id=R.id.preview_next; text="下一页"; setOnClickListener { pager.setCurrentItem((pager.currentItem+1).coerceAtMost(total-1),true) } }
        hdControls.addView(previous,LinearLayout.LayoutParams(0,dp(50),1f))
        hdControls.addView(next,LinearLayout.LayoutParams(0,dp(50),1f))
        root.addView(hdControls)
    }

    private fun jump(hideKeyboard: Boolean) {
        main.removeCallbacks(autoJump)
        try {
            val page=PreviewNavigation.pageNumber(pageInput.text.toString(),total)
            pageInput.error=null
            // Always scroll, including repeated searches for an already-highlighted page.
            navigateTo(page-1,hideKeyboard)
        } catch (e: IllegalArgumentException) { pageInput.error=e.message }
    }

    private fun navigateTo(index: Int, hideKeyboard: Boolean) {
        val old=focused
        focused=index.coerceIn(0,total-1)
        refreshThumbnail(old); refreshThumbnail(focused)
        if (hideKeyboard) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pageInput.windowToken,0)
            pageInput.clearFocus()
        }
        pendingJump=focused
        applyPendingJump()
        // The keyboard changes available height; reapply the pending anchor after layout.
        grid.post { applyPendingJump(); requestVisible() }
        updateStatus()
    }
    private fun applyPendingJump() {
        val target=pendingJump ?: return
        if (hdMode || closed.get() || grid.width <= 0 || grid.height <= 0) return
        if (targetIsVisible(target)) return
        grid.stopScroll()
        gridLayout.scrollToPositionWithOffset(target,0)
    }
    private fun targetIsVisible(target: Int): Boolean {
        val first=gridLayout.findFirstVisibleItemPosition()
        val last=gridLayout.findLastVisibleItemPosition()
        return first>=0 && target in first..last
    }

    private fun openHd(index: Int) {
        main.removeCallbacks(autoJump)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pageInput.windowToken,0)
        pageInput.clearFocus()
        focused=index.coerceIn(0,total-1); pendingJump=null; hdMode=true; changingMode=true
        search.visibility=View.GONE; grid.visibility=View.GONE
        pager.visibility=View.VISIBLE; hdControls.visibility=View.VISIBLE
        pager.adapter=hdAdapter
        pager.setCurrentItem(focused,false)
        changingMode=false
        setInputSilently(focused+1)
        retainHdNeighbours(); updateStatus(); requestVisible()
    }
    private fun back() {
        if (!hdMode) { finish(); return }
        focused=pager.currentItem
        hdMode=false
        pager.visibility=View.GONE; pager.adapter=null; hdControls.visibility=View.GONE
        hdCache.evictAll()
        grid.visibility=View.VISIBLE; search.visibility=View.VISIBLE
        setInputSilently(focused+1)
        navigateTo(focused,true)
    }
    private fun setInputSilently(page: Int) {
        silentInput=true; pageInput.setText(page.toString()); silentInput=false
    }
    private fun updateStatus() {
        if (!::title.isInitialized) return
        title.text=if(hdMode) "高清查看" else "页面预览"
        position.text="第 ${focused+1} 页 / 共 $total 页"
        if (::previous.isInitialized) { previous.isEnabled=focused>0; next.isEnabled=focused<total-1 }
        if (!ready) return
        val key=RenderRequest(focused,if(hdMode) PageQuality.HD else PageQuality.THUMBNAIL)
        status.text=failures[key]?.let { "此页加载失败：$it" } ?: if(hdMode) {
            if(hdCache.get(focused)!=null) "高清已就绪 · 左右滑动切换相邻页" else "正在生成高清…左右滑动切换相邻页"
        } else "搜索自动定位 · 双击缩略图进入高清"
    }
    private fun retainHdNeighbours() {
        val keep=PreviewNavigation.hdPages(focused,total)
        hdCache.snapshot().keys.filter { it !in keep }.forEach { hdCache.remove(it) }
    }
    private fun scheduleVisible() { main.removeCallbacks(request); main.postDelayed(request,60) }
    private fun cacheFor(quality: PageQuality) = if(quality==PageQuality.HD) hdCache else thumbCache
    private fun requestVisible() {
        if (!foreground || !ready || closed.get() || !::grid.isInitialized) return
        val wanted=if(hdMode) {
            PreviewNavigation.hdPages(focused,total).map { RenderRequest(it,PageQuality.HD) }
        } else {
            val first=gridLayout.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last=gridLayout.findLastVisibleItemPosition()
            PreviewNavigation.visiblePages(first,(last-first+1).coerceAtLeast(1),total,focused).map { RenderRequest(it,PageQuality.THUMBNAIL) }
        }.filter { cacheFor(it.quality).get(it.page)==null && !failures.containsKey(it) }
        synchronized(drainLock) {
            queue.replace(wanted)
            if(!draining && wanted.isNotEmpty()) { draining=true; executor.execute { drain() } }
        }
    }
    private fun drain() {
        while(!closed.get()) {
            val work=synchronized(drainLock) { queue.next().also { if(it==null) draining=false } } ?: return
            try {
                val bitmap=renderer!!.render(work)
                main.post {
                    if(closed.get() || (work.quality==PageQuality.HD && (!hdMode || work.page !in PreviewNavigation.hdPages(focused,total)))) bitmap.recycle()
                    else {
                        cacheFor(work.quality).put(work.page,bitmap)
                        if(work.quality==PageQuality.HD) refreshHd(work.page) else refreshThumbnail(work.page)
                        if(work.page==focused) updateStatus()
                    }
                }
            } catch(e: Exception) { renderFailed(work,friendlyError(e)) }
            catch(_: OutOfMemoryError) { renderFailed(work,"页面过于复杂，内存不足") }
            finally { queue.complete(work) }
        }
        synchronized(drainLock) { draining=false }
    }
    private fun renderFailed(work: RenderRequest,message: String) = main.post {
        if(!closed.get()) {
            failures[work]=message
            if(work.quality==PageQuality.HD) refreshHd(work.page) else refreshThumbnail(work.page)
            updateStatus()
        }
    }
    private fun refreshThumbnail(index: Int) {
        if(!::grid.isInitialized) return
        (grid.findViewHolderForAdapterPosition(index) as? ThumbnailHolder)?.bind(index)
    }
    private fun refreshHd(index: Int) {
        ((pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(index) as? HdHolder)?.bind(index)
    }

    private inner class ThumbnailHolder(val root: LinearLayout,val image: ImageView,val note: TextView,val number: TextView) : RecyclerView.ViewHolder(root) {
        var page=-1
        init {
            val detector=GestureDetector(this@PreviewActivity,object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent)=true
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if(page>=0) { pendingJump=null; val old=focused; focused=page; setInputSilently(page+1); refreshThumbnail(old); refreshThumbnail(page); updateStatus() }
                    return true
                }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean { if(page>=0) openHd(page); return true }
            })
            root.setOnTouchListener { _,event -> detector.onTouchEvent(event) }
            root.isClickable=true
            root.setOnLongClickListener { if(page>=0) openHd(page); true }
            root.setOnClickListener { if(page>=0) openHd(page) } // accessibility click
        }
        fun bind(index: Int) {
            page=index; root.isSelected=index==focused
            root.background=GradientDrawable().apply {
                setColor(if(index==focused) Color.rgb(223,239,230) else Color.WHITE)
                cornerRadius=dp(10).toFloat(); setStroke(dp(if(index==focused) 2 else 1),if(index==focused) green else Color.rgb(222,229,223))
            }
            number.text="第 ${index+1} 页" + if(index==focused) " · 已定位" else ""
            root.contentDescription="第 ${index+1} 页预览，双击进入高清"
            val bitmap=thumbCache.get(index); image.setImageBitmap(bitmap)
            note.visibility=if(bitmap!=null) View.GONE else View.VISIBLE
            note.text=failures[RenderRequest(index,PageQuality.THUMBNAIL)]?.let { "预览失败\n$it" } ?: "等待预览…"
        }
    }
    private inner class ThumbnailAdapter : RecyclerView.Adapter<ThumbnailHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount()=total
        override fun getItemId(position: Int)=position.toLong()
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): ThumbnailHolder {
            val root=LinearLayout(this@PreviewActivity).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(7),dp(7),dp(7),dp(7))
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply { setMargins(dp(4),dp(4),dp(4),dp(8)) }
            }
            val frame=FrameLayout(this@PreviewActivity)
            val image=ImageView(this@PreviewActivity).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
            val note=label("等待预览…",12,muted).apply { gravity=Gravity.CENTER }
            frame.addView(image,FrameLayout.LayoutParams(-1,-1)); frame.addView(note,FrameLayout.LayoutParams(-1,-1))
            root.addView(frame,LinearLayout.LayoutParams(-1,dp(200)))
            val number=label("",13,ink).apply { gravity=Gravity.CENTER }
            root.addView(number,LinearLayout.LayoutParams(-1,dp(32)))
            return ThumbnailHolder(root,image,note,number)
        }
        override fun onBindViewHolder(holder: ThumbnailHolder,position: Int)=holder.bind(position)
        override fun onViewRecycled(holder: ThumbnailHolder) { holder.page=-1; holder.image.setImageDrawable(null) }
    }
    private inner class HdHolder(val frame: FrameLayout,val image: ImageView,val note: TextView) : RecyclerView.ViewHolder(frame) {
        fun bind(index: Int) {
            val hd=hdCache.get(index)
            image.setImageBitmap(hd ?: thumbCache.get(index))
            note.visibility=if(hd!=null) View.GONE else View.VISIBLE
            note.text=failures[RenderRequest(index,PageQuality.HD)]?.let { "高清生成失败：$it" } ?: "正在生成第 ${index+1} 页高清图…"
            frame.contentDescription="第 ${index+1} 页高清查看"
        }
    }
    private inner class HdAdapter : RecyclerView.Adapter<HdHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount()=total
        override fun getItemId(position: Int)=position.toLong()
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): HdHolder {
            val frame=FrameLayout(this@PreviewActivity).apply { layoutParams=RecyclerView.LayoutParams(-1,-1) }
            val image=ImageView(this@PreviewActivity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
            val note=label("正在生成高清…",13,Color.WHITE).apply { gravity=Gravity.CENTER; setBackgroundColor(0x99000000.toInt()); setPadding(dp(12),dp(10),dp(12),dp(10)) }
            frame.addView(image,FrameLayout.LayoutParams(-1,-1)); frame.addView(note,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
            return HdHolder(frame,image,note)
        }
        override fun onBindViewHolder(holder: HdHolder,position: Int)=holder.bind(position)
        override fun onViewRecycled(holder: HdHolder) { holder.image.setImageDrawable(null) }
    }

    private fun friendlyError(e: Exception): String = if(e is SecurityException || e.message?.contains("password",true)==true) "请返回主界面重新选择文件，并填写正确密码（如有）" else e.message?.take(100) ?: "此页无法预览"
    private fun showOpenError(e: Exception)=main.post { if(!closed.get()) { spinner.visibility=View.GONE; status.text="预览未打开：${friendlyError(e)}" } }
    override fun onResume() { super.onResume(); foreground=true; main.post(request) }
    override fun onPause() { foreground=false; main.removeCallbacks(request); main.removeCallbacks(autoJump); queue.replace(emptyList()); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("focused",focused); outState.putBoolean("hd",hdMode); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        closed.set(true); main.removeCallbacksAndMessages(null); queue.replace(emptyList()); thumbCache.evictAll(); hdCache.evictAll(); password=""
        if(::pager.isInitialized) pager.adapter=null
        executor.execute { runCatching { renderer?.close() } }; executor.shutdown()
        super.onDestroy()
        if(isFinishing && android.app.Application.getProcessName()?.endsWith(":preview")==true) Process.killProcess(Process.myPid())
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Int,color: Int)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); gravity=Gravity.CENTER_VERTICAL }
}
