package util.core

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo
import IdmDownloadInfo
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.TypeReference
import kotlinx.coroutines.channels.SendChannel
import org.jsoup.internal.StringUtil
import util.BasicPlatformCore
import util.RequestUtil
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

//在fanbox中，start与end的解释为 [作品] 序号
class FanboxCore(private val requestGeneric: RequestUtil) :
    BasicPlatformCore<FanboxPostInfo, FanboxDownloadInfo, FanboxFileInfo>() {
    /**
     * limit可以设置的最大值为300，超过300则返回error
     * 该api在正常浏览情况下发送的参数是 limit=10。若是想要安全可以设置为10
     * 正常浏览情况下该api是通过
     * https://api.fanbox.cc/post.paginateCreator?creatorId=【作者名字】
     * 这个api所获得的json obj里面的名为"body"的json array里的第一个url去直接请求的，该api会列出所有的可请求的作品页面信息的url
     *
     * 故该接口是省略了几个get参数并修改了limit中的数值后的[魔改]请求，想要安全可以替换为原接口
     */
    private val creatorApi = "https://api.fanbox.cc/post.listCreator?creatorId={0}&limit=300".requiredOneParam()
    private val detailApi = "https://api.fanbox.cc/post.info?postId={0}".requiredOneParam()

    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    init {
        if (requestGeneric.referer == null) requestGeneric.referer = "https://www.fanbox.cc/"
        if (requestGeneric.origin == null) requestGeneric.origin = "https://www.fanbox.cc"
    }

    override val requestGenerator: RequestUtil
        get() = requestGeneric

    //fanbox一次请求最多能返回300条作品信息，返回量大而且json解析在内存中，效率非常高，故此处选择直接获取全部作品信息做一个剪裁
    override suspend fun fetchPosts(
        authorName: String,
        start: Int?,
        end: Int?,
        reversed: Boolean
    ): List<FanboxPostInfo> {
        val list = fetchPostsRec(creatorApi(authorName))
        val range = postRange(start, end, reversed, list.size - 1)
        return list.subList(range.first, range.last + 1)
    }

    private tailrec fun fetchPostsRec(
        url: String,
        list: MutableList<FanboxPostInfo> = ArrayList()
    ): List<FanboxPostInfo> {
        val json = requestGeneric.getBody(url)
        json.check("作品")
        var nextUrl: String? = null
        val infos =
            JSONObject.parseObject(json)?.getJSONObject("body")
                ?.also { nextUrl = it.getString("nextUrl") }
                ?.getJSONArray("items")
                ?: throw RuntimeException("无法获取作品合集")
        for (i in 0 until infos.size) {
            val creator = infos.getJSONObject(i)
            val postId = creator.getString("id")
            val title = creator.getString("title")
            val priceRequire = creator.getIntValue("feeRequired")
            val publishedDatetime = creator.getString("publishedDatetime")
            val updatedDatetime = creator.getString("updatedDatetime")
            val updated = publishedDatetime == updatedDatetime
            val adult = creator.getBoolean("hasAdultContent")
            val restricted = creator.getBoolean("isRestricted")

            list.add(
                FanboxPostInfo(
                    postId,
                    title,
                    priceRequire,
                    publishedDatetime.toTime(),
                    updatedDatetime.toTime(),
                    updated,
                    adult,
                    restricted,
                    !restricted
                )
            )
        }

        return if (nextUrl != null) {
            fetchPostsRec(nextUrl!!, list)
        } else {
            list
        }
    }

    override suspend fun catchPostDownloadInfo(postInfo: FanboxPostInfo): FanboxDownloadInfo {
        val json = requestGeneric.getBody(detailApi(postInfo.postId))
        json.check("作品附件")

        var title = ""
        var cover: String? = null
        val fileJson = JSONObject.parseObject(json)?.getJSONObject("body")
            ?.also {
                title = it.getString("title") ?: UUID.randomUUID().toString()
                cover = it.getString("coverImageUrl") ?: null
            }?.getJSONObject("body")
            ?: throw RuntimeException("未能获取作品详情，是否是cookie过期或者赞助等级不足？")

        val imgList = ArrayList<FanboxFileInfo>()
        val fileList = ArrayList<FanboxFileInfo>()
        fileJson.getJSONArray("images")?.let {
            for (i in 0 until it.size) {
                it.getJSONObject(i)?.let { imgInfo ->
                    imgList.add(
                        FanboxFileInfo(
                            "${imgList.size}.${imgInfo.getString("extension")}",
                            imgInfo.getString("originalUrl"),
                            imgInfo.getString("extension")
                        )
                    )
                }
            }
        }

        fileJson.getJSONObject("imageMap")?.let {
            val imgMap = it.toJavaObject(object : TypeReference<LinkedHashMap<String, JSONObject>>() {})
            imgMap.values.forEach { imgInfo ->
                imgList.add(
                    FanboxFileInfo(
                        "${imgList.size}.${imgInfo.getString("extension")}",
                        imgInfo.getString("originalUrl"),
                        imgInfo.getString("extension")
                    )
                )
            }
        }

        fileJson.getJSONArray("files")?.let {
            for (i in 0 until it.size) {
                it.getJSONObject(i)?.let { fileInfo ->
                    fileList.add(
                        FanboxFileInfo(
                            "${fileInfo.getString("name")}.${fileInfo.getString("extension")}".validFileName(),
                            fileInfo.getString("url"),
                            fileInfo.getString("extension")
                        )
                    )
                }
            }
        }

        fileJson.getJSONObject("fileMap")?.let {
            val fileMap = it.toJavaObject(object : TypeReference<LinkedHashMap<String, JSONObject>>() {})
            fileMap.values.forEach { fileInfo ->
                fileList.add(
                    FanboxFileInfo(
                        "${fileInfo.getString("name")}.${fileInfo.getString("extension")}".validFileName(),
                        fileInfo.getString("url"),
                        fileInfo.getString("extension")
                    )
                )
            }
        }

        val hasContent = imgList.isNotEmpty() || fileList.isNotEmpty()
        return FanboxDownloadInfo(title.validFileName(), imgList, fileList, cover, hasContent)
    }

    override fun extractImgNum(downLoadInfo: FanboxDownloadInfo): Int {
        return if (downLoadInfo.coverHref == null) 0 else 1
    }

    override suspend fun extractDownload(
        saveFile: File,
        downLoadInfo: FanboxDownloadInfo,
        sendFiles: ConcurrentLinkedDeque<IdmDownloadInfo>,
        senderP: SendChannel<Boolean>
    ) {
        if (downLoadInfo.coverHref != null) {
            saveFile.resolve("cover.jpeg").run {
                if (!exists()) createNewFile()
                outputStream().use { fout ->
                    requestGeneric.genericGet(downLoadInfo.coverHref!!).execute().bodyStream().use {
                        it.copyTo(fout)
                    }
                }
                senderP.trySend(true)
            }
        }
    }

    private fun String.toTime(): Date {
        val dateTime = OffsetDateTime.parse(this, formatter)
        return Date.from(dateTime.toInstant())
    }

    private fun String.check(info: String = "") {
        if (StringUtil.isBlank(this)) {
            throw RuntimeException("${info}接口未返回任何信息！")
        }
    }

}

data class FanboxPostInfo(
    val postId: String,
    val title: String,
    val priceRequire: Int,
    val publishedDatetime: Date,
    val updatedDatetime: Date,
    val updated: Boolean,
    val adult: Boolean,
    val restricted: Boolean,
    val access: Boolean,
) : CommonPostInfo(postId, title, priceRequire, publishedDatetime, restricted, access)

data class FanboxDownloadInfo(
    var title: String,
    var imgHref: MutableList<FanboxFileInfo>,
    var fileHref: MutableList<FanboxFileInfo>,
    var coverHref: String?,
    var hasContent: Boolean,
) : CommonDownloadInfo<FanboxFileInfo>(title, imgHref, fileHref, hasContent)

data class FanboxFileInfo(val name: String, val href: String, val extension: String) :
    CommonFileInfo(name, href, extension, "")