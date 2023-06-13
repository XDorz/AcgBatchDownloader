open class CommonDownloadInfo<T : CommonFileInfo>(
    var c_title: String,
    var c_imgHref: MutableList<T>,
    var c_fileHref: MutableList<T>,
    var c_hasContent: Boolean,
)