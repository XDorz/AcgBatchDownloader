package util

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo


/**
 * 平台工具集
 *
 * 该接口是让用户可以手动解析的接口
 */
interface PlatformUtil<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo> {

    fun analysis(url: String): K
    fun analysis(postInfo: T): K
}