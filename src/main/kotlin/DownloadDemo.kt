import kotlinx.coroutines.runBlocking
import util.DeCompressUtil
import util.RequestUtil
import util.core.CI_ENCore
import util.core.OneDriveCore
import util.core.OnedriveFileType
import java.util.*
import kotlin.system.measureTimeMillis

class DownloadDemo {
    val cienGeneric = RequestUtil().apply {
        cookie = "你的ci-en的cookie"
        allowRedirect = false
        proxyAddr = "127.0.0.1"
        proxyPort = 10809
        //headers = mutableMapOf("Accept-Language" to mutableListOf("zh-CN","zh;q=0.9"))
    }
    val cienCore = CI_ENCore(cienGeneric)

    val fanboxGeneric = RequestUtil().apply {
        cookie = "你的fanbox cookie"
        origin = "https://www.fanbox.cc"
        referer = "https://www.fanbox.cc/"
        proxyAddr = "127.0.0.1"
        proxyPort = 10809
    }

    val oneDriveGeneric = RequestUtil().apply {
        //每个作者的分享页面cookie都不同，当下载别的作品时请记得切换cookie
        //cookie每天都会刷新
        //请确保能打开分享页面再去寻找cookie，如果分享链接需要密码，请输入密码后进入分享页面获取cookie
        cookie = "你的oneDrive cookie"
        proxyAddr = "127.0.0.1"
        proxyPort = 10809
    }

    /**
     * 方法一：
     * 第二个参数可以直接粘贴 分享链接/打开后的分享页面URL
     * 此操作主要为了获取域名和path等信息
     * 在这里主要获取  ABCDE-my.sharepoint.com 和 FGHIJ_KLMNOPQ_RSTUVW_com 这两个参数
     */
    val oneDriveCore = OneDriveCore(
        oneDriveGeneric,
        "https://ABCDE-my.sharepoint.com/:f:/g/personal/FGHIJ_KLMNOPQ_RSTUVW_com/EuEXyCWTxOMBS-WDYyObFyLtg?e=TfD8na"
    )

    /**
     * 方法二：
     * 您也可以直接将需要的两个参数粘贴进构造函数
     */
//    val oneDriveCore = OneDriveCore(oneDriveGeneric,"ABCDE-my.sharepoint.com","FGHIJ_KLMNOPQ_RSTUVW_com")

    companion object {
        //fanbox下载
//        fun main() {
//            CommonArticleDownloader(fanboxCore).apply {
//                // 当你点开pixiv的某个作者主页的赞助时，能看到浏览器的url链接
//                // https://XXXXXX.fanbox.cc/?utm_campaign=www_profile&utm_medium=site_flow&utm_source=pixiv
//                //或者 https://www.fanbox.cc/@XXXXX
//                //此处的域名的XXXXXX就是我们所需要的key
//                //第三个参数表示不进行下载，我们在此处只进行作品统计
//                commonDownload("XXXXXX", "G:/your/save/path", false, filterContent = { _, info ->
//                    accumulator("postNum")                  //使用内置的计数器计数，它是线程不安全的，但是filter中的代码块不会在多线程中调用，如果您想要在多线程环境下使用该计数器，推荐使用锁等结构保证线程安全性
//
//                    //saveRelativePath 为其保存的相对路径(也可设为绝对路径)
//                    //其中`?`(代表传入的savePath，即将文件直接保存到根目录)和`*`(绝对路径)为关键词，它们需要放在字符串的最前面
//                    //为防止名称冲突，当文件不在含有投稿名称的路径下时会加上投稿名称前缀来防止冲突
//                    info.imgInfos.forEach { it.saveRelativePath = "?inner" }     //该图片会保存在 G:/your/save/path/inner 文件夹下
//                    info.imgInfos.forEach {
//                        it.saveRelativePath = "inner/咱们可以玩点花的/../"
//                    }       //该图片会保存在 G:/your/save/path/index_投稿名称/inner 文件夹下
//                    info.fileInfos.forEach {
//                        it.saveRelativePath = "*G:\\download\\fanbox"
//                    }  //该文件会保存在 G:\download\fanbox 文件夹下
//                    true
//                }) { _, fanboxPostInfo ->
//                    fanboxPostInfo.adult        //lambada会将这个表达式的值返回
//                }
//                println("该作者的成人作品投稿有 ${getAcc("postNum")} 个")
//                accClear()                      //清空内置计数器，每一个通用下载器实例都含有一个计数器实例
//            }
//        }


        //ci-en的下载
//        fun main() {
//            CommonArticleDownloader(cienCore).apply {
//                //此处的14266为用户id  例如你浏览一个用户，你可以看到浏览器URL为
//                //https://ci-en.dlsite.com/creator/14266
//                //此时creator后面的那个数字(即14266)就是用户id
//                //这里指定start，end表示下载第0-10页，在reversed为true时，表示第0-10页(正常浏览时所看到的页数顺序)
//                //此处表示下载第max-10 到 max页，也就是最早的10页作品(正常浏览时看到的是最新最晚发布的作品)
//                val fail =
//                    commonDownload(
//                        "14266",
//                        "G://your//save//path",
//                        true,
//                        0,
//                        10,
//                        reversed = false,
//                        filterContent = { _, cienDownloadInfo ->
//                            //您可以在此处修改任意信息
//                            //去除所有文件的下载
//                            cienDownloadInfo.fileInfos.clear()
//                            //在此处可以修改分类保存的每个文件名，不推荐将所有文件名修改为一个，会导致不同投稿的文件覆盖
//                            cienDownloadInfo.title = cienDownloadInfo.title
//                            //过滤掉没有图片的作品信息
//                            cienDownloadInfo.imgInfos.isNotEmpty()
//                        }) filter@{ index, cienPostInfo ->
//                        //下载前51个
//                        if (index > 50) {
//                            return@filter false
//                        }
//                        //过滤掉所有以"【特別動画】"为开头的标题
//                        if (cienPostInfo.title.startsWith("【特別動画】")) return@filter false
//                        true
//                    }
//                println("共有以下文件下载失败 \n" + fail.joinToString("\n"))
//            }
//        }

        //oneDrive 下载
        fun main() {
            CommonArticleDownloader(oneDriveCore).apply {
                //请阅读创建变量oneDriveCore那块的注释
                //此处的key为你要下载文件所处的路径(父路径)  ***要排除最前面的那个 作者名字(有时候会因为某种原因显示为 我的文件)***
                //比如你想要下载一些共享的文件，你看到如下信息
                //AuthorName > this > is > sample > path
                //即这些文件在 this/is/sample/path 这个路径下
                //那么此时key则为 `this/is/sample/path`
                //注意排除最前面的那个`AuthorName`那个是作者名字，并不包含在路径中
                //如果您怕出错，您可以使用oneDriveCore.convertToKey将你见到的所有信息放入list中传入
                //就像demo展示的那样
                //
                //***需要注意的是filterPosts所给出的项是给出的key下的直接文件(包括文件夹，视频，zip等所有)***
                //***filterContent则是递归获取每一个通过了filterPosts的直接文件夹下的每一个文件(除了文件夹外的所有类型)***
                //
                val fail =
                    commonDownload(
                        oneDriveCore.convertToKey(
                            listOf(
                                "作者名称",
                                "this",
                                "is",
                                "sample",
                                "path"
                            )
                        ),      //该函数结果与下面的注释相同
                        //"this/is/sample/path",
                        "G://your//save//path",
                        true,
                        0,
                        10,
                        reversed = false,
                        filterContent = { _, onedriveDownloadInfo ->
                            //您可以在此处修改任意信息
                            onedriveDownloadInfo.hasContent = true          //这样即使该文件夹下没有文件也会创建这个文件夹
                            onedriveDownloadInfo.imgInfos.forEach {
                                it.saveRelativePath = ""
                            }    //将一个文件夹内的所有图片下载到这个文件夹下
                            true
                        }) filter@{ index, onedrivePostInfo ->
                        if (onedrivePostInfo.updated) {
                            //只下载有更新的文件
                            val calendar = Calendar.getInstance().apply {
                                set(Calendar.YEAR, 2023)
                                set(Calendar.MONTH, 6)
                                set(Calendar.DATE, 1)
                            }
                            if (onedrivePostInfo.publishedDatetime.after(calendar.time)) {
                                //下载2023年6月1日后创建的文件
                                if (onedrivePostInfo.type == OnedriveFileType.DIRECTORY && onedrivePostInfo.innerFileCount > 2) {
                                    //获取该文件夹下直接文件大于2的所有内容
                                    return@filter true
                                }
                            }
                        }
                        false
                    }
                println("共有以下文件下载失败 \n" + fail.joinToString("\n"))
            }
        }

    }
}

//解压缩一个目录下的压缩文件
fun main() = runBlocking {
    val timeMillis = measureTimeMillis {
        val failed = DeCompressUtil().deCompressAll("G:\\your\\post\\saved\\path") { _, fileName, file ->
            //如果这个文件以password打头，使用这个文件名作为密码  (请按自己的实际来，此处仅为演示)
            if (fileName.startsWith("password")) {
                fileName.replace("password", "")
            } else {
                val pwdFile = file.listFiles()?.filter { it.name.contains("password", true) }?.getOrNull(0)
                pwdFile?.readText() ?: "默认密码"
            }
        }
        println("以下文件解压失败：\n" + failed.joinToString("\n"))
    }
    //passwordSet.forEach{ it.mv }
    println("本次解压共花费时间 $timeMillis ms")
}