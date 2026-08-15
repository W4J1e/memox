package cool.hin.memox.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cool.hin.memox.data.model.NoteFolder

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(folder: NoteFolder)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(folders: List<NoteFolder>)

    @Update suspend fun update(folder: NoteFolder)

    @Update suspend fun update(folders: List<NoteFolder>)

    @Query("DELETE FROM NoteFolder WHERE name = :name") suspend fun delete(name: String)

    @Query("DELETE FROM NoteFolder") suspend fun deleteAll()

    @Query("UPDATE NoteFolder SET name = :newName WHERE name = :oldName")
    suspend fun update(oldName: String, newName: String)

    @Query("SELECT * FROM NoteFolder ORDER BY `order` DESC, name ASC")
    fun getAll(): LiveData<List<NoteFolder>>

    @Query("SELECT name FROM NoteFolder ORDER BY `order` DESC, name ASC")
    suspend fun getArrayOfAll(): Array<String>

    @Query("SELECT EXISTS(SELECT 1 FROM NoteFolder WHERE name = :name)")
    suspend fun exists(name: String): Boolean

    @Query("SELECT MAX(`order`) FROM NoteFolder") suspend fun getMaxOrder(): Int?

    /**
     * 方案 B（同步自动补齐文件夹）：导入笔记时，若笔记携带的 folderId 在本地文件夹表中不存在，
     * 自动创建一个同名文件夹（order 排到末尾）。folderId 为空或已存在则跳过。
     */
    suspend fun ensureFolder(folderId: String) {
        if (folderId.isNotBlank() && !exists(folderId)) {
            val nextOrder = (getMaxOrder() ?: 0) + 1
            insert(NoteFolder(name = folderId, order = nextOrder))
        }
    }
}
