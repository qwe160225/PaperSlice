package cn.paperslice.app

import org.junit.Assert.*
import org.junit.Test

class PageSelectionTest {
    @Test fun mixedSortedUnique() {
        assertArrayEquals(intArrayOf(3, 8) + (20..30).toList().toIntArray(), PageSelection.parse("20-30,3,8,25", 400))
    }
    @Test fun chineseAndWhitespace() { assertArrayEquals(intArrayOf(1, 5, 6, 7), PageSelection.parse(" 1，5 － 7、6 ", 400)) }
    @Test fun shortcuts() {
        assertArrayEquals(intArrayOf(1, 3, 5), PageSelection.parse("奇数页", 5))
        assertArrayEquals(intArrayOf(2, 4), PageSelection.parse("偶数页", 5))
        assertEquals(400, PageSelection.parse("全部", 400).size)
    }
    @Test fun rejectsMalformedAndOutOfRange() {
        for (input in listOf("", "0", "401", "5-2", "1,", ",1", "1,,2", "1-2-3", "abc", "-1", "9999999999999999999", "1-9999999999999999999")) {
            assertThrows(input, IllegalArgumentException::class.java) { PageSelection.parse(input, 400) }
        }
    }
    @Test fun singlePageAndDuplicates() { assertArrayEquals(intArrayOf(1), PageSelection.parse("1,1-1,1", 1)) }
    @Test fun noEvenPages() { assertThrows(IllegalArgumentException::class.java) { PageSelection.parse("偶数页", 1) } }
}
