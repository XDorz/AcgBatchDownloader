import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max


//TODO("可以设定请求线程数")
//TODO("月刊制平台可以指定月份")
class CommonArticleDownloader<T : CommonPostInfo, K : CommonDownloadInfo<E>, E : CommonFileInfo>(private val core: BasicPlatformCore<T, K, E>) {
    private val accMap = HashMap<String, Int>()
    private val requestGeneric: RequestUtil = core.requestGenerator

    /**
     * 调用默认的下载行为，可传入以下过滤函数过滤作品
     *
     * @param filterFile            该函数过滤作者的投稿
     * @param filterCreator         该函数过滤作者投稿中的附件与图片
     *
     * 需要注意的是 filterCreator 中给出的index代表作者投稿顺序的逆序，即越新的投稿index值越小，此举为了方便获取特定数量的更新
     * 为了统一，filterFile中的index值也是作者投稿顺序的逆序，但需要注意的是filterFile过滤之前就已经将[没有]图片与附件的投稿过滤掉了(即使有封面存在也会过滤掉)
     * 而当[下载]时，则会按照作者的投稿顺序进行下载与Idm信息的发送
     *
     * filter不仅限于过滤,还可以打印作品的名称，取消封面或者文件的下载等，以下是示例代码
     *     FanboxDownloader.defaultDownload("XXX","C:\\windows\\XXX",requestGeneric, filterFile = {_,info ->
     *         if(info.imgHref.isEmpty()) {
     *             false                                    //过滤所有没有图片的投稿
     *         }else{
     *             info.coverHref = null
     *             info.fileHref.clear()                    //将文件下载列表置空，表示本次不下载文件
     *             println(info.title)                      //打印文件名
     *             true
     *         }
     *     })
     *
     *     ***filterCreator与filterFile两者都是单线程的***
     *     ***只有请求接口获取作品信息和下载投稿图片是使用多线程的***
     *
     * 需要注意的是即使你将coverHref,fileHref,imgHref全置为空，程序仍会尝试对其进行解析和下载,
     * 最明显的一点就是会创建一个空文件用于存放下载内容，如果要跳过这个步骤请使用filter原本的功能--即过滤掉这个下载任务
     * 举例来说,你不想下载图片,可以使用 info.imgHref = arrayListOf(),
     * 此时如果你想确保不会有额外的空文件被创建,请于这段代码后检查cover和fileHref是否为空并返回Boolean进行下载任务过滤
     *
     * @param authorKey             作者名称，需要从url中获取
     * @param savePath              作品保存路径
     * @param download              是否进行下载，默认为是，如果不希望进行下载仅希望获取作品下载链接或希望打印作品名称等请设置为false并通过给定的两个filter获取作品的信息
     * @param start                 XX的获取起始范围 （不同的平台有不同的解释，请参见各个core的开头）
     * @param end                   XX的获取结束范围 （不同的平台有不同的解释，请参见各个core的开头）
     * @param prefixIndexStart      作品分类保存的数字前缀起始，默认为空则是自动对比和判断之前下载的文件
     * @param reversed              是否翻转，默认为true，即正常浏览顺序，将最新的作品置于前面
     * @param printProgress         是否打印进度条
     */
    @OptIn(ObsoleteCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun commonDownload(
        authorKey: String,
        savePath: String,
        download: Boolean = true,
        start: Int? = 0,
        end: Int? = null,
        reversed: Boolean = true,
        prefixIndexStart: Int? = null,
        printProgress: Boolean = true,
        filterFile: CommonArticleDownloader<T, K, E>.(index: Int, K) -> Boolean = { _, _ -> true },
        filterCreator: CommonArticleDownloader<T, K, E>.(index: Int, T) -> Boolean = { _, _ -> true },
    ): List<String> {
        //守护线程----状态打印区
        var photoDown = 0
        var totalP = 0
        var fileSend = 0
        var totalFile = 0
        var senderP: SendChannel<Boolean> = Channel(onBufferOverflow = BufferOverflow.DROP_LATEST)
        var state = 0
        if (printProgress) {
            GlobalScope.launch {
                senderP = actor<Boolean>(capacity = 100) {
                    for (b in channel) photoDown++
                }
                val progress = ScrollProgress()
                while (true) {
                    delay(100)
                    print('\r')
                    print(progress.next())
                    print('\t')
                    when (state) {
                        0 -> print("正在解析作者所有作品")
                        1 -> print("作品下载地址获取中")
                        2 -> print("信息统计中")
                        3 -> print("作品图片下载/发送中 图片:$photoDown/$totalP  附件:$fileSend/$totalFile")
                    }
                }
            }
        }


        val failed = ConcurrentLinkedDeque<String>()
        //解析并下载
        runBlocking {
            core.preHook()

            val mutex = Mutex()

            //获取该用户所有作品
            val posts = core.fetchPosts(authorKey, start, end, reversed).filterIndexed { i, c -> filterCreator(i, c) }

            //获取该用户所有作品的下载链接解析
            state = 1
            val array = Array<Any?>(posts.size) { null }
            coroutineScope {
                for ((index, creatorInfo) in posts.withIndex()) {
                    launch(Dispatchers.IO) {
                        val downloadInfo = core.catchPostDownloadInfo(creatorInfo)
                        array[index] = downloadInfo
                    }
                }
            }

            //数据统计
            state = 2
            val downloadInfos =
                array.filterNotNull().map { it as K }.filterIndexed { i, d -> filterFile(i, d) }
                    .filter { d -> d.hasContent }.reversed()
            downloadInfos.forEach {
                totalP += it.imgInfos.size
                totalFile += it.fileInfos.size
                totalP += core.extractImgNum(it)
                totalFile += core.extractFileNum(it)
            }
            if (!download) {
                println()
                println("图片:$totalP  附件:$totalFile")
                return@runBlocking
            }


            //文件夹创建，图片下载，附件链接发送
            state = 3
            File(savePath).run {
                if (!exists()) mkdirs()
            }
            coroutineScope {
                val indexOffset = if (prefixIndexStart == null) {
                    var max = -1
                    val reg = Regex("^(\\d+)_.+$")
                    File(savePath).listFiles()?.forEach { f ->
                        reg.matchEntire(f.name)?.let { match ->
                            max = max(match.groupValues[1].toInt(), max)
                        }
                    }
                    max + 1
                } else {
                    0
                }
                downloadInfos.forEachIndexed download@{ index, downloadInfo ->
                    //此处判断prefixIndexStart是否被设置值，为null的话则遍历savePath下所有文件夹判断是否该续接上之前的index
                    val postSaveIndex = prefixIndexStart?.let {
                        prefixIndexStart + index
                    } ?: (indexOffset + index)

                    val _title = downloadInfo.title
                    val titleFile = File("$savePath\\${postSaveIndex}_${_title}")

                    //投稿图片下载
                    downloadInfo.imgInfos.forEach { info ->
                        val href = info.href
                        launch(Dispatchers.IO) {
                            saveFile(savePath, info, titleFile, _title, postSaveIndex).run {
                                try {
                                    if (!parentFile.exists()) parentFile.mkdirs()
                                    if (!exists()) createNewFile()
                                } catch (e: Exception) {
                                    failed.add(absolutePath)
                                    return@launch
                                }
                                //如果一次性爬取太多可能会触发风控，此时会抛出异常导致程序崩溃  此时需要try catch来维持程序不崩溃
                                //TODO("添加进一步错误处理，如delay一段时间后重试  注意并发问题")
                                //2023/6/13 不同平台不同风控很难处理，在PlatformCore 中为每个平台设置一个专门的风控处理函数的代价有点高，还要考虑并发带来的问题
                                //而且风控策略可能会随时间更新，也不一定保证能有效果，风控可能和梯子ip有关，可能和账号有关，可能和作者有关，这个功能不太可能去实现
                                //2023/7/6 也许每个core可以设置一个值，当爬取内容超过这个值时触发慢速爬取，不使用多线程或者yield将主要任务交给下载
                                try {
                                    outputStream().use { fout ->
                                        requestGeneric.genericGet(href).execute().bodyStream().use {
                                            it.copyTo(fout)
                                        }
                                    }
                                    senderP.trySend(true)
                                } catch (e: Exception) {
                                    failed.add(absolutePath)
                                }
                            }
                        }
                    }

                    //附件发送IDM
                    launch(Dispatchers.IO) {
                        downloadInfo.fileInfos.forEach { info ->
                            val href = info.href
                            val savedFile = saveFile(savePath, info, titleFile, _title, postSaveIndex).apply {
                                if (!parentFile.exists()) parentFile.mkdirs()
                            }
                            mutex.withLock {
                                IdmUtil.sendLinkToIDM(
                                    href = href,
                                    cookie = requestGeneric.cookie,
                                    referer = requestGeneric.referer,
                                    localPath = savedFile.parent,
                                    localFileName = savedFile.name,
                                )
                                fileSend++
                            }
                        }
                    }

                    //额外下载
                    launch(Dispatchers.IO) {
                        val idmDownloadInfo = ConcurrentLinkedDeque<IdmDownloadInfo>()
                        core.extractDownload(titleFile, downloadInfo, idmDownloadInfo, senderP)
                        mutex.withLock {
                            idmDownloadInfo.forEach {
                                IdmUtil.sendLinkToIDM(it)
                                fileSend++
                            }
                        }
                    }

                    //如果该投稿文件夹未创建，则将偏移减一，使得下个投稿的index使用这个的
                    //thinking：多线程环境下无法保证该代码在titleFile文件创建之后执行，会造成错误的index
                    //if(!titleFile.exists()) unusedIndexOffset--

                }
                core.afterHook()
            }
        }
        if (printProgress) {
            println()
            println("所有任务已完成！请享受作品")
        }
        return failed.toList()
    }

    fun accumulator(key: String, value: Int = 1) {
        accMap[key] = (accMap[key] ?: 0) + value
    }

    fun getAcc(key: String): Int? = accMap[key]

    fun accClear() = accMap.clear()

    fun List<String>.deleteFailed() {
        this.forEach {
            File(it).let { file ->
                if (file.exists()) file.delete()
            }
        }
    }

    fun List<T>.println(transform: (T) -> CharSequence) {
        println(this.joinToString("\n", transform = transform))
    }

    private fun saveFile(savePath: String, info: CommonFileInfo, titleFile: File, title: String, index: Int): File {
        val savedFile = when {
            //星号开头表示使用绝对路径
            info.saveRelativePath.startsWith("*") -> {
                val path = info.saveRelativePath.substring(1)
                val firstIndex = path.indexOfFirst { it != '/' && it != '\\' }
                File(path.substring(firstIndex))
            }
            //单问号开头表示使用save path作为保存路径
            info.saveRelativePath.startsWith("?") -> {
                val path = info.saveRelativePath.substring(1)
                val firstIndex = path.indexOfFirst { it != '/' && it != '\\' }
                File(savePath).resolve(path.substring(firstIndex))
            }
            //默认情况下是在save path下创建以投稿为名的的文件夹作为保存路径
            else -> {
                val path = info.saveRelativePath
                val firstIndex = path.indexOfFirst { it != '/' && it != '\\' }
                titleFile.resolve(path.substring(firstIndex))
            }
        }

        //如果saveRelativePath被修改过，则触发下列代码
        return if (info._chasPathChanged) {
            //当保存路径不带有投稿名称，则将该文件的名称加上 `index_投稿名称` 前缀
            if (!savedFile.canonicalPath.contains(title)) {
                savedFile.resolve("${index}_${title}_${info.name}")
            } else {
                //其余情况正常处理
                savedFile.resolve(info.name)
            }
        } else {
            savedFile.resolve(info.name)
        }
    }

}