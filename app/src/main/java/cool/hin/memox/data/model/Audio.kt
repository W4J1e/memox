package cool.hin.memox.data.model

import kotlinx.parcelize.Parcelize

@Parcelize
data class Audio(var name: String, var duration: Long?, var timestamp: Long) : Attachment
