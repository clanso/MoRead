package com.mozhi.reader.ai.companion

import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.library.AnnotationMedia
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class ProactiveAnnotationCommit(val annotationId: Long, val job: ProactiveAnnotationJobEntity)

/** [rows] 是落了库的批注在请求里的位置；[replies] 是接上了话的父批注。 */
internal data class BatchCommit(
    val annotationIds: List<Long>,
    val rows: List<Int>,
    val replies: List<Long>,
    val job: ProactiveAnnotationJobEntity
)

/** A short DB-only transaction. All source IO, SHA and DataStore checks happen in the caller. */
internal class ProactiveAnnotationCommitStore(private val database: MoReadDatabase) {
    private class ContextExpired : RuntimeException()

    /** 接话和段落账本一起落库，事务内重查父段评与角色回复，防止生成期间被删除或被抢先接话。 */
    suspend fun commitReply(
        peer: ProactivePeerNote,
        text: String,
        updatedJob: ProactiveAnnotationJobEntity,
        now: Long,
        contextValid: () -> Boolean
    ): ProactiveAnnotationCommit? = withContext(NonCancellable) {
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                val dao = database.annotationDao()
                val parent = dao.getAnnotation(peer.annotationId) ?: return@withTransaction null
                if (parent.bookId != updatedJob.bookId || parent.chapterIndex != updatedJob.chapterIndex ||
                    dao.getReplies(peer.annotationId).any { it.personaId != null }) return@withTransaction null
                val jobs = database.proactiveAnnotationJobDao()
                if (jobs.find(updatedJob.bookId, updatedJob.chapterIndex, updatedJob.personaId,
                        updatedJob.sourceRevision)?.id != updatedJob.id) return@withTransaction null
                dao.insertReply(AnnotationReplyEntity(
                    annotationId = peer.annotationId, personaId = updatedJob.personaId, replyToId = null,
                    contentMarkdown = text, createdAt = now
                ))
                jobs.update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                ProactiveAnnotationCommit(peer.annotationId, updatedJob)
            }
        } catch (_: ContextExpired) { null }
    }

    /** 跳过也要拿到取消安全的落库回执，内存账本才不会与数据库分叉。 */
    suspend fun skip(
        updatedJob: ProactiveAnnotationJobEntity, contextValid: () -> Boolean
    ): ProactiveAnnotationJobEntity? = withContext(NonCancellable) {
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                val jobs = database.proactiveAnnotationJobDao()
                if (jobs.find(updatedJob.bookId, updatedJob.chapterIndex, updatedJob.personaId,
                        updatedJob.sourceRevision)?.id != updatedJob.id) return@withTransaction null
                jobs.update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                updatedJob
            }
        } catch (_: ContextExpired) { null }
    }

    /**
     * 整段模式：一段正文里写下的全部批注、接话和这一段的账本一起落库。事务里重查每日上限，
     * 放不下的批注不落（[BatchCommit.rows] 只列出落了的那几条在 [rows] 里的位置，调用方删掉其余配图）；
     * 接话照逐段模式的规矩，父批注已被删除、已有角色接过话的跳过。
     */
    suspend fun commitBatch(
        rows: List<Pair<AnnotationEntity, IllustrationEntity?>>,
        replies: List<Pair<Long, String>>,
        updatedJob: ProactiveAnnotationJobEntity,
        dailyCap: Int,
        startOfDay: Long,
        now: Long,
        contextValid: () -> Boolean
    ): BatchCommit? = withContext(NonCancellable) {
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                val jobs = database.proactiveAnnotationJobDao()
                if (jobs.find(updatedJob.bookId, updatedJob.chapterIndex, updatedJob.personaId,
                        updatedJob.sourceRevision)?.id != updatedJob.id) return@withTransaction null
                val room = if (dailyCap == Int.MAX_VALUE) Int.MAX_VALUE
                else (dailyCap - jobs.createdSince(startOfDay)).coerceAtLeast(0)
                val dao = database.annotationDao()
                val committed = mutableListOf<Int>()
                val ids = mutableListOf<Long>()
                for ((index, pair) in rows.withIndex()) {
                    if (committed.size >= room) break
                    val (row, illustration) = pair
                    val media = AnnotationMedia.decode(row.mediaJson)
                    val imageId = illustration?.let { database.illustrationDao().insert(it) }
                    ids += dao.insert(row.copy(proactiveJobId = updatedJob.id, mediaJson = media.copy(illustrationId = imageId).encode()))
                    committed += index
                }
                val replied = mutableListOf<Long>()
                for ((parentId, text) in replies) {
                    if (committed.size + replied.size >= room) break
                    val parent = dao.getAnnotation(parentId) ?: continue
                    if (parent.bookId != updatedJob.bookId || parent.chapterIndex != updatedJob.chapterIndex ||
                        dao.getReplies(parentId).any { it.personaId != null }) continue
                    dao.insertReply(AnnotationReplyEntity(
                        annotationId = parentId, personaId = updatedJob.personaId, replyToId = null,
                        contentMarkdown = text, createdAt = now
                    ))
                    replied += parentId
                }
                jobs.update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                BatchCommit(ids, committed, replied, updatedJob)
            }
        } catch (_: ContextExpired) { null }
    }

    /**
     * null/throw ALWAYS means no commit; a returned ID ALWAYS means committed.
     * NonCancellable spans just SQLite so cancellation cannot lose the commit acknowledgement
     * and make the service delete an image that is already referenced by a published row.
     */
    suspend fun commit(
        row: AnnotationEntity,
        updatedJob: ProactiveAnnotationJobEntity,
        illustration: IllustrationEntity?,
        dailyCap: Int,
        startOfDay: Long,
        contextValid: () -> Boolean
    ): ProactiveAnnotationCommit? = withContext(NonCancellable) {
        val media = AnnotationMedia.decode(row.mediaJson)
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                if (database.proactiveAnnotationJobDao().createdSince(startOfDay) >= dailyCap) return@withTransaction null
                val imageId = illustration?.let { database.illustrationDao().insert(it) }
                val id = database.annotationDao().insert(row.copy(
                    proactiveJobId = updatedJob.id, mediaJson = media.copy(illustrationId = imageId).encode()))
                database.proactiveAnnotationJobDao().update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                ProactiveAnnotationCommit(id, updatedJob)
            }
        } catch (_: ContextExpired) { null }
    }
}
