package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.NoticeDocument
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class NoticeRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeNotices(userId: String = requireUserId()): Flow<List<NoticeDocument>> = flow {
        val path = "users/$userId/notices"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("notices")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { querySnapshot ->
                    querySnapshot.toObjects(
                        NoticeDocument::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserNotices(userId: String = requireUserId()): Result<List<NoticeDocument>> {
        val path = "users/$userId/notices"
        return try {
            val snapshot = db.collection("users")
                .document(userId)
                .collection("notices")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val list = snapshot.toObjects(
                NoticeDocument::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getNoticeById(ownerUserId: String, noticeId: String): Result<NoticeDocument?> {
        val path = "users/$ownerUserId/notices/$noticeId"
        return try {
            val doc = db.collection("users")
                .document(ownerUserId)
                .collection("notices")
                .document(noticeId)
                .get()
                .await()
            Result.success(
                doc.toObject(
                    NoticeDocument::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun saveNotice(notice: NoticeDocument): Result<String> {
        val uid = requireUserId()
        val docId = notice.id.takeIf { it.isNotBlank() } ?: "notice_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("users")
            .document(uid)
            .collection("notices")
            .document(docId)

        val payload = mapOf(
            "userId" to uid,
            "title" to notice.title.ifBlank { "Campus Notice" }.take(300),
            "rawNoticeText" to notice.rawNoticeText.ifBlank { "Notice text" }.take(15000),
            "purposeEn" to notice.purposeEn.ifBlank { "Summary not specified" }.take(2000),
            "purposeHi" to notice.purposeHi.ifBlank { "सारांश निर्दिष्ट नहीं है" }.take(2000),
            "purposeQuote" to notice.purposeQuote.take(2000),
            "deadline" to notice.deadline.ifBlank { "Not specified in notice" }.take(500),
            "deadlineQuote" to notice.deadlineQuote.take(2000),
            "eligibilityEn" to notice.eligibilityEn.ifBlank { "Not specified in notice" }.take(2000),
            "eligibilityHi" to notice.eligibilityHi.ifBlank { "नोटिस में निर्दिष्ट नहीं है" }.take(2000),
            "eligibilityQuote" to notice.eligibilityQuote.take(2000),
            "documentsRequired" to notice.documentsRequired.take(3000),
            "contactDetails" to notice.contactDetails.take(1000),
            "contactQuote" to notice.contactQuote.take(2000),
            "checklistEn" to notice.checklistEn.take(25),
            "checklistHi" to notice.checklistHi.take(25),
            "checklistQuotes" to notice.checklistQuotes.take(25),
            "completedIndices" to notice.completedIndices.take(25),
            "flaggedMissingEn" to notice.flaggedMissingEn.take(25),
            "flaggedMissingHi" to notice.flaggedMissingHi.take(25),
            "followUpQuestionsEn" to notice.followUpQuestionsEn.take(25),
            "followUpQuestionsHi" to notice.followUpQuestionsHi.take(25),
            "qaEntries" to notice.qaEntries.take(50),
            "groundingScore" to notice.groundingScore.coerceIn(0L, 100L),
            "modelUsed" to notice.modelUsed.ifBlank { "google/gemma-4-31b-it" }.take(200),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateChecklistCompletion(
        noticeId: String,
        completedIndices: List<Long>
    ): Result<Unit> {
        val uid = requireUserId()
        require(noticeId.isNotBlank()) { "Notice ID must not be blank for update" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("notices")
            .document(noticeId)
        return try {
            docRef.update(
                mapOf(
                    "completedIndices" to completedIndices.take(25),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateQaEntries(
        noticeId: String,
        qaEntries: List<String>
    ): Result<Unit> {
        val uid = requireUserId()
        require(noticeId.isNotBlank()) { "Notice ID must not be blank for update" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("notices")
            .document(noticeId)
        return try {
            docRef.update(
                mapOf(
                    "qaEntries" to qaEntries.take(50),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteNotice(noticeId: String): Result<Unit> {
        val uid = requireUserId()
        require(noticeId.isNotBlank()) { "Notice ID must not be blank for delete" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("notices")
            .document(noticeId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
