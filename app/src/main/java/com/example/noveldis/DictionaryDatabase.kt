package com.example.noveldis

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

// We tell Room about the search index here so it doesn't crash!
@Entity(
    tableName = "entries",
    indices = [Index(value = ["word"], name = "ix_entries_word")]
)
data class DictionaryEntry(
    @PrimaryKey
    @ColumnInfo(name = "word") val word: String,

    @ColumnInfo(name = "wordtype") val wordType: String?,

    @ColumnInfo(name = "definition") val meaning: String?
)

@Dao
interface DictionaryDao {
    @Query("SELECT definition FROM entries WHERE word = :searchWord COLLATE NOCASE LIMIT 1")
    fun getMeaning(searchWord: String): String?
}

@Database(entities = [DictionaryEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
}