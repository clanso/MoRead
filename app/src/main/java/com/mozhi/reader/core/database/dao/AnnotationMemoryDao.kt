package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mozhi.reader.core.database.entity.AnnotationMemoryEntity

@Dao
interface AnnotationMemoryDao {
    @Query("SELECT * FROM annotation_memories WHERE bookId = :bookId AND cacheKey = :cacheKey LIMIT 1")
    suspend fun get(bookId: Long, cacheKey: String): AnnotationMemoryEntity?

    @Query("SELECT * FROM annotation_memories WHERE bookId = :bookId")
    suspend fun getAll(bookId: Long): List<AnnotationMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AnnotationMemoryEntity)

    @Query("DELETE FROM annotation_memories WHERE bookId = :bookId")
    suspend fun clear(bookId: Long)
}
