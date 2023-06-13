import java.util.*

open class CommonPostInfo(
    val c_postId: String,
    val c_title: String,
    val c_priceRequire: Int,
    val c_publishedDatetime: Date,
    val c_restricted: Boolean,
    val c_access: Boolean
)