package cool.hin.memox.utils

/**
 * Cached preview data for a web link: the page title and the local path of the
 * downloaded favicon (if any). `null` fields mean "not available / fetch failed".
 */
data class LinkPreview(
    val title: String?,
    val faviconPath: String?,
)
