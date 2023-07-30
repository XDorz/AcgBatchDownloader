package util.core

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.BasicPlatformCore
import util.RequestUtil
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * onedrive平台的批量下载核心
 *
 * ***onedrive的文档获取顺序为用户正常打开所看到的显示顺序，不一定是按照创建时间或者更新时间排序的***
 * ***注意，onedrive多级域名间cookie不共享，意味着你想抓取另一个作者的作品，你就必须得[更换cookie]***
 *
 * @property requestGeneric
 * @constructor Create empty One drive core
 */
class OneDriveCore private constructor(val requestGeneric: RequestUtil) :
    BasicPlatformCore<OnedrivePostInfo, OnedriveDownloadInfo, CommonFileInfo>() {

    /**
     * @param exampleUrl            给出一个示例链接
     * 最推荐的初始化方法，示例链接可以是分享链接，也可以是打开页面后的浏览器显示的url
     * 该core目前只支持企业版onedrive的抓取，个人版的请求难以破解
     */
    constructor(requestGeneric: RequestUtil, exampleUrl: String) : this(requestGeneric) {
        initParamFromUrl(exampleUrl)
    }

    /**
     * 给出一个域名和部分uri，其获取如下所示
     * https://[XXXXX-my.sharepoint.com]/:f:/g/personal/[XXXXX_XXXX_onmicrosoft_com]/EuEXDf8XtHTxOMBS-WDYyOkt0pC0Ltg?e=TfD8na
     * 该链接是一个onedrive企业版的分享链接
     * 其中第一个用[]框起来的内容为需要的param1，第二个则是param2，链接做过脱敏处理
     */
    constructor(requestGeneric: RequestUtil, param1: String, param2: String) : this(requestGeneric) {
        this.param1 = param1
        this.param2 = param2
    }

    private val fileListUrl =
        "https://{0}/personal/{1}/_api/web/GetListUsingPath(DecodedUrl=@a1)/RenderListDataAsStream?@a1=''/personal/{1}/Documents''&RootFolder=/personal/{1}/Documents/{2}&TryNewExperienceSingle=TRUE".requiredThreeParam()
    private val innerFileListUrl =
        "https://{0}/personal/{1}/_api/web/GetListUsingPath(DecodedUrl=@a1)/RenderListDataAsStream?@a1=''/personal/{1}/Documents''&RootFolder=/personal/{1}/Documents/{2}/{3}&TryNewExperienceSingle=TRUE".requiredFourParam()
    private val nextListUrl =
        "https://{0}/personal/{1}/_api/web/GetListUsingPath(DecodedUrl=@a1)/RenderListDataAsStream?@a1=''/personal/{1}/Documents''&TryNewExperienceSingle=TRUE&{2}".requiredThreeParam()
    private val postValue =
        "'{\"parameters\":{\"RenderOptions\":'{0}',\"AllowMultipleValueFilterForTaxonomyFields\":true,\"AddRequiredFields\":true}}'".requiredOneParam()
    private val downloadUrl = "https://{0}/personal/{1}/_layouts/15/download.aspx?UniqueId={2}".requiredThreeParam()
    private var param1: String? = null
    private var param2: String? = null
    private val opt1 = "5445383"
    private val opt2 = "1185543"
    private val opt3 = "1216519"
    private val imgTypeList = listOf<String>("png", "gif", "jpg", "jpeg", "webp", "bmp")

    override suspend fun preHook() {
        println("该core仍处于beta版，可能会出现下载不完全，无法进行下载等问题。")
    }

    override val requestGenerator: RequestUtil
        get() = requestGeneric

    /**
     * 打开分享链接后，可以看到文件列表的上方有你现在浏览的文件的路径
     * 此处需要的key就是文件除去用户名后的路径
     * 比如你看到的当前路径为 AuthorName/视频/截图/AAA
     * 此时你需要传入的key值就为 `视频/截图/AAA`
     * @see convertToKey
     *
     * ***注意，该方法只获取给定目录下所有的[文件和文件夹]，无法递归的获取文件夹里面的文件夹，要获取该文件夹里面的所有[文件]，请使用***
     * @see catchPostDownloadInfo
     */
    override suspend fun fetchPosts(key: String, start: Int?, end: Int?, reversed: Boolean): List<OnedrivePostInfo> {
        val posts: MutableList<OnedrivePostInfo>
        val fileList = analysisUrlForFileJsonArray(fileListUrl(param1!!, param2!!, key), opt1).apply {
            val postAry = Array<OnedrivePostInfo?>(size) { null }
            coroutineScope {
                forEachIndexed { index, _ ->
                    val jsonInfo = getJSONObject(index)
                    launch(Dispatchers.IO) {
                        postAry[index] = parseOnedrivePostInfo(jsonInfo, true).apply { _key = key }
                    }
                }
            }
            posts = postAry.filterNotNull().toMutableList()
            //为保证多个core的行为统一性，此处需要读作品的顺序做翻转，变为最新的作品的index在最前
            posts.reverse()
        }
        val range = postRange(start, end, reversed, fileList.size - 1)
        posts.subList(range.first, range.last + 1)
        return posts
    }

    override suspend fun catchPostDownloadInfo(postInfo: OnedrivePostInfo): OnedriveDownloadInfo {
        if (postInfo.type == OnedriveFileType.FILE) {
            return rootFileParseOnedriveDownloadInfo(postInfo)
        }
        return catchPostDownloadInfoRec(postInfo, mutex = Mutex()).apply {
            title = title.validFileName()
        }
    }

    private suspend fun catchPostDownloadInfoRec(
        postInfo: OnedrivePostInfo,
        saveRelativePath: String = "",
        info: OnedriveDownloadInfo = defaultDownloadInfo(postInfo),
        mutex: Mutex
    ): OnedriveDownloadInfo {
        val fileListJson = if (saveRelativePath.isBlank()) {
            analysisUrlForFileJsonArray(innerFileListUrl(param1!!, param2!!, postInfo._key, postInfo.title), opt2)
        } else {
            val path = if (saveRelativePath.endsWith("/")) {
                saveRelativePath.substring(0, saveRelativePath.length - 1)
            } else {
                saveRelativePath
            }
            analysisUrlForFileJsonArray(
                innerFileListUrl(param1!!, param2!!, postInfo._key, "${info.title}/$path"),
                opt2
            )
        }


        coroutineScope {
            for (i in 0 until fileListJson.size) {

                //此处不要求更新时间，单解析json耗时忽略不计，所以不使用多线程
                val postFileInfo =
                    parseOnedrivePostInfo(fileListJson.getJSONObject(i), false).apply { _key = postInfo._key }
                if (postFileInfo.type == OnedriveFileType.DIRECTORY) {
                    launch(Dispatchers.IO) {
                        catchPostDownloadInfoRec(
                            postFileInfo,
                            "$saveRelativePath${URLEncoder.encode(postFileInfo.title, StandardCharsets.UTF_8)}/",
                            info,
                            mutex
                        )
                    }
                } else {
                    info.hasContent = true
                    val fileInfo = CommonFileInfo(
                        postFileInfo.title.validFileName(),
                        analysisDownloadUrl(postFileInfo.postId),
                        postFileInfo.fileType,
                        cleanSaveRelativePath(saveRelativePath)
                    )
                    mutex.withLock {
                        if (postFileInfo.fileType.lowercase() in imgTypeList) {
                            info.imgInfos.add(fileInfo)
                        } else {
                            info.fileInfos.add(fileInfo)
                        }
                    }
                }
            }
        }
        return info
    }

    private fun cleanSaveRelativePath(saveRelativePath: String): String {
        val path = saveRelativePath.split("/").joinToString("/") { it.validFileName() }
        return if (path.endsWith("/")) {
            path.dropLast(1)
        } else {
            path
        }
    }

    private fun analysisUrlForFileJsonArray(url: String, opt: String): JSONArray {
        checkParamsInit()
        val request = requestGeneric.genericPost(url).apply {
            body(postValue(opt))
        }
        val body = request.execute().body()
        val jsonObj = JSONObject.parseObject(body)?.getJSONObject("ListData")
            ?: run {
                println()
                println(body)
                throw RuntimeException("无法获取文件列表信息，可能是cookie刷新(当你看到 UnauthorizedAccessException 时请更新您的cookie)或是接口变动")
            }
        val array = jsonObj.getJSONArray("Row")
            ?: throw RuntimeException("无法获取onedrive下所有文件列表，可能是接口变动")
        jsonObj.getString("NextHref")?.let {
            array.addAll(recForFile(it))
        }
        return array
    }

    //递归获取所有目录，不包括调用它的那一段目录
    //该方法递归获取文件的原顺序
    private fun recForFile(json: String): JSONArray {
        val nextUrl = json.let { href ->
            val nextParam = if (href.startsWith('?')) href.substring(1) else href
            nextListUrl(param1!!, param2!!, nextParam)
        }

        val request = requestGeneric.genericPost(nextUrl).apply {
            body(postValue(opt3))
        }
        val body = request.execute().body()
        val listData = JSONObject.parseObject(body)?.getJSONObject("ListData")
            ?: run {
                println()
                println(body)
                throw RuntimeException("无法获取onedrive剩余的文件列表，可能是cookie刷新或是接口变动")
            }

        val array = listData.getJSONArray("Row")
        listData.getString("NextHref")?.let {
            array.addAll(recForFile(it))
        }
        return array
    }

    private fun defaultDownloadInfo(postInfo: OnedrivePostInfo): OnedriveDownloadInfo {
        return OnedriveDownloadInfo(postInfo.title, mutableListOf(), mutableListOf(), false)
    }

    private fun analysisDownloadUrl(uniqueId: String): String {
        checkParamsInit()
        return downloadUrl(param1!!, param2!!, uniqueId.lowercase())
    }


    private fun checkParamsInit() {
        if (param1 == null || param2 == null) throw RuntimeException("未能找到必须参数,请确保您已经调用过 initParamFromUrl 方法来初始化param")
    }

    private fun parseOnedrivePostInfo(json: JSONObject, needTime: Boolean): OnedrivePostInfo {
        val id = json.getString("ID")
        val type = json.getString("FSObjType")
        val postId = json.getString("UniqueId").let {
            it.substring(1, it.length - 1)
        }
        val title = json.getString("FileLeafRef")
        val fileType = json.getString(".fileType")
        val innerFileCount = json.getInteger("ItemChildCount")

        val (createDate, modifiedDate) = if (needTime) {
            //此处发起请求获取更为详细的时间信息，但是这样会增加等待时间，增加风控可能
            val itemDetailUrl = json.getString(".spItemUrl")
            getItemTimeDetail(itemDetailUrl)
        } else {
            Date() to Date()
        }

        return OnedrivePostInfo(
            id,
            postId,
            title,
            parseType(type),
            fileType,
            createDate,
            modifiedDate,
            createDate.time != modifiedDate.time,
            innerFileCount
        )
    }

    private fun rootFileParseOnedriveDownloadInfo(postInfo: OnedrivePostInfo): OnedriveDownloadInfo {
        val fileInfos = mutableListOf(
            CommonFileInfo(postInfo.title, analysisDownloadUrl(postInfo.postId), postInfo.fileType, "")
        )
        val emptyList = ArrayList<CommonFileInfo>(0)
        return if (postInfo.title.lowercase() in imgTypeList) {
            OnedriveDownloadInfo(postInfo.title, fileInfos, emptyList, true)
        } else {
            OnedriveDownloadInfo(postInfo.title, emptyList, fileInfos, true)
        }
    }

    fun initParamFromUrl(url: String) {
        val reg = Regex("^https?://([^/]+)/?.*/personal/([^/]+)")
        reg.find(url)?.groupValues?.run {
            param1 = getOrNull(1)
            param2 = getOrNull(2)
            if (param1 == null || param2 == null) return@run null
            if (!param1!!.endsWith(".sharepoint.com")) return@run null
            return@run this
        } ?: TODO("当前仅支持XXX-my.sharepoint.com域名(企业版onedrive)分享的文件")
    }

    private fun parseType(type: String): OnedriveFileType {
        val tp = type.toInt()
        OnedriveFileType.values().forEach {
            if (it.code == tp) return it
        }
        return OnedriveFileType.UNKNOWN
    }

    private fun getItemTimeDetail(itemUrl: String): Pair<Date, Date> {
        val body = requestGeneric.getBody(itemUrl)
        return JSONObject.parseObject(body)?.getJSONObject("fileSystemInfo")?.run {
            val create = getString("createdDateTime").let {
                if (it == null || it == "") {
                    Date(0)
                } else {
                    ISOTime2DateTime(it)
                }
            }
            val modify = getString("lastModifiedDateTime").let {
                if (it == null || it == "") {
                    Date(0)
                } else {
                    ISOTime2DateTime(it)
                }
            }
            Pair(create, modify)
        } ?: Pair(Date(0), Date(0))
    }

    private fun ISOTime2DateTime(time: String): Date {
        val localDateTime = LocalDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME)
        val instant = localDateTime.toInstant(ZoneOffset.UTC)
        return Date.from(instant)
    }

    fun convertToKey(list: List<String>, containsAuthor: Boolean = true): String {
        return list.toMutableList().run {
            if (containsAuthor) {
                if (list.isNotEmpty()) removeAt(0)
            }
            joinToString("/")
        }
    }
}

data class OnedrivePostInfo(
    val id: String,
    val postId: String,
    val title: String,
    val type: OnedriveFileType,
    val fileType: String,
    val publishedDatetime: Date,
    val updatedDatetime: Date,
    val updated: Boolean,
    val innerFileCount: Int
) : CommonPostInfo(postId, title, -1, publishedDatetime, false, true) {
    lateinit var _key: String
}

class OnedriveDownloadInfo(
    title: String,
    imgHref: MutableList<CommonFileInfo>,
    fileHref: MutableList<CommonFileInfo>,
    hasContent: Boolean,
) : CommonDownloadInfo<CommonFileInfo>(title, imgHref, fileHref, hasContent, null)

enum class OnedriveFileType(val code: Int, val desc: String) {
    DIRECTORY(1, "文件夹"),
    FILE(0, "文件"),
    UNKNOWN(-1, "未知")
}