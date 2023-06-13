package util

import java.text.DecimalFormat

class ProgressBar(
    /**
     * 进度条长度
     */
    private val barLen: Int = 50,
    /**
     * 总数
     */
    private val total:Int = 100,
    /**
     * 用于进度条显示的字符
     */
    private var showChar: Char = '#'
) {
    private val formater = DecimalFormat("#.##%")

    /**
     * 显示进度条
     */
    fun show(value: Int,extra: String) {
        print('\r')

        // 比例
        val rate = (value * 1.0 / total).toFloat()
        // 比例*进度条总长度=当前长度
        draw(barLen, rate, extra)
        if (value.toLong() >= total) {
            afterComplete()
        }
    }

    /**
     * 画指定长度个showChar
     */
    private fun draw(barLen: Int, rate: Float, extra: String) {
        val len = (rate * barLen).toInt()
        print("Progress: ")
        for (i in 0 until len) {
            print(showChar)
        }
        for (i in 0 until barLen - len) {
            print(" ")
        }
        print(" |${format(rate)}   $extra")
    }

    /**
     * 完成后换行
     */
    private fun afterComplete() {
        print('\n')
    }

    private fun format(num: Float): String {
        return formater.format(num.toDouble())
    }
}

class ScrollProgress(private val totalLength: Int = 30, private val snakeLength: Int = 10) {
    private var location = snakeLength
    private var back = false

    fun next(): String {
        if (location >= totalLength) {
            location = 0
            back = !back
        }
        val sb = buildString {
            if (back) append("』") else append("『")
            if (location <= snakeLength) {
                repeat(location) {
                    append("=")
                }
                if (back) append("<") else append(">")
                if (snakeLength - (2 * location + 1) > 0) {
                    repeat(snakeLength - (2 * location + 1)) {
                        append("=")
                    }
                }
            } else {
                repeat(location - snakeLength) {
                    append(" ")
                }
                repeat(snakeLength) {
                    append("=")
                }
                if (back) append("<") else append(">")
            }
            repeat(totalLength + 1 - length) {
                append(" ")
            }
            if (back) append("『") else append("』")
        }
        location++
        return if (back) sb.reversed() else sb
    }
}