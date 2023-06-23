package util.core

import CommonDownloadInfo
import CommonFileInfo
import CommonPostInfo
import IdmDownloadInfo
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import util.BasicPlatformCore
import util.RequestUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

//在CI-EN中，start与end的含义解释为 [页数] 排除第一页作者固定的作品，每页有5个作品
class CI_ENCore(private val requestGeneric: RequestUtil) :
    BasicPlatformCore<CIENPostInfo, CIENDownloadInfo, CIENFileInfo>() {

    private val creatorApi = "https://ci-en.dlsite.com/creator/{0}/article?mode=detail&page={1}".requiredTwoParam()
    private val detailApi = "https://ci-en.dlsite.com/creator/{0}/article/{1}".requiredTwoParam()
    private val imgGalleryApi = "https://ci-en.dlsite.com/api/creator/gallery/images"
    private val imgPathApi = "https://ci-en.dlsite.com/api/creator/gallery/imagePath"
    private val planApi = "https://ci-en.dlsite.com/creator/{0}/plan".requiredOneParam()

    private val dateRegex = Regex("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}")

    //    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm")

    override val requestGenerator: RequestUtil
        get() = requestGeneric

    //TODO("当请求过快返回403 Forbidden的时候delay一段时间 这段时间约莫5分钟？也很有可能是每个整点，像10,20，也有可能每5分钟重置一次？ 测试时46m16s前出现的403,50m多一点恢复正常 解决问题的关键点可能在RequestUtil 目前想法是为其配置请求模式，或是为其传入异常处理函数 ")
    //获取最大cien作品的最大页数
    tailrec fun articlePageNum(creatorId: String, pageNum: Int = 1000): Int {
        val html = requestGeneric.getBody(creatorApi(creatorId, pageNum.toString())).html()
        return html.getElementsByClass("pagerItem").last()?.previousElementSibling()?.text()?.toInt()?.let {
            if (it > pageNum) {
                return articlePageNum(creatorId, pageNum * 2)
            } else {
                it
            }
        } ?: 0
    }

    //下面三个属性是用于控制通用下载时的跟随作者
    private var isFollow: Boolean? = null
    private var cId: String? = null
    private var commonDown = false
    override suspend fun fetchPosts(
        creatorId: String,
        start: Int?,
        end: Int?,
        reversed: Boolean
    ): List<CIENPostInfo> {
        //通用下载时要保证查询时是follow状态，但还是要注意一点，通用下载与自己调用core一起进行时将不会抛出错误，但是follow管理将会混乱
        if (commonDown) {
            //跟随这个用户并记录跟随前状态
            if (isFollow == null) {
                isFollow = follow(creatorId)?.let { !it }
                cId = creatorId
            } else {
                throw RuntimeException("请勿在同一个实例中同时执行两个下载任务")
            }
        }

        val pageNum = articlePageNum(creatorId)
        val planMap = getAllPlan(creatorId)
        val range = postRange(start, end, reversed, pageNum - 1)
        val infoArray = Array<List<CIENPostInfo>?>(range.count()) { null }
        coroutineScope {
            for (i in range) {
                launch(Dispatchers.IO) {
                    infoArray[i] = fetchPostsPaged(creatorId, i + 1, planMap)
                }
            }
        }
        return infoArray.filterNotNull().fold(ArrayList<CIENPostInfo>(pageNum * 5 + 1)) { acc, cienCreatorInfos ->
            acc.addAll(cienCreatorInfos)
            acc
        }
    }

    suspend fun fetchPostsPaged(creatorId: String, pageNum: Int): List<CIENPostInfo> {
        return fetchPostsPaged(creatorId, pageNum, getAllPlan(creatorId))
    }


    //该方法返回的 CIENCreatorInfo 中的 articleHtml 属性将没有带有 article-title 这个class的标签
    //这是个标题标签，里面带有一个 <a> 标签用于跳转到详细文章内容，该 <a> 标签会影响links的获取
    val articleIdRegex = Regex("^article-(\\d+)$")
    suspend fun fetchPostsPaged(creatorId: String, pageNum: Int, planMap: Map<String, Int>): List<CIENPostInfo> {
        val html = requestGeneric.getBody(creatorApi(creatorId, pageNum.toString())).html()
        val postsArray: Array<CIENPostInfo?>
        coroutineScope {
            //不知为何文章的tag现在带上 is-article 这个class了
            html.getElementsByClass("e-boxInner is-article").also {
                postsArray = Array(it.size) { null }
            }.forEachIndexed { articleIndex, item ->
                launch(Dispatchers.IO) {
                    var publishTime: Date? = null
                    var updateTime: Date? = null
                    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm")       //SimpleDateFormat并不是线程安全的，被坑惨力~
                    //这里还是不太可能出bug的，主动抛出异常能更好debug？
                    //TODO("如果这里出现问题了大概率是 ci-en 的网站更新了网页")
                    item.getElementsByClass("e-date").forEachIndexed { index, date ->
                        when (index) {
                            0 -> publishTime = dateFormat.parse(dateRegex.find(date.text())!!.value)
                            1 -> updateTime = dateFormat.parse(dateRegex.find(date.text())!!.value)
                        }
                    }
                    if (updateTime == null) updateTime = publishTime
                    var title: String? = null
                    var postId: String? = null
                    item.getElementsByClass("article-title").first()?.let {
                        title = it.text()
                        it.parent()?.let { ele ->
                            postId = articleIdRegex.matchEntire(ele.id())?.groupValues?.get(1)
                        }
                        it.remove()
                    }
                    val restricted = item.getElementsByClass("is-sealed").size != 0
                    val access = item.getElementsByClass("is-open").size != 0 || !restricted
                    val priceList = mutableListOf<Int>()
                    item.getElementsByClass("c-rewardBox").forEach { rewardBox ->
                        val price = getPlanName(rewardBox).let {
                            planMap.getOrDefault(it, -100)
                        }
                        priceList.add(price)
                    }

                    //针对类似 https://ci-en.dlsite.com/creator/14226/article?mode=detail&page=1 出现的  "続きを読む" 目前尚不知道是文章过长会出现这个还是只有置顶投稿会有这个标签
                    //此处没有剔除title这个元素，意味着会在links中多出一个该作品的链接，但无伤大雅，去除这个title反而会使代码看起来更加冗余
                    val articleEle = item.getElementsByClass("e-articleCommentLink").getOrNull(0)
                        ?.getElementsByTag("a")?.getOrNull(0)?.let {
                            if (it.text() == "続きを読む") {
                                requestGeneric.getBody(it.attr("href")).html()
                                    .getElementsByClass("e-boxInner is-article").getOrNull(0) ?: item
                            } else {
                                item
                            }
                        } ?: item

                    val info = CIENPostInfo(
                        postId!!,
                        title!!,
                        priceList.lastOrNull() ?: -1,
                        priceList,
                        publishTime!!,
                        updateTime!!,
                        publishTime != updateTime,
                        restricted,
                        access,
                        articleEle.getElementsByTag("article").first()!!,
                        planMap
                    )
                    postsArray[articleIndex] = info
                }
            }
        }
        return postsArray.filterNotNull()
    }

    //传入的 CIENCreatorInfo中的articleHtml属性需要为<article>标签这个元素
    //如果使用整个投稿检索的话会将头像误作图片，将文章下的tag误作links
    override suspend fun catchPostDownloadInfo(postInfo: CIENPostInfo): CIENDownloadInfo {
        val rewardItem = ArrayList<Element>()
        val planedList = ArrayList<CIENPlanedInfo>()

        suspend fun createCIENPlanedInfo(name: String, price: Int, item: Element): CIENPlanedInfo {
            val imgs = analyzeImage(item)
            val files = analyzeFile(item)
            val links = analyzeLink(item)
            return CIENPlanedInfo(name, price, imgs, files, links)
        }

        val planMap = postInfo.planedMap
        postInfo.articleHtml.let { item ->
            item.getElementsByClass("c-rewardBox").forEach { rewardBox ->
                if (rewardBox.classNames().contains("is-open")) {
                    rewardItem.add(rewardBox)
                }
                rewardBox.remove()
            }
            planedList.add(createCIENPlanedInfo("article", -1, item))

            rewardItem.forEach { reward ->
                val name = getPlanName(reward) ?: "unKnown"
                val price = planMap.getOrDefault(name, -100)
                planedList.add(createCIENPlanedInfo(name, price, reward))
            }
        }

        var hascontent = false

        val imgHref = planedList.fold(mutableListOf<CIENFileInfo>()) { acc, cienPlanedInfo ->
            acc.addAll(cienPlanedInfo.imgInfos)
            acc
        }.apply { hascontent = hascontent or isNotEmpty() }
        val fileHref = planedList.fold(mutableListOf<CIENFileInfo>()) { acc, cienPlanedInfo ->
            acc.addAll(cienPlanedInfo.fileInfos)
            acc
        }.apply { hascontent = hascontent or isNotEmpty() }
        val links = planedList.fold(mutableListOf<String>()) { acc, cienPlanedInfo ->
            acc.addAll(cienPlanedInfo.links)
            acc
        }.apply { hascontent = hascontent or isNotEmpty() }
        return CIENDownloadInfo(postInfo.title.validFileName(), imgHref, fileHref, links, planedList, hascontent)
    }

    //对外开放可能导致支援计划按钮的链接被错误的加入到links中
    private fun analyzeLink(html: Element) = html.getElementsByTag("a").map { it.attr("href") }.toList()

    suspend fun analyzeImage(html: Element): MutableList<CIENFileInfo> {
        val imgInfos = ArrayList<CIENFileInfo>()
        //普通图片
        html.getElementsByTag("vue-l-image").forEachIndexed { index, element ->
            val imgPath = element.attr("data-raw")
            if (imgPath.isBlank()) {
                //头像的显示也会用 vue-l-image 这个标签表示，而且还有<img>标签表示的图片，推测可能为投稿封面？？
                //return@forEachIndexed
                TODO("DO STH  目前已知作者头像也会用到 vue-l-image这个标签，但是没有data-raw这个属性")
            } else {
                val extension = imgPath.urlGetExtension() ?: "png"
                imgInfos.add(CIENFileInfo("$index.$extension", imgPath, extension))
            }
        }

        //对ci-en的画廊展示做多线程请求，画廊包含多张图画，有一个标题
        val mutex = Mutex()
        coroutineScope {
            html.getElementsByTag("vue-image-gallery").forEach { imgEle ->
                launch(Dispatchers.IO) {
                    analyzeImageHref(imgEle)?.let {
                        it.forEachIndexed { index, fileInfo ->
                            if (fileInfo == null) {
                                TODO("DO STH")
                            }
                        }
                        val list = it.filterNotNull()
                        mutex.withLock {
                            imgInfos.addAll(list)
                        }
                    }
                }
            }
        }
        return imgInfos
    }

    fun analyzeFile(html: Element): MutableList<CIENFileInfo> {
        val fileInfos = ArrayList<CIENFileInfo>()
        //解析在线播放的真实url
        html.getElementsByTag("vue-file-player").forEachIndexed { index, player ->
            val info = analyzeOnlinePlayerHref(player)
            if (info == null) {
                TODO("DO STH")
            } else {
                fileInfos.add(info)
            }
        }

        //解析zip下载的url
        //TODO("通用性有待存疑")
        html.getElementsByClass("downloadBlock").forEachIndexed { index, downloadBlock ->
            downloadBlock.getElementsByTag("a").forEach { info ->
                val href = info.attr("href")
                val name = info.attr("download").ifBlank { prefixUUID() + "_" + (href.urlGetFileName() ?: "") }
                fileInfos.add(CIENFileInfo(name, href, name.extension()))
            }
        }
        return fileInfos
    }

    override suspend fun preHook() {
        commonDown = true
    }

    override suspend fun afterHook() {
        if (commonDown) {
            commonDown = false
            isFollow?.let { followed ->
                if (!followed) {
                    cId?.let { unFollow(it) }
                    cId = null
                    isFollow = null
                }
            }
        }
    }

    override suspend fun extractDownload(
        saveFile: File,
        downLoadInfo: CIENDownloadInfo,
        sendFiles: ConcurrentLinkedDeque<IdmDownloadInfo>,
        senderP: SendChannel<Boolean>
    ) {
        downLoadInfo.links.let {
            if (it.isNotEmpty()) {
                saveFile.resolve("links.txt").let { file ->
                    file.createNewFile()
                    file.writeText(it.joinToString("\n"))
                }
            }
        }
    }

    private suspend fun analyzeImageHref(imgEle: Element?): List<CIENFileInfo?>? {
        if (imgEle == null) return null
        val title = imgEle.attr("title").ifBlank { prefixUUID() }
        val hash = imgEle.attr("hash")
        val galleryId = imgEle.attr("gallery-id")
        val time = imgEle.attr("time")
        val galleryHref = "$imgGalleryApi?hash=$hash&gallery_id=$galleryId&time=$time"
        val imgArray = requestGeneric.getBody(galleryHref).toJson().getJSONArray("imgList")
        val imgs = Array<CIENFileInfo?>(imgArray.size) { null }
        coroutineScope {
            for (page in 0 until imgArray.size) {
                launch(Dispatchers.IO) {
                    val fileId = imgArray.getInteger(page)
                    val pathHref = "$imgPathApi?hash=$hash&gallery_id=$galleryId&time=$time&page=$page&file_id=$fileId"
                    val imgPath = requestGeneric.getBody(pathHref).toJson().getString("path")
                    val extension = imgPath.urlGetExtension()
                    if (extension == null) {
                        imgs[page] = null
                        return@launch
                    }
                    imgs[page] = CIENFileInfo("${title}_$page.$extension", imgPath, extension)
                }
            }
        }
        return imgs.toList().ifEmpty { null }
    }


    //传入空元素与网页给出资源在视频和音频以外的类型时会返回null
    private fun analyzeOnlinePlayerHref(videoEle: Element?): CIENFileInfo? {
        if (videoEle == null) return null
        val medium = when (videoEle.attr("file-type")) {
            "audio" -> "audio-web.mp3"
            "video" -> "video-web.mp4"
            else -> null
        } ?: return null

        val fileName = videoEle.attr("file-name").ifBlank { prefixUUID() + medium }
        val extension = fileName.extension().ifBlank { medium.extension() }

        val href = videoEle.attr("base-path") + medium + "?" + videoEle.attr("auth-key")
        return CIENFileInfo(fileName, href, extension)
    }

    //获取该创作者所有的支持计划
    fun getAllPlan(creatorId: String): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        requestGeneric.proxyGetBody(planApi(creatorId))
            .html().getElementsByClass("c-planList-item").forEach { item ->
                val price = item.attr("id").toInt()
                val name: String
                if (price == 0) {
                    name = if (item.getElementsByClass("e-planPrice").first()?.text() == "フォロー") {
                        "フォロー"
                    } else {
                        item.getElementsByClass("planName").first()?.text()
                            ?: item.getElementsByClass("e-planPrice").first()?.text() ?: "フォロー"
                    }
                } else {
                    name = item.getElementsByClass("planName").first()?.text()
                        ?: item.getElementsByClass("e-planPrice").first()?.text() ?: "フォロー"
                }
                map[name.trim()] = price
            }
        return map
    }

    //获取一个作品块中的reward时需要的支持等级的名称
    //要求传入带有“c-rewardBox”这个class的html元素
    private val planNameRegex = Regex("^(【 )?(.+?)( 】プラン)?以上限定")
    fun getPlanName(ele: Element): String? {
        var name = ele.getElementsByClass("c-rewardBox-heading").first()?.text()?.trim() ?: return null
        name = planNameRegex.find(name)?.groupValues?.get(2) ?: return null
        if (name == "フォロワー") name = "フォロー"
        return name
    }

    //跟随作者，返回值代表有没有执行跟随这个操作，返回null代表调用跟随功能时出现错误，可能为网页变更
    fun follow(creatorId: String): Boolean? {
        val html = requestGeneric.getBody(planApi(creatorId)).html()
        return html.getElementById("0")?.let {
            it.getElementsByTag("form").firstOrNull()?.let { form ->
                val params = ArrayList<String>()
                form.getElementsByTag("input").forEach { input ->
                    params.add("${input.attr("name")}=${input.attr("value")}")
                }
                requestGeneric.genericPost(planApi(creatorId)).body(params.joinToString("&"))
                    .execute()
                true
            } ?: false
        }
    }

    //取消跟随作者，返回值代表有没有执行这个操作，返回null代表调用取消跟随功能时出现错误，可能为网页变更
    fun unFollow(creatorId: String): Boolean? {
        val html = requestGeneric.getBody(planApi(creatorId)).html()
        return html.getElementById("0")?.let {
            val href = it.getElementsByTag("a").toList().filter { atag ->
                atag.classNames().contains("e-button")
            }.firstOrNull()?.attr("href") ?: return false
            requestGeneric.getBody(href)
            true
        }
    }

    //查询是否跟随了这个创作者，返回null代表出错，可能网页出现变更
    fun isFollowed(creatorId: String): Boolean? {
        val html = requestGeneric.getBody(planApi(creatorId)).html()
        return html.getElementById("0")?.let {
            it.getElementsByTag("form").firstOrNull() == null
        }
    }

    private fun String.html() = Jsoup.parse(this)

    private fun prefixUUID() = UUID.randomUUID().toString().substring(0, 8)

    private fun String.extension() = this.substringAfterLast('.')

    private fun String.toJson() = JSONObject.parseObject(this)

    private val urlRegex = Regex("^http.*/([^/]+\\.([^/.]+?))(\\?|$)")
    private fun String.urlGetExtension(): String? {
        if (this.isBlank()) return null
        return urlRegex.find(this)?.groupValues?.get(2)
    }

    private fun String.urlGetFileName(): String? {
        if (this.isBlank()) return null
        return urlRegex.find(this)?.groupValues?.get(1)
    }

}

data class CIENPostInfo(
    val postId: String,
    val title: String,
    val priceRequire: Int,
    val priceList: List<Int>,
    val publishedDatetime: Date,
    val updatedDatetime: Date,
    val updated: Boolean,
    val restricted: Boolean,
    val access: Boolean,
    val articleHtml: Element,
    val planedMap: Map<String, Int>,
) : CommonPostInfo(postId, title, priceRequire, publishedDatetime, restricted, access)

data class CIENDownloadInfo(
    var title: String,
    var imgHref: MutableList<CIENFileInfo>,
    var fileHref: MutableList<CIENFileInfo>,
    var links: List<String>,
    val planedInfos: List<CIENPlanedInfo>,
    val hascontent: Boolean,
) : CommonDownloadInfo<CIENFileInfo>(title, imgHref, fileHref, hascontent)

//考虑到通用下载时并不使用这个属性，故此处全为val
data class CIENPlanedInfo(
    val name: String,
    val fee: Int,
    val imgInfos: List<CIENFileInfo>,
    val fileInfos: List<CIENFileInfo>,
    val links: List<String>
)

data class CIENFileInfo(val name: String, val href: String, val extension: String) :
    CommonFileInfo(name, href, extension, "")