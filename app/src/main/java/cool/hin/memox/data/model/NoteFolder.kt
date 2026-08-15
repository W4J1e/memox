package cool.hin.memox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** User-defined folder. A note belongs to at most one folder (or none, folderId = ""). */
@Entity class NoteFolder(@PrimaryKey val name: String, val order: Int)
