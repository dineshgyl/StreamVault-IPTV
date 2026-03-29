package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.ProgramBrowseEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.domain.model.Program
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryImplTest {

    private val programDao: ProgramDao = mock()
    private val xmltvParser: XmltvParser = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun `searchPrograms ranks exact matches ahead of loose matches`() = runTest {
        whenever(programDao.searchPrograms(any(), any(), any(), any(), anyOrNull(), any())).thenReturn(
            flowOf(
                listOf(
                    ProgramBrowseEntity(
                        id = 1L,
                        providerId = 7L,
                        channelId = "one",
                        title = "Late Sports Replay",
                        startTime = 10L,
                        endTime = 20L
                    ),
                    ProgramBrowseEntity(
                        id = 2L,
                        providerId = 7L,
                        channelId = "two",
                        title = "Sports",
                        startTime = 30L,
                        endTime = 40L
                    )
                )
            )
        )

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner
        )

        val result = repository.searchPrograms(7L, "sports", 0L, 100L).first()

        assertThat(result.map { it.title }).containsExactly("Sports", "Late Sports Replay").inOrder()
    }

    @Test
    fun `refreshEpg serializes concurrent syncs for same provider`() = runTest {
        val firstParserEntered = CompletableDeferred<Unit>()
        val releaseFirstParser = CompletableDeferred<Unit>()
        val secondParserEntered = CompletableDeferred<Unit>()
        val parserCallOrder = AtomicInteger(0)
        val activeParsers = AtomicInteger(0)
        val maxConcurrentParsers = AtomicInteger(0)

        whenever(xmltvParser.parseStreaming(any(), any())).thenAnswer { invocation ->
            val activeCount = activeParsers.incrementAndGet()
            maxConcurrentParsers.updateAndGet { current -> maxOf(current, activeCount) }
            val callIndex = parserCallOrder.incrementAndGet()

            try {
                if (callIndex == 1) {
                    firstParserEntered.complete(Unit)
                    runBlocking { releaseFirstParser.await() }
                } else {
                    secondParserEntered.complete(Unit)
                }

                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(1)
                runBlocking {
                    onProgram(
                        Program(
                            providerId = 7L,
                            channelId = "channel-1",
                            title = "News",
                            description = "",
                            startTime = 1_735_722_000_000L,
                            endTime = 1_735_725_600_000L,
                            lang = "en"
                        )
                    )
                }
            } finally {
                activeParsers.decrementAndGet()
            }
        }

        val repository = EpgRepositoryImpl(
            programDao = programDao,
            xmltvParser = xmltvParser,
            okHttpClient = okHttpClientReturningXml(),
            transactionRunner = transactionRunner
        )

        val firstRefresh = async { repository.refreshEpg(7L, "https://example.com/epg.xml") }
        firstParserEntered.await()

        val secondRefresh = async { repository.refreshEpg(7L, "https://example.com/epg.xml") }
        delay(100)

        assertThat(secondParserEntered.isCompleted).isFalse()

        releaseFirstParser.complete(Unit)

        assertThat(firstRefresh.await().isSuccess).isTrue()
        assertThat(secondRefresh.await().isSuccess).isTrue()
        assertThat(maxConcurrentParsers.get()).isEqualTo(1)

        val insertedPrograms = argumentCaptor<List<ProgramEntity>>()
        verify(programDao, atLeastOnce()).insertAll(insertedPrograms.capture())
        assertThat(insertedPrograms.allValues.flatten()).isNotEmpty()
        verify(programDao, times(2)).moveToProvider(-7L, 7L)
    }

    private fun okHttpClientReturningXml(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<tv></tv>".toResponseBody("application/xml".toMediaType()))
                    .build()
            }
            .build()
}
