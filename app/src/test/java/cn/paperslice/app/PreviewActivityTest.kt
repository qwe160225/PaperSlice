package cn.paperslice.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UiTestPreviewActivity : PreviewActivity() {
    val rendered=CopyOnWriteArrayList<RenderRequest>()
    val hdRequested=CountDownLatch(1)
    override fun createRenderer(): PageRenderer = object : PageRenderer {
        override fun open() {}
        override fun render(request: RenderRequest): Bitmap {
            rendered.add(request)
            if(request.quality==PageQuality.HD) hdRequested.countDown()
            return if(request.quality==PageQuality.HD) Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888)
                else Bitmap.createBitmap(8,12,Bitmap.Config.ARGB_8888)
        }
        override fun close() {}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewActivityTest {
    private lateinit var controller: ActivityController<UiTestPreviewActivity>
    private lateinit var activity: UiTestPreviewActivity
    private val grid get()=activity.findViewById<RecyclerView>(R.id.preview_grid)
    private val layout get()=grid.layoutManager as GridLayoutManager
    private val input get()=activity.findViewById<EditText>(R.id.preview_page)
    private val pager get()=activity.findViewById<ViewPager2>(R.id.preview_hd)

    @Before fun setup() {
        val intent=Intent().putExtra("uri","content://test/large.pdf").putExtra("pages",500).putExtra("name","500 pages")
        controller=Robolectric.buildActivity(UiTestPreviewActivity::class.java,intent).setup().visible()
        activity=controller.get(); settle()
    }
    @After fun close() {controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle()}
    private fun settle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        }
    }
    private fun search(page: String) {input.requestFocus();input.setText(page);settle()}
    private fun visible(index: Int) = index in layout.findFirstVisibleItemPosition()..layout.findLastVisibleItemPosition()
    private fun manuallyScrollAway() {
        val t=SystemClock.uptimeMillis()
        for ((action,offset,y) in listOf(Triple(MotionEvent.ACTION_DOWN,0L,grid.height*0.8f),Triple(MotionEvent.ACTION_MOVE,20L,grid.height*0.2f),Triple(MotionEvent.ACTION_CANCEL,40L,grid.height*0.2f))) {
            val event=MotionEvent.obtain(t,t+offset,action,grid.width/2f,y,0)
            grid.dispatchTouchEvent(event);event.recycle()
        }
        layout.scrollToPositionWithOffset(0,0);settle()
    }
    private fun doubleTap(view: View) {
        val start=SystemClock.uptimeMillis()
        fun event(action: Int,offset: Long) {
            val e=MotionEvent.obtain(start,start+offset,action,view.width/2f,view.height/2f,0)
            view.dispatchTouchEvent(e);e.recycle()
        }
        event(MotionEvent.ACTION_DOWN,0);event(MotionEvent.ACTION_UP,40)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals("A single tap must remain a thumbnail preview",View.GONE,pager.visibility)
        event(MotionEvent.ACTION_DOWN,140);event(MotionEvent.ACTION_UP,180)
        settle()
    }

    @Test fun automaticSearchActuallyMovesViewportAndHighlightsTarget() {
        search("400")
        assertTrue("Target page must be visible, not just labelled",visible(399))
        assertTrue(grid.findViewHolderForAdapterPosition(399)!!.itemView.isSelected)
        assertEquals(View.GONE,pager.visibility)
    }
    @Test fun repeatedSamePageSearchReturnsAfterManualScrollAway() {
        search("400");assertTrue(visible(399))
        manuallyScrollAway();assertFalse(visible(399))
        input.setText("");input.setText("400");settle()
        assertTrue("The highlighted-page equality guard must not suppress scrolling",visible(399))
        manuallyScrollAway();assertFalse(visible(399))
        activity.findViewById<Button>(R.id.preview_jump).performClick();settle()
        assertTrue(visible(399))
    }
    @Test fun latestSearchWinsAcrossLayoutChangeAndLastPage() {
        input.requestFocus();input.setText("120");input.setText("499");input.setText("500")
        // Simulate an IME inset while the debounced search is pending. Let the real
        // ViewRoot measure/layout; manually forcing a different screen size gives
        // contradictory bounds when Robolectric performs its next traversal.
        val root=(activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        root.setPadding(root.paddingLeft,root.paddingTop,root.paddingRight,80)
        settle()
        assertTrue(visible(499))
        root.setPadding(root.paddingLeft,root.paddingTop,root.paddingRight,0)
        settle();assertTrue(visible(499))
        search("1");assertTrue(visible(0))
        search("501");assertNotNull(input.error);assertTrue(visible(0))
    }
    @Test fun doubleTapOpensCorrectHdPageAndBackRestoresPreview() {
        search("400")
        doubleTap(grid.findViewHolderForAdapterPosition(399)!!.itemView)
        assertEquals(View.VISIBLE,pager.visibility);assertEquals(399,pager.currentItem)
        assertEquals(View.GONE,grid.visibility)
        assertTrue("HD work must reach the background renderer",activity.hdRequested.await(2,TimeUnit.SECONDS))
        assertTrue(activity.rendered.any {it==RenderRequest(399,PageQuality.HD)})
        activity.onBackPressedDispatcher.onBackPressed();settle()
        assertEquals(View.GONE,pager.visibility);assertTrue(visible(399))
    }
    @Test fun horizontalDragChangesNeighbourAndBoundariesAreSafe() {
        search("250");doubleTap(grid.findViewHolderForAdapterPosition(249)!!.itemView)
        assertTrue(pager.beginFakeDrag());pager.fakeDragBy(-pager.width*0.8f);pager.endFakeDrag();settle()
        assertEquals(250,pager.currentItem)
        assertTrue(pager.beginFakeDrag());pager.fakeDragBy(pager.width*0.8f);pager.endFakeDrag();settle()
        assertEquals(249,pager.currentItem)
        pager.setCurrentItem(0,false);settle()
        assertFalse(activity.findViewById<Button>(R.id.preview_previous).isEnabled)
        pager.setCurrentItem(499,false);settle()
        assertFalse(activity.findViewById<Button>(R.id.preview_next).isEnabled)
        activity.findViewById<Button>(R.id.preview_back).performClick();settle();assertTrue(visible(499))
    }
}
