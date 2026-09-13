package com.bookshelf.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Embedded
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "editions",
    indices = [Index(value = ["isbn13"], unique = true)]
)
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val isbn13: String,
    val isbn10: String? = null,
    val title: String,
    val subtitle: String? = null,
    val authors: String = "",
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val pages: Int? = null,
    val publicationType: String = "BOOK",
    val categories: String = "",
    val description: String? = null,
    val coverLocalPath: String? = null,
    val coverRemoteUrl: String? = null,
    val metadataSource: String = "manual",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "book_copies",
    foreignKeys = [
        ForeignKey(
            entity = EditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["editionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("editionId")]
)
data class BookCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionId: Long,
    val condition: String = "NOT_SET",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

data class BookWithEdition(
    @Embedded val copy: BookCopyEntity,
    @Relation(
        parentColumn = "editionId",
        entityColumn = "id"
    )
    val edition: EditionEntity
)

data class BookSnapshotRow(
    val copyId: Long,
    val editionId: Long,
    val isbn13: String,
    val isbn10: String?,
    val title: String,
    val subtitle: String?,
    val authors: String,
    val publisher: String?,
    val publishedYear: Int?,
    val pages: Int?,
    val publicationType: String,
    val categories: String,
    val description: String?,
    val coverLocalPath: String?,
    val coverRemoteUrl: String?,
    val metadataSource: String,
    val condition: String,
    val notes: String,
    val addedAt: Long
)

@Dao
interface BookDao {
    @Transaction
    @Query("SELECT * FROM book_copies ORDER BY addedAt DESC")
    fun observeShelf(): Flow<List<BookWithEdition>>

    @Transaction
    @Query("SELECT * FROM book_copies WHERE id = :copyId LIMIT 1")
    suspend fun getBook(copyId: Long): BookWithEdition?

    @Query("SELECT * FROM editions WHERE isbn13 = :isbn LIMIT 1")
    suspend fun getEditionByIsbn(isbn: String): EditionEntity?

    @Query("SELECT COUNT(*) FROM book_copies WHERE editionId = :editionId")
    suspend fun getCopyCount(editionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEdition(edition: EditionEntity): Long

    @Update
    suspend fun updateEdition(edition: EditionEntity)

    @Insert
    suspend fun insertCopy(copy: BookCopyEntity): Long

    @Update
    suspend fun updateCopy(copy: BookCopyEntity)

    @Delete
    suspend fun deleteCopy(copy: BookCopyEntity)

    @Query("DELETE FROM book_copies")
    suspend fun clearCopies()

    @Query("DELETE FROM editions")
    suspend fun clearEditions()

    @Query(
        """
        SELECT
            c.id AS copyId,
            e.id AS editionId,
            e.isbn13 AS isbn13,
            e.isbn10 AS isbn10,
            e.title AS title,
            e.subtitle AS subtitle,
            e.authors AS authors,
            e.publisher AS publisher,
            e.publishedYear AS publishedYear,
            e.pages AS pages,
            e.publicationType AS publicationType,
            e.categories AS categories,
            e.description AS description,
            e.coverLocalPath AS coverLocalPath,
            e.coverRemoteUrl AS coverRemoteUrl,
            e.metadataSource AS metadataSource,
            c.condition AS condition,
            c.notes AS notes,
            c.addedAt AS addedAt
        FROM book_copies c
        INNER JOIN editions e ON e.id = c.editionId
        ORDER BY c.addedAt DESC
        """
    )
    suspend fun snapshot(): List<BookSnapshotRow>
}

@Database(
    entities = [EditionEntity::class, BookCopyEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BookShelfDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: BookShelfDatabase? = null

        fun get(context: Context): BookShelfDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookShelfDatabase::class.java,
                    "bookshelf.db"
                ).build().also { INSTANCE = it }
            }
    }
}
