package util

import CommonPostInfo
import CommonDownloadInfo
import CommonFileInfo
import IdmDownloadInfo
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

abstract class BasicPlatformCore<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo>: PlatformCore<T,K,E> {
    override suspend fun preHook() = Unit
    override suspend fun afterHook() = Unit
    override fun extractImgNum(downLoadInfo: K) = 0
    override fun extractFileNum(downLoadInfo: K) = 0
    override suspend fun extractDownload(saveFile: File, downLoadInfo: K, sendFiles: ConcurrentLinkedDeque<IdmDownloadInfo>, senderP: SendChannel<Boolean>) = Unit

    //去除标题中含有的windows不允许的文件名字符
    private val invalidChars = listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|','.').map { it.toString() }
    private val reg = Regex("[^\\u0000-\\uFFFF]")
    protected fun String.validFileName(): String {
        var s = this.trim()
        invalidChars.forEach {
            s = s.replace(it, "")
            //s = reg.replace(s,"")
        }
        return s
    }
}