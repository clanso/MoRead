package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 整段段评的阅读记忆缓存：章节（或长章里一截）的回顾、几章合并的前情提要。
 * 只由正文推出、与角色无关；[sourceRevision] 对不上就当作没有，重新写。删书级联清理。
 */
@Entity(
    tableName = "annotation_memories",
    primaryKeys = ["bookId", "cacheKey"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class AnnotationMemoryEntity(
    val bookId: Long,
    val cacheKey: String,
    val sourceRevision: String,
    val content: String,
    val createdAt: Long
)
