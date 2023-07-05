import kotlinx.coroutines.runBlocking
import util.CompressUtil
import util.RequestUtil
import util.core.CI_ENCore
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

    companion object {
        //fanbox下载
        fun main() {
            CommonArticleDownloader(fanboxCore).apply {
                // 当你点开pixiv的某个作者主页的赞助时，能看到浏览器的url链接
                // https://XXXXXX.fanbox.cc/?utm_campaign=www_profile&utm_medium=site_flow&utm_source=pixiv
                //或者 https://www.fanbox.cc/@XXXXX
                //此处的域名的XXXXXX就是我们所需要的key
                //第三个参数表示不进行下载，我们在此处只进行作品统计
                commonDownload("XXXXXX", "G://your//save//path", false, filterFile = { _, _ ->
                    accumulator("postNum")
                    true
                }) { _, fanboxPostInfo ->
                    fanboxPostInfo.adult        //lambada会将这个表达式的值返回
                }
                println("该作者的成人作品投稿有 ${getAcc("postNum")} 个")
                accClear()
            }
        }

        //TODO("添加onedrive的demo")

        //ci-en的下载
//        fun main() {
//            CommonArticleDownloader(cienCore).apply {
//                //此处的14266为用户id  例如你浏览一个用户，你可以看到浏览器URL为
//                //https://ci-en.dlsite.com/creator/14266
//                //此时creator后面的那个数字(即14266)就是用户id
//                //这里指定start，end表示下载第0-10页，但是reversed表示逆转列表，所以是下载第max-10 到 max页，也就是最早的10页作品
//                val fail =
//                    commonDownload(
//                        "14266",
//                        "G://your//save//path",
//                        true,
//                        0,
//                        10,
//                        0,
//                        false,
//                        filterFile = { _, cienDownloadInfo ->
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

    }
}

//解压文件
fun main() = runBlocking {
    val timeMillis = measureTimeMillis {
        val failed = CompressUtil().compressAll("G:\\your\\post\\saved\\path") { _, fileName, file ->
            //如果这个文件以password打头，使用这个文件名作为密码  (请按自己的实际来，此处仅为演示)
            if (fileName.startsWith("password")) {
                fileName.replace("password", "")
            } else {
                val pwdFile = file.listFiles().filter { it.name.contains("password", true) }.getOrNull(0)
                pwdFile?.readText() ?: "默认密码"
            }
        }
        println("以下文件解压失败：\n" + failed.joinToString("\n"))
    }
    //passwordSet.forEach{ it.mv }
    println("本次解压共花费时间 $timeMillis ms")
}