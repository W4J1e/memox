package cool.hin.memox.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.dao.BaseNoteDao
import cool.hin.memox.data.dao.BaseNoteDao.Companion.MAX_BODY_CHAR_LENGTH
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ColorString
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.NoteViewMode
import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NoteRepairUtilsDbTest {

    private lateinit var db: MemoXDatabase
    private lateinit var dao: BaseNoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, MemoXDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.getBaseNoteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun oversizedNote(): BaseNote {
        val body = "x".repeat(MAX_BODY_CHAR_LENGTH + 50)
        // Span goes beyond the max and should be clipped to MAX after repair
        val spans = listOf(SpanRepresentation(10, MAX_BODY_CHAR_LENGTH + 20, bold = true))
        return BaseNote(
            id = 0,
            type = Type.NOTE,
            folder = Folder.NOTES,
            color = "DEFAULT" as ColorString,
            title = "Oversized",
            pinned = false,
            timestamp = 0L,
            modifiedTimestamp = 0L,
            labels = emptyList(),
            body = body,
            spans = spans,
            items = emptyList<ListItem>(),
            images = emptyList<FileAttachment>(),
            files = emptyList<FileAttachment>(),
            audios = emptyList(),
            reminders = emptyList<Reminder>(),
            viewMode = NoteViewMode.EDIT,
            isPinnedToStatus = false,
        )
    }

    @Test
    fun truncateBodyAndFixSpans_clipsBodyAndSpans() = runBlocking {
        val id = dao.insert(oversizedNote())

        // Sanity: inserted body is oversized in this in-memory DB context
        var before = dao.get(id)!!
        assertTrue(before.body.length > MAX_BODY_CHAR_LENGTH)
        assertTrue(before.spans.first().end > MAX_BODY_CHAR_LENGTH)

        NoteRepairUtils.truncateBodyAndFixSpans(dao, id)

        val after = dao.get(id)!!
        assertEquals(MAX_BODY_CHAR_LENGTH, after.body.length)
        // Span should be clipped to end of body
        val span = after.spans.first()
        assertEquals(MAX_BODY_CHAR_LENGTH, span.end)
        assertEquals(10, span.start)
    }
}
