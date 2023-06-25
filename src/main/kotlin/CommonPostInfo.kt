import java.util.*

/**
 * 通用投稿信息表示模型
 * 该模型表示一个投稿的绝大部分通用信息，该模型为通用下载器CommonArticleDownload服务，并可在相应的filter中被操作
 *
 * 为该模型的属性加上 `_c` 前缀是因为让其子类可以使用data关键词修饰而不会导致名称冲突
 * 该模型是不可修改模型，也没有修改必要，因为该模型仅表示一个投稿的信息
 *
 *
 * @property _cpostId                   投稿唯一id
 * @property _ctitle                    投稿的名称
 * @property _cpriceRequire             查看该投稿所需的赞助费用(该属性在不同core中可能有不同解释，如该属性在onedriveCore中没有用)
 * @property _cpublishedDatetime        投稿时间
 * @property _crestricted               该投稿的获取是否是受限制的(即不能获取到该投稿的全部内容，大部分情况下是因为账号的赞助等级限制)
 * @property _caccess                   该投稿是否有内容可以被获取(即该投稿是否至少有一项是当前等级可获取的内容)
 */
open class CommonPostInfo(
    val _cpostId: String,
    val _ctitle: String,
    val _cpriceRequire: Int,
    val _cpublishedDatetime: Date,
    val _crestricted: Boolean,
    val _caccess: Boolean
)