/**
 * 通用投稿内容展示模型
 * 该模型展示一个投稿内的所有可下载内容，该模型为通用下载器CommonArticleDownload服务，并可在相应的filter中被操作
 *
 * @param T                         CommonDownloadInfo中容纳的文件下载信息表示模型，它一定是CommonFileInfo或其子类
 * @property title                  文件名称，如果无法抓取到它的名称，或其名称只是一个randomID的话则会使用index.extension的方式为其命名
 * @property imgInfos               图片文件的下载信息表示
 * @property fileInfos              文件的下载信息表示模型
 * @property hasContent             是否有抓取到内容，通常这个值与imgInfos和fileInfos的值有关
 * @property extractFileSavePath    额外下载的东西的保存的相对路径
 *
 * ***所有对该类中属性的更改均可提现在下载结果中***
 */
open class CommonDownloadInfo<T : CommonFileInfo>(
    var title: String,
    var imgInfos: MutableList<T>,
    var fileInfos: MutableList<T>,
    var hasContent: Boolean,
    var extractFileSavePath: String?,
)