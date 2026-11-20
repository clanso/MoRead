package com.mozhi.reader.feature.bookdetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mozhi.reader.R
import com.mozhi.reader.ai.companion.AnnotationPackService
import com.mozhi.reader.ai.companion.WholeBookAnnotationWorker
import com.mozhi.reader.ai.companion.policyFor
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.i18n.UiText
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.ui.requireBookId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 整本段评这一项在详情页上的样子。 */
data class WholeBookAnnotationState(
    val phase: Phase = Phase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    /** 正在写的章（-1 = 还在准备）和章名。 */
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val created: Int = 0,
    /** 上一次跑完（或失败、停止）时的一句话。 */
    val result: UiText? = null,
    val packBusy: Boolean = false
) {
    enum class Phase { IDLE, QUEUED, RUNNING }

    val active: Boolean get() = phase != Phase.IDLE
}

/** 开始整本生成之前给用户看的估算。 */
data class WholeBookEstimate(
    val chapters: Int,
    val chars: Long,
    val personas: Int,
    val requests: Long,
    val fromChapter: Int,
    val voiceAvailable: Boolean,
    val imagesAvailable: Boolean
)

/**
 * 详情页「整本段评」：后台整本生成（[WholeBookAnnotationWorker]）的开始、停止和进度，
 * 以及批注包的导出 / 导入（[AnnotationPackService]）。
 */
@HiltViewModel
class BookAnnotationToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val settings: ReaderSettingsRepository,
    private val packs: AnnotationPackService
) : ViewModel() {
    private val bookId: Long = savedStateHandle.requireBookId()
    private val mutableState = MutableStateFlow(WholeBookAnnotationState())
    val state = mutableState.asStateFlow()
    private val messages = Channel<UiText>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val workManager = WorkManager.getInstance(context)

    init {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(WholeBookAnnotationWorker.uniqueWorkName(bookId))
                .collectLatest { infos -> infos.lastOrNull()?.let(::apply) }
        }
    }

    private fun apply(info: WorkInfo) {
        val data = if (info.state.isFinished) info.outputData else info.progress
        val created = data.getInt(WholeBookAnnotationWorker.KEY_CREATED, 0)
        mutableState.update { current ->
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> current.copy(phase = WholeBookAnnotationState.Phase.QUEUED)
                WorkInfo.State.RUNNING -> current.copy(
                    phase = WholeBookAnnotationState.Phase.RUNNING,
                    done = data.getInt(WholeBookAnnotationWorker.KEY_DONE, 0),
                    total = data.getInt(WholeBookAnnotationWorker.KEY_TOTAL, 0),
                    chapterIndex = data.getInt(WholeBookAnnotationWorker.KEY_CHAPTER_INDEX, -1),
                    chapterTitle = data.getString(WholeBookAnnotationWorker.KEY_CHAPTER_TITLE).orEmpty(),
                    created = created
                )
                WorkInfo.State.SUCCEEDED -> {
                    val failed = data.getString(WholeBookAnnotationWorker.KEY_FAILED).orEmpty()
                    current.copy(phase = WholeBookAnnotationState.Phase.IDLE, created = created,
                        result = if (failed.isBlank()) UiText.of(R.string.whole_book_done, created)
                        else UiText.of(R.string.whole_book_done_failed, created, failed))
                }
                WorkInfo.State.FAILED -> current.copy(phase = WholeBookAnnotationState.Phase.IDLE,
                    result = UiText.of(R.string.whole_book_failed,
                        data.getString(WholeBookAnnotationWorker.KEY_ERROR)?.let { UiText.raw(it) } ?: UiText.of(R.string.whole_book_failed_unknown)))
                WorkInfo.State.CANCELLED -> current.copy(phase = WholeBookAnnotationState.Phase.IDLE,
                    result = UiText.of(R.string.whole_book_cancelled))
            }
        }
    }

    /** 估算从 [fromCurrent]（当前读到的章）或第一章起要发多少次请求。 */
    suspend fun estimate(fromCurrent: Boolean): WholeBookEstimate = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId)
        val autonomy = settings.companionAutonomySettings.first()
        val policy = autonomy.policyFor(bookId, settings.activePersonaId.first())
        val from = if (fromCurrent) book?.lastReadChapterIndex ?: 0 else 0
        val chapters = library.getChapters(bookId).filter { it.chapterIndex >= from }
        val chars = chapters.sumOf { it.charCount.toLong() }
        val batch = policy.limits.batch
        val chunks = chapters.sumOf { chapter ->
            ((chapter.charCount + batch.chunkChars - 1) / batch.chunkChars.coerceAtLeast(1)).coerceAtLeast(1).toLong()
        }
        val perChunk = if (batch.proofread) 2 else 1
        WholeBookEstimate(
            chapters = chapters.size, chars = chars, personas = policy.personaIds.size,
            requests = chunks * policy.personaIds.size.coerceAtLeast(1) * perChunk,
            fromChapter = from, voiceAvailable = policy.voice, imagesAvailable = policy.images
        )
    }

    fun start(fromChapter: Int, voice: Boolean, images: Boolean) {
        viewModelScope.launch {
            val autonomy = settings.companionAutonomySettings.first()
            if (autonomy.policyFor(bookId, settings.activePersonaId.first()).personaIds.isEmpty()) {
                messages.send(UiText.of(R.string.whole_book_no_persona))
                return@launch
            }
            WholeBookAnnotationWorker.enqueue(context, bookId, fromChapter, voice, images)
            messages.send(UiText.of(R.string.whole_book_started))
        }
    }

    fun stop() {
        WholeBookAnnotationWorker.stop(context, bookId)
    }

    fun suggestedPackName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "book" } + ".moread-notes.json"

    fun exportPack(uri: Uri?) {
        uri ?: return
        runPack {
            val count = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { packs.export(bookId, it) }
                    ?: error(context.getString(R.string.annotation_pack_unwritable))
            }
            UiText.plural(R.plurals.annotation_pack_exported, count)
        }
    }

    fun importPack(uri: Uri?) {
        uri ?: return
        runPack {
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { packs.import(bookId, it) }
                    ?: error(context.getString(R.string.annotation_pack_unreadable))
            }
            // 几段拼成一句，只在这一刻显示，按当前语言解析。
            val resources = context.resources
            UiText.raw(buildString {
                append(resources.getQuantityString(R.plurals.annotation_pack_imported, result.imported, result.imported))
                if (result.replies > 0) append(resources.getString(R.string.annotation_pack_imported_replies, result.replies))
                if (result.duplicates > 0) append(resources.getString(R.string.annotation_pack_duplicates, result.duplicates))
                if (result.skipped > 0) append(resources.getString(R.string.annotation_pack_skipped, result.skipped))
                if (result.newPersonas.isNotEmpty()) append(resources.getString(R.string.annotation_pack_new_personas,
                    result.newPersonas.joinToString(resources.getString(R.string.annotation_list_separator))))
            })
        }
    }

    private fun runPack(block: suspend () -> UiText) {
        if (mutableState.value.packBusy) return
        mutableState.update { it.copy(packBusy = true) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                UiText.of(R.string.annotation_pack_failed, error.message ?: error.javaClass.simpleName)
            } finally {
                mutableState.update { it.copy(packBusy = false) }
            }
            messages.send(message)
        }
    }
}
