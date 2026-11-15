package cn.paperslice.app

/** One-based user page numbers; independent of Android for boundary tests. */
object PreviewNavigation {
    fun pageNumber(input: String, total: Int): Int {
        require(total > 0) { "文件没有可预览的页面" }
        val raw = input.trim()
        require(raw.isNotEmpty()) { "请输入要跳转的页码" }
        val digits = raw.map {
            val digit = Character.digit(it, 10)
            require(digit >= 0) { "请输入单个页码，例如 120" }
            ('0'.code + digit).toChar()
        }.joinToString("")
        val number = digits.toIntOrNull()
        require(number != null && number in 1..total) { "页码必须在 1～$total 之间" }
        return number
    }

    fun visiblePages(first: Int, count: Int, total: Int, focused: Int): List<Int> {
        if (total <= 0 || count <= 0) return emptyList()
        val start = first.coerceIn(0, total - 1)
        val end = (start.toLong() + count.coerceAtMost(16)).coerceAtMost(total.toLong()).toInt()
        val result = (start until end).toMutableList()
        if (focused in start until end) { result.remove(focused); result.add(0, focused) }
        return result
    }

    fun bitmapSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "页面尺寸无效" }
        val scale = minOf(320.0 / width, 448.0 / height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    fun hdBitmapSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "页面尺寸无效" }
        // Render anew from the PDF at up to 3072 px long edge / 4 million pixels.
        val scale = minOf(3072.0 / maxOf(width, height), kotlin.math.sqrt(4_000_000.0 / width / height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    fun hdPages(current: Int, total: Int): List<Int> {
        if (current !in 0 until total) return emptyList()
        return listOf(current, current + 1, current - 1).filter { it in 0 until total }
    }
}
