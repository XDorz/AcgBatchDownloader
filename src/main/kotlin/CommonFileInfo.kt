/**
 * 通用文件下载信息表示模型
 * 该模型表示一个文件/图片的必要下载和保存信息，该模型为通用下载器CommonArticleDownload服务
 *
 * 受限于kotlin的自动生成getter和setter机制，无法为主构造函数中声明的属性覆盖getter和setter逻辑
 * 所以无法同步子类和父类中使用值引用的属性的变更，如Int，String等
 * 故该类的子类将使用普通类，直接使用父类的属性，不会声明与父类类似的属性
 *
 * @property name                       文件的本地保存名称
 * @property href                       文件的下载直链
 * @property extension                  文件的拓展名
 * @property saveRelativePath           文件的相对保存路径，example：
 *                                      当saveRelativePath指定为`foo/bar`
 *                                      在通用下载器中指定保存路径为`C:/example`
 *                                      投稿的名称为`title-0.0`
 *                                      name属性为`filename.png`的情况下
 *                                      文件最后的路径应该在`C:/example/X_title-0.0/foo/bar/filename.png`下,其中X为作品的index
 */
open class CommonFileInfo(
    var name: String,
    val href: String,
    val extension: String,
    _csaveRelativePath: String,
) {
    //在类中声明该属性是为了覆盖默认setter方法
    //该变量旨在默认值被修改时设置 默认值值被修改 标记位
    //并且在通用下载中根据_chasPathChanged属性来确定是否要为该图片启用名称防冲突算法
    var saveRelativePath: String = _csaveRelativePath
        set(value) {
            if (field != value) {
                field = value
                _chasPathChanged = true
            }
        }
    var _chasPathChanged: Boolean = false
}