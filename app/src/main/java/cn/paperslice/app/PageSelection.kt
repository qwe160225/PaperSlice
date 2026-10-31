package cn.paperslice.app

/** One-based positions, sorted and deduplicated. Bounded by the actual PDF page count. */
object PageSelection {
    fun parse(input: String, total: Int): IntArray {
        require(total > 0) { "文件没有可提取的页面" }
        require(input.length <= 20_000) { "页码输入过长，请使用连续页段" }
        val text = input.trim().replace('，', ',').replace('、', ',')
            .replace('；', ',').replace(';', ',').replace('－', '-').replace('–', '-').replace('—', '-')
        require(text.isNotBlank()) { "请输入页码，例如 1,5,10-20" }
        val selected = java.util.BitSet(total)
        if (text == "全部") selected.set(0, total)
        else if (text == "奇数页" || text == "偶数页") {
            var page = if (text == "奇数页") 1 else 2
            while (page <= total) { selected.set(page - 1); page += 2 }
        } else text.split(',').forEach { raw ->
            val token = raw.trim()
            val match = Regex("(\\d+)\\s*(?:-\\s*(\\d+))?").matchEntire(token)
                ?: throw IllegalArgumentException("“$token” 格式不正确，请用逗号分隔页码")
            val start = match.groupValues[1].toIntOrNull() ?: 0
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: if (match.groupValues[2].isEmpty()) start else 0
            require(start in 1..total && end in 1..total) { "页码必须在 1～$total 之间" }
            require(start <= end) { "“$token” 起始页不能大于结束页" }
            selected.set(start - 1, end)
        }
        require(!selected.isEmpty) { "没有选中任何页面" }
        val result = IntArray(selected.cardinality())
        var index = 0
        var bit = selected.nextSetBit(0)
        while (bit >= 0) { result[index++] = bit + 1; bit = selected.nextSetBit(bit + 1) }
        return result
    }
}
