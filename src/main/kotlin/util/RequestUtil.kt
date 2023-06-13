package util

import cn.hutool.http.HttpRequest
import cn.hutool.http.HttpUtil
import java.text.MessageFormat

class RequestUtil {

    var cookie: String? = null
    var referer: String? = null
    var origin: String? = null
    var userAgent: String? = null

    var proxyAddr: String? = null
    var proxyPort: Int? = null
    var allowRedirect: Boolean = true
    var headers: MutableMap<String,MutableList<String>>? = null

    val normalUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"


    fun genericGet(url: String): HttpRequest {
        return HttpUtil.createGet(url).config();
    }

    private fun genericProxyGet(url: String): HttpRequest{
        return HttpUtil.createGet(url).apply {
            proxyAddr?.let { addr ->
                proxyPort?.let { port ->
                    setHttpProxy(addr,port)
                }
            }
        }
    }

    fun genericPost(url: String): HttpRequest {
        return HttpUtil.createPost(url).config();
    }

    fun getBody(url: String): String {
        return genericGet(url).execute().body()
    }

    fun proxyGetBody(url: String): String{
        return genericProxyGet(url).execute().body()
    }

    fun postBody(url: String): String {
        return genericPost(url).execute().body()
    }

    //核心方法，为请求附带必要条件   cookie，代理等
    private fun HttpRequest.config(): HttpRequest {
        setFollowRedirects(allowRedirect)
        cookie?.let { cookie(it) }
        referer?.let { header("Referer", it) }
        origin?.let { header("Origin", it) }
        userAgent?.let { header("User-Agent", it) }
        proxyAddr?.let { addr ->
            proxyPort?.let { port ->
                setHttpProxy(addr, port)
            }
        }
        headers?.let {
            header(it, true)
        }
        return this
    }
}