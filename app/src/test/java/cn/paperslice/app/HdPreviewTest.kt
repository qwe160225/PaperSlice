package cn.paperslice.app

import org.junit.Assert.*
import org.junit.Test

class HdPreviewTest {
    @Test fun hdIsRerenderedAtLargerResolutionWithFiniteBudget() {
        val thumb=PreviewNavigation.bitmapSize(595,842)
        val hd=PreviewNavigation.hdBitmapSize(595,842)
        assertTrue(hd.first>thumb.first*4);assertTrue(hd.second>thumb.second*4)
        for((w,h) in listOf(595 to 842,842 to 595,100000 to 1,1 to 100000,Int.MAX_VALUE to Int.MAX_VALUE)) {
            val (bw,bh)=PreviewNavigation.hdBitmapSize(w,h)
            assertTrue(bw in 1..3072 && bh in 1..3072)
            assertTrue(bw.toLong()*bh<=4_000_000L)
        }
    }
    @Test fun onlyCurrentAndImmediateNeighboursAreRequested() {
        assertEquals(listOf(0,1),PreviewNavigation.hdPages(0,500))
        assertEquals(listOf(250,251,249),PreviewNavigation.hdPages(250,500))
        assertEquals(listOf(499,498),PreviewNavigation.hdPages(499,500))
        assertEquals(listOf(0),PreviewNavigation.hdPages(0,1))
    }
    @Test fun thumbnailDoesNotSatisfyHdAndNewPageReplacesOldNeighbours() {
        val q=RenderQueue()
        val thumb=RenderRequest(249,PageQuality.THUMBNAIL)
        val hd=RenderRequest(249,PageQuality.HD)
        q.replace(listOf(thumb));assertEquals(thumb,q.next())
        q.replace(listOf(hd,RenderRequest(250,PageQuality.HD)));q.complete(thumb)
        assertEquals(hd,q.next())
        val latest=RenderRequest(399,PageQuality.HD)
        q.replace(listOf(latest));q.complete(hd)
        assertEquals(latest,q.next());q.complete(latest);assertNull(q.next())
    }
}
