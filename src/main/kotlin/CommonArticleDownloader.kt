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

class CommonArticleDownloader<T: CommonPostInfo,K: CommonDownloadInfo<E>,E: CommonFileInfo>(private val core: BasicPlatformCore<T,K,E>) {
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
     * @param authorKey            作者名称，需要从url中获取
     * @param savePath              作品保存路径
     * @param requestGeneric        请求生成器，用于生成配置了指定参数的请求，如cookie，referer
     * @param download              是否进行下载，默认为是，如果不希望进行下载仅希望获取作品下载链接或希望打印作品名称等请设置为false并通过给定的两个filter获取作品的信息
     * @param printProgress         是否打印进度条
     */
    @OptIn(ObsoleteCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun commonDownload(
        authorKey: String,
        savePath: String,
        download: Boolean = true,
        printProgress: Boolean = true,
        filterFile: CommonArticleDownloader<T,K,E>.(index: Int, K) -> Boolean = { _, _ -> true },
        filterCreator: CommonArticleDownloader<T,K,E>.(index: Int, T) -> Boolean = { _, _ -> true },
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


        val failed = ArrayList<String>()
        //解析并下载
        runBlocking {
            core.preHook()

            val mutex = Mutex()

            //获取该用户所有作品
            val posts = core.fetchPosts(authorKey).filterIndexed { i, c -> filterCreator(i, c) }

            //获取该用户所有作品的下载链接解析
            state = 1
            val array = Array<Any?>(posts.size) { null }
            coroutineScope {
                for ((index, creatorInfo) in posts.withIndex()) {
                    launch(Dispatchers.IO) {
                        val downloadInfo = core.catchPostDownloadInfo(creatorInfo)
                        //TODO("使被filterFile修改过的 c_hasContent 可在下载中体现影响")
                        if (downloadInfo.c_hasContent) {
                            array[index] = downloadInfo
                        }
                    }
                }
            }

            //数据统计
            state = 2
            val downloadInfos =
                array.filterNotNull().map { it as K }.filterIndexed { i, d -> filterFile(i, d) }.reversed()
            downloadInfos.forEach {
                totalP += it.c_imgHref.size
                totalFile += it.c_fileHref.size
                totalP += core.extractImgNum(it)
                totalFile += core.extractFileNum(it)
            }
            if (!download) return@runBlocking


            //文件夹创建，图片下载，附件链接发送
            state = 3
            File(savePath).run {
                if (!exists()) mkdirs()
            }
            coroutineScope {
                downloadInfos.forEachIndexed { index, downloadInfo ->
                    val titleFile = File("$savePath\\${index}_${downloadInfo.c_title}").apply {
                        if (!exists()) mkdirs()
                    }

                    //投稿图片下载
                    downloadInfo.c_imgHref.forEach { T ->
                        val name = T.c_name
                        val href = T.c_href
                        launch(Dispatchers.IO) {
                            titleFile.resolve(name).run {
                                try {
                                    if (!exists()) createNewFile()
                                }catch (e: Exception){
                                    println(absolutePath)
                                    throw e
                                }
                                //如果一次性爬取太多可能会触发风控，此时会抛出异常导致程序崩溃  此时需要try catch来维持程序不崩溃
                                //TODO("添加进一步错误处理，如delay一段时间后重试  注意并发问题")
                                try {
                                    outputStream().use { fout ->
                                        requestGeneric.genericGet(href).execute().bodyStream().use {
                                            it.copyTo(fout)
                                        }
                                    }
                                    senderP.trySend(true)
                                }catch (e: Exception){
                                    failed.add(absolutePath)
                                }
                            }
                        }
                    }

                    //附件发送IDM
                    launch(Dispatchers.IO) {
                        downloadInfo.c_fileHref.forEach { T ->
                            val name = T.c_name
                            val href = T.c_href
                            mutex.withLock {
                                IdmUtil.sendLinkToIDM(
                                    href = href,
                                    cookie = requestGeneric.cookie,
                                    referer = requestGeneric.referer,
                                    localPath = titleFile.absolutePath,
                                    localFileName = name
                                )
                                fileSend++
                            }
                        }
                    }

                    //额外下载
                    launch(Dispatchers.IO) {
                        val idmDownloadInfo = ConcurrentLinkedDeque<IdmDownloadInfo>()
                        core.extractDownload(titleFile,downloadInfo, idmDownloadInfo,senderP)
                        mutex.withLock {
                            idmDownloadInfo.forEach {
                                IdmUtil.sendLinkToIDM(it)
                                fileSend++
                            }
                        }
                    }

                }
                core.afterHook()
            }
        }
        if (printProgress) {
            println()
            println("所有任务已完成！请享受作品")
        }
        return failed
    }

    fun accumulator(key: String, value: Int = 1) {
        accMap[key] = (accMap[key] ?: 0) + value
    }

    fun getAcc(key: String): Int? = accMap[key]

    fun accClear() = accMap.clear()

    fun List<String>.deleteFailed(){
        this.forEach {
            File(it).let {
                if (it.exists()) it.delete()
            }
        }
    }

    fun List<T>.println(transform: (T) -> CharSequence) {
        println(this.joinToString("\n",transform = transform))
    }

}