package cn.paperslice.app

import org.junit.Assert.*
import org.junit.Test

class PreviewNavigationTest {
    private fun thumb(page: Int)=RenderRequest(page,PageQuality.THUMBNAIL)
    @Test fun jumpToFirstLastAndFullWidthNumber() {
        assertEquals(1, PreviewNavigation.pageNumber("1", 500))
        assertEquals(500, PreviewNavigation.pageNumber(" 500 ", 500))
        assertEquals(120, PreviewNavigation.pageNumber("１２０", 500))
    }
    @Test fun invalidNumbersCannotBecomeScrollPositions() {
        listOf("", "0", "501", "-1", "1.5", "1-5", "1,5", "abc", "99999999999999999").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { PreviewNavigation.pageNumber(it, 500) }
        }
        assertThrows(IllegalArgumentException::class.java) { PreviewNavigation.pageNumber("1", 0) }
    }
    @Test fun viewportNearEndAndFocusFirst() {
        assertEquals(listOf(499, 496, 497, 498), PreviewNavigation.visiblePages(496, 12, 500, 499))
        assertEquals(listOf(0,1,2,3), PreviewNavigation.visiblePages(0,4,500,120))
        assertEquals(listOf(0), PreviewNavigation.visiblePages(0,2,1,0))
        assertTrue(PreviewNavigation.visiblePages(0,4,0,0).isEmpty())
        assertTrue(PreviewNavigation.visiblePages(0,500,500,0).size <= 16)
    }
    @Test fun jumpDiscardsEarlierPendingPagesAndKeepsCurrentRender() {
        val queue=RenderQueue()
        queue.replace(listOf(0,1,2,3).map(::thumb))
        assertEquals(thumb(0),queue.next())
        queue.replace(listOf(399,398,397,0,399).map(::thumb))
        queue.complete(thumb(0))
        assertEquals(thumb(399),queue.next());queue.complete(thumb(399))
        assertEquals(thumb(398),queue.next());queue.complete(thumb(398))
        assertEquals(thumb(397),queue.next());queue.complete(thumb(397))
        assertNull(queue.next())
    }
    @Test fun pauseClearsPendingAndRequestsRemainBounded() {
        val queue=RenderQueue()
        queue.replace((0..499).map(::thumb))
        var count=0
        while(true) { val p=queue.next()?:break;count++;queue.complete(p) }
        assertEquals(16,count)
        queue.replace(listOf(20,21).map(::thumb));assertEquals(thumb(20),queue.next())
        queue.replace(emptyList());queue.complete(thumb(20));assertNull(queue.next())
    }
    @Test fun bitmapAllocationIsBoundedForEveryAspectRatio() {
        for((w,h) in listOf(595 to 842,842 to 595,100000 to 1,1 to 100000,20000 to 20000)) {
            val (bw,bh)=PreviewNavigation.bitmapSize(w,h)
            assertTrue(bw in 1..320);assertTrue(bh in 1..448)
            assertTrue(bw.toLong()*bh*4<=320L*448*4)
        }
        assertThrows(IllegalArgumentException::class.java) {PreviewNavigation.bitmapSize(0,100)}
    }
}
