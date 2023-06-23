open class CommonDownloadInfo<T : CommonFileInfo>(
    var _ctitle: String,
    var _cimgHref: MutableList<T>,
    var _cfileHref: MutableList<T>,
    var _chasContent: Boolean,
)