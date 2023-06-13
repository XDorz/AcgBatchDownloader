package util

import CommonPostInfo
import CommonDownloadInfo
import CommonFileInfo
import IdmDownloadInfo
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

//该接口为CommonArticleDownloader 通用作品下载 服务
interface PlatformCore<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo> {
    //网络请求发生器，发送带cookie等信息的对应平台请求
    val requestGenerator: RequestUtil
    //下载开始前执行的任务
    suspend fun preHook()
    //下载完成后执行的任务
    suspend fun afterHook()
    //获取该作者所有的作品
    suspend fun fetchPosts(key: String): List<T>
    //获取一个平台作品的下载链接
    suspend fun catchPostDownloadInfo(postInfo: T): K
    //获取下载链接中额外的图片数 ----用于数据统计与显示
    fun extractImgNum(downLoadInfo: K): Int
    //获取下载链接中额外的文件数 ----用于数据统计与显示
    fun extractFileNum(downLoadInfo: K): Int

    /**
     * 平台对应的作品的额外信息的下载
     * 该函数被用于CommonArticleDownloader，用于在通用下载的基础上进行平台对应下载
     *
     * @param saveFile              文件保存位置
     * @param downLoadInfo          平台对应文件下载链接信息
     * @param sendFiles             将用于发送给idm进行下载的信息包
     * @param senderP               在该通道内发送任何数据都将使实时信息打印中的已下载图片数+1
     */
    suspend fun extractDownload(
        saveFile: File,
        downLoadInfo: K,
        sendFiles: ConcurrentLinkedDeque<IdmDownloadInfo>,
        senderP: SendChannel<Boolean>
    )
}