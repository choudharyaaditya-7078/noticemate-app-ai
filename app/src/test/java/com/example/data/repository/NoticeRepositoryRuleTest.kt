package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.SampleNotices
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun saveNotice_authenticatedOwner_createsAndReadsNotice() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = NoticeRepository(firestore)
        val sample = SampleNotices.ALL.first().prebuiltDocument

        val saveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveNotice(sample.copy(id = ""))
        }
        assertTrue("Expected saveNotice to succeed: ${saveResult.exceptionOrNull()}", saveResult.isSuccess)
        val noticeId = saveResult.getOrThrow()
        assertTrue(noticeId.isNotBlank())

        val updateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateChecklistCompletion(noticeId, listOf(0L, 1L))
        }
        assertTrue("Expected updateChecklistCompletion to succeed", updateResult.isSuccess)

        val fetchedResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getNoticeById(aliceUid, noticeId)
        }
        assertTrue(fetchedResult.isSuccess)
        val fetched = fetchedResult.getOrThrow()
        assertNotNull(fetched)
        assertEquals(listOf(0L, 1L), fetched?.completedIndices)
    }

    @Test
    fun observeNotices_authenticatedOwner_emitsRealtimeUpdates() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = NoticeRepository(firestore)
        val sample = SampleNotices.ALL.first().prebuiltDocument

        val noticeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveNotice(sample.copy(id = "")).getOrThrow()
        }

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeNotices(aliceUid).first { list -> list.any { it.id == noticeId } }
        }
        assertTrue(emitted.any { it.id == noticeId })
    }

    @Test
    fun getNoticeById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = NoticeRepository(firestore)
        val sample = SampleNotices.ALL.first().prebuiltDocument
        val noticeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveNotice(sample.copy(id = "")).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobFetchResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getNoticeById(aliceUid, noticeId)
        }
        assertTrue("Expected cross-user read to fail", bobFetchResult.isFailure)
        val firestoreEx = bobFetchResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun observeNotices_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = NoticeRepository(firestore)

        var caughtException: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeNotices(aliceUid).first()
            }
        } catch (e: Throwable) {
            caughtException = e
        }
        assertNotNull("Expected unauthenticated observeNotices to throw", caughtException)
        val firestoreEx = caughtException?.unwrapFirestoreException()
        assertNotNull("Expected nested FirebaseFirestoreException, got $caughtException", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@noticemate.test"
        const val BOB_EMAIL = "bob@noticemate.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
