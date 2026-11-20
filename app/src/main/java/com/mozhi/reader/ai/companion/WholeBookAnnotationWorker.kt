package com.mozhi.reader.ai.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.MainActivity
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WholeBookAnnotationEntryPoint {
    fun batchAnnotationEngine(): BatchAnnotationEngine
    fun libraryRepository(): LibraryRepository
    fun readerSettingsRepository(): ReaderSettingsRepository
}

/**
 * 后台整本生成段评：从第一章（或指定的章）起一章一章往后写，和随读调度共用整段引擎与账本，
 * 已经写过的章节直接跳过，中断后再开始会接着写。整本生成是用户点了才开始的，不看每日上限，
 * 每章上限、整段参数、提示词都照设置；语音和配图默认不带，要在开始前勾上。
 */
class WholeBookAnnotationWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, WholeBookAnnotationEntryPoint::class.java)
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId <= 0) return Result.failure(workDataOf(KEY_ERROR to text(R.string.whole_book_error_missing_book)))
        val library = entryPoint.libraryRepository()
        val settings = entryPoint.readerSettingsRepository()
        val engine = entryPoint.batchAnnotationEngine()
        return try {
            setForeground(createForegroundInfo(text(R.string.whole_book_preparing), 0, 0))
            library.getBook(bookId)?.takeIf { it.removedAt == 0L }
                ?: return Result.failure(workDataOf(KEY_ERROR to text(R.string.whole_book_error_missing_book)))
            val autonomy = settings.companionAutonomySettings.first()
            val policy = autonomy.policyFor(bookId, settings.activePersonaId.first())
            if (policy.personaIds.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to text(R.string.whole_book_error_no_persona)))
            val chapters = library.getChapters(bookId).map { it.chapterIndex }.sorted()
            val from = inputData.getInt(KEY_FROM_CHAPTER, 0).coerceAtLeast(0)
            val todo = chapters.filter { it >= from }
            val voice = inputData.getBoolean(KEY_VOICE, false)
            val images = inputData.getBoolean(KEY_IMAGES, false)
            val gate = object : BatchGate {
                override suspend fun valid(): Boolean =
                    !isStopped && library.getBook(bookId)?.removedAt == 0L

                override fun stillValid(): Boolean = !isStopped

                override suspend fun allowance(): ProactiveAnnotationAllowance? =
                    if (!valid()) null else ProactiveAnnotationAllowance(
                        accepted = true, maxAnnotations = Int.MAX_VALUE, minAnnotations = policy.limits.minPerChapter,
                        maxVoice = if (voice) Int.MAX_VALUE else 0, maxImages = if (images) Int.MAX_VALUE else 0
                    )
            }
            var created = 0
            val failed = mutableListOf<Int>()
            suspend fun write(index: Int, position: Int, retry: Boolean) {
                val title = library.getChapter(bookId, index)?.title.orEmpty()
                val label = if (title.isBlank()) text(R.string.whole_book_chapter, index + 1)
                else text(R.string.whole_book_chapter_titled, index + 1, title)
                setProgress(workDataOf(KEY_CHAPTER_INDEX to index, KEY_CHAPTER_TITLE to title,
                    KEY_DONE to position, KEY_TOTAL to todo.size, KEY_CREATED to created))
                setForeground(createForegroundInfo(label, position, todo.size))
                val outcome = engine.runChapter(
                    BatchChapterRequest(
                        bookId = bookId, chapterIndex = index,
                        personaIds = annotationPersonaOrder(policy.personaIds, index, policy.interplay),
                        limits = policy.limits, prompts = policy.prompts, templates = policy.templates,
                        voice = voice, images = images, interplay = policy.interplay,
                        ignoreDailyCap = true, retryFailed = retry
                    ),
                    gate
                )
                created += outcome.created.values.sumOf { it.size }
                if (outcome.stopped && !gate.valid()) throw CancellationException("stopped")
                if (outcome.failed) failed += index else failed -= index
            }
            for ((position, index) in todo.withIndex()) write(index, position, retry = true)
            // 失败的章节最后再试一次：多半是一时的网络或限流。
            for (index in failed.toList()) write(index, todo.size - 1, retry = false)
            Result.success(workDataOf(KEY_CREATED to created, KEY_TOTAL to todo.size,
                KEY_FAILED to failed.distinct().map { it + 1 }.joinToString(text(R.string.annotation_list_separator))))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: text(R.string.whole_book_error_generic)).take(300)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(text(R.string.whole_book_preparing), 0, 0)

    private fun text(id: Int, vararg args: Any): String = applicationContext.getString(id, *args)

    private fun createForegroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        ensureNotificationChannel()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (inputData.getLong(KEY_BOOK_ID, 0L) % 1_000).toInt(),
            buildNotification(message, done, total),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun buildNotification(message: String, done: Int, total: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text(R.string.whole_book_notification_title))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total, done.coerceAtMost(total), total <= 0)
            .build()
    }

    private fun ensureNotificationChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, text(R.string.whole_book_notification_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = text(R.string.whole_book_notification_channel_description) }
            )
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_FROM_CHAPTER = "from_chapter"
        const val KEY_VOICE = "voice"
        const val KEY_IMAGES = "images"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CREATED = "created"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "whole_book_annotation"
        private const val NOTIFICATION_ID_BASE = 7_400

        fun uniqueWorkName(bookId: Long) = "whole-book-annotation-$bookId"

        fun enqueue(context: Context, bookId: Long, fromChapter: Int, voice: Boolean, images: Boolean) {
            val request = OneTimeWorkRequestBuilder<WholeBookAnnotationWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId, KEY_FROM_CHAPTER to fromChapter,
                    KEY_VOICE to voice, KEY_IMAGES to images))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(bookId), ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context, bookId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(bookId))
        }
    }
}
