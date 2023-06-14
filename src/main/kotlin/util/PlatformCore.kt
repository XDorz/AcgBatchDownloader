package util

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo
import IdmDownloadInfo
import kotlinx.coroutines.channels.SendChannel
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

//该接口为CommonArticleDownloader 通用作品下载 服务
interface PlatformCore<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo> {
    //网络请求生成器，发送带cookie等信息的对应平台请求
    val requestGenerator: RequestUtil

    //下载开始前执行的任务
    suspend fun preHook()

    //下载完成后执行的任务
    suspend fun afterHook()

    /**
     * 获取该作者所有的作品
     *
     * @param key           作者关键词，不同的平台关键词获取方法不同
     * @param start         起始index，其影响和平台有关
     * @param end           获取结束index，其影响和平台有关
     * @param reversed      获取指定获取顺序是否为倒叙，默认为true，即我们实际浏览的顺序，由新发布到最早发布
     * @return              作者作品的所有信息
     *
     * 虽然start和end的解释与平台有关，但是我们仍然能定义些通用解释，所有core都会遵守以下约定：
     * 当start/end为null时代表范围内最后一个(其定义与平台有关，可能为作品，可能为页数)
     * 当start/end为负数时代表从最后的范围往前进多少，如-1是倒数第二个(倒数第一个为null)，为0则是第一项
     *  reversed的值并不影响filter中的index的顺序
     *  当start的计算值在end的计算值之后时，会返回他们之间的交集
     */
    suspend fun fetchPosts(key: String, start: Int? = 0, end: Int? = null, reversed: Boolean = true): List<T>

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