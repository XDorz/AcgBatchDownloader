package util

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo
import IdmDownloadInfo
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max
import kotlin.math.min

abstract class BasicPlatformCore<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo> :
    PlatformCore<T, K, E> {
    override suspend fun preHook() = Unit
    override suspend fun afterHook() = Unit
    override fun extractImgNum(downLoadInfo: K) = 0
    override fun extractFileNum(downLoadInfo: K) = 0
    override suspend fun extractDownload(
        saveFile: File,
        downLoadInfo: K,
        sendFiles: ConcurrentLinkedDeque<IdmDownloadInfo>,
        senderP: SendChannel<Boolean>
    ) = Unit

    //去除标题中含有的windows不允许的文件名字符
    private val invalidChars = listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|').map { it.toString() }
    private val reg = Regex("[^\\u0000-\\uFFFF]")
    private val fileLayerReg = Regex("(\\.+)(([\\\\/]+)|$)")

    protected fun String.validFileName(): String {
        var s = this.trim()
        invalidChars.forEach {
            s = s.replace(it, "")
            //s = reg.replace(s,"")
        }
        //过滤掉类似../和./这种代表上一层文件和当前文件的命名
        s = fileLayerReg.replace(s) { result ->
            result.value.replace(".", "")
        }
        return s
    }

    //简单解析一个起止区间，注意maximum是范围允许的最大值，而不是作品的数量，一般来说 maximum = postsNum - 1, 默认minimum为0
    protected fun postRange(start: Int?, end: Int?, reversed: Boolean, maximum: Int): IntRange {
        var s = start?.let {
            if (it < 0) maximum + it else it
        } ?: maximum
        var e = end?.let {
            if (it < 0) maximum + it else it
        } ?: maximum

        if (!reversed) {
            s = maximum - s
            e = maximum - e
        }

        val before = min(s, e).let {
            min(max(0, it), maximum)
        }
        val after = max(s, e).let {
            min(max(0, it), maximum)
        }
        return (before..after)
    }
}