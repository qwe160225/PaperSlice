package cn.paperslice.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Exercises the same Kotlin JNI adapter and C++ core shipped in the Android library. */
class PdfEngineTest {
    private fun fixture(name: String): File {
        val file = Files.createTempFile("paperslice-", ".pdf").toFile()
        javaClass.getResourceAsStream("/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.deleteOnExit()
        return file
    }
    @Test fun nativeExtractionAndProgress() {
        val source = fixture("small.pdf")
        val result = File(source.parentFile, source.name + ".out.pdf")
        try {
            assertEquals(8, PdfEngine.count(source, "", false))
            val percents = mutableListOf<Int>()
            PdfEngine.extract(source, "", intArrayOf(1,3,4,5,8),8,result,false,CancelToken()) { percents.add(it) }
            assertEquals(5, PdfEngine.count(result, "", false))
            assertTrue(percents.isNotEmpty())
            assertEquals(100, percents.last())
            assertTrue(percents.zipWithNext().all { (a,b) -> a <= b })
            assertEquals(8, PdfEngine.count(source, "", false))
        } finally { source.delete(); result.delete() }
    }
    @Test fun unicodePasswordAndNativeException() {
        val source = fixture("unicode.pdf")
        val result = File(source.parentFile, source.name+".out.pdf")
        try {
            assertEquals(8, PdfEngine.count(source, "密码🔑", false))
            assertThrows(java.io.IOException::class.java) { PdfEngine.count(source,"错误🔒",false) }
            PdfEngine.extract(source,"密码🔑",intArrayOf(2,7),8,result,false,CancelToken()) {}
            assertEquals(2,PdfEngine.count(result,"",false))
        } finally { source.delete();result.delete() }
    }
    @Test fun callbackExceptionCrossesJniSafely() {
        val source=fixture("small.pdf")
        val result=File(source.parentFile,source.name+".out.pdf")
        try {
            assertThrows(TaskCancelled::class.java) {
                PdfEngine.extract(source,"",intArrayOf(1,2),8,result,false,CancelToken()) { throw TaskCancelled() }
            }
            assertEquals(8,PdfEngine.count(source,"",false))
        } finally {source.delete();result.delete()}
    }
    @Test fun rejectsChangedSourceAndUnsortedPages() {
        val source=fixture("small.pdf")
        val result=File(source.parentFile,source.name+".out.pdf")
        try {
            assertThrows(java.io.IOException::class.java) {
                PdfEngine.extract(source,"",intArrayOf(1),9,result,false,CancelToken()) {}
            }
            assertThrows(IllegalArgumentException::class.java) {
                PdfEngine.extract(source,"",intArrayOf(2,1),8,result,false,CancelToken()) {}
            }
        } finally {source.delete();result.delete()}
    }
}
