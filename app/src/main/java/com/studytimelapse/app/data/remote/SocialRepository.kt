package com.studytimelapse.app.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.studytimelapse.app.data.db.SessionEntity
import com.studytimelapse.app.domain.ChallengeType
import com.studytimelapse.app.domain.StudyRecord
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

data class RemoteProfile(
    val uid: String,
    val username: String,
    val avatar: String,
    val zone: String,
    val weeklyGoalMinutes: Int?,
    val achievements: List<String>,
    val liveSubject: String?,
    val liveStartedAt: Long?,
    val liveTargetMinutes: Int?,
)

data class Friendship(
    val id: String,
    val users: List<String>,
    val requester: String,
    val receiver: String,
    val status: String,
    val requesterName: String,
) {
    val accepted: Boolean get() = status == "accepted"
    fun other(me: String): String = users.firstOrNull { it != me } ?: ""
}

data class Challenge(
    val id: String,
    val users: List<String>,
    val type: ChallengeType,
    val targetMinutes: Int,
    val startDate: String,
    val endDate: String,
    val createdBy: String,
)

/**
 * Everything social, on Cloud Firestore. Only study *metadata* is ever sent: never video, never
 * thumbnails, never notes. Who may read what is enforced server-side by firebase/firestore.rules.
 *
 * Data model:
 *  users/{uid}                      profile + live "studying now" status
 *  users/{uid}/sessions/{id}        one doc per finished session (validated by rules)
 *  inviteCodes/{CODE}               6-character invite codes
 *  friendships/{uidA_uidB}          sorted pair id, status pending|accepted
 *  challenges/{id}                  simple two-person challenges
 */
class SocialRepository(private val auth: AuthRepository) {

    private val fs: FirebaseFirestore? get() = if (auth.available) FirebaseFirestore.getInstance() else null
    private val me: String? get() = auth.currentUser?.uid

    val available: Boolean get() = auth.available

    // ------------------------------------------------------------------ profile

    suspend fun upsertProfile(
        username: String,
        avatar: String,
        zone: String,
        weeklyGoalMinutes: Int?,
        achievements: List<String>,
    ) {
        val db = fs ?: return
        val uid = me ?: return
        val data = hashMapOf<String, Any?>(
            "username" to username.take(30),
            "avatar" to avatar.take(8),
            "zone" to zone,
            "weeklyGoalMinutes" to weeklyGoalMinutes,
            "achievements" to achievements,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        withTimeout(TIMEOUT_MS) { db.collection("users").document(uid).set(data, SetOptions.merge()).await() }
    }

    /** "Studying now" for the friend. Fire-and-forget: Firestore queues it while offline. */
    fun setLive(subject: String?, startedAtUtc: Long?, targetMinutes: Int?) {
        val db = fs ?: return
        val uid = me ?: return
        val live = if (subject == null) null else mapOf(
            "subject" to subject.take(60),
            "startedAt" to startedAtUtc,
            "targetMinutes" to targetMinutes,
        )
        db.collection("users").document(uid).set(mapOf("live" to live), SetOptions.merge())
    }

    fun observeProfile(uid: String): Flow<RemoteProfile?> {
        val db = fs ?: return emptyFlow()
        return callbackFlow {
            val reg = db.collection("users").document(uid).addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snap?.takeIf { it.exists() }?.toProfile())
            }
            awaitClose { reg.remove() }
        }
    }

    // ------------------------------------------------------------------ sessions

    suspend fun uploadSession(s: SessionEntity, shareSubject: Boolean) {
        val db = fs ?: return
        val uid = me ?: return
        val end = s.endUtc ?: return
        val data = hashMapOf<String, Any>(
            "startUtc" to s.startUtc,
            "endUtc" to end,
            "studyMs" to s.studyMs,
            "pausedMs" to s.pausedMs,
            "subject" to (if (shareSubject) s.subject.take(60) else "Study"),
            "zone" to s.zoneId,
            "uploadedAt" to FieldValue.serverTimestamp(),
        )
        s.targetMs?.let { data["targetMs"] = it }
        withTimeout(TIMEOUT_MS) {
            db.collection("users").document(uid).collection("sessions").document(s.id).set(data).await()
        }
    }

    suspend fun deleteRemoteSession(id: String) {
        val db = fs ?: return
        val uid = me ?: return
        withTimeout(TIMEOUT_MS) { db.collection("users").document(uid).collection("sessions").document(id).delete().await() }
    }

    /** A friend's sessions since [sinceUtc], newest first. Readable only if we are friends. */
    fun observeSessions(uid: String, sinceUtc: Long): Flow<List<StudyRecord>> {
        val db = fs ?: return emptyFlow()
        return callbackFlow {
            val reg = db.collection("users").document(uid).collection("sessions")
                .whereGreaterThanOrEqualTo("endUtc", sinceUtc)
                .orderBy("endUtc", Query.Direction.DESCENDING)
                .limit(2000)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    trySend(snap?.documents?.mapNotNull { it.toRecord() } ?: emptyList())
                }
            awaitClose { reg.remove() }
        }
    }

    suspend fun fetchSessions(uid: String, sinceUtc: Long): List<StudyRecord> {
        val db = fs ?: return emptyList()
        val snap = withTimeout(TIMEOUT_MS) {
            db.collection("users").document(uid).collection("sessions")
                .whereGreaterThanOrEqualTo("endUtc", sinceUtc).get().await()
        }
        return snap.documents.mapNotNull { it.toRecord() }
    }

    // ------------------------------------------------------------------ friends

    /** Creates a fresh 6-character invite code valid for 7 days. */
    suspend fun createInviteCode(): String {
        val db = fs ?: error(AuthRepository.NOT_CONFIGURED)
        val uid = me ?: error("Please sign in first.")
        repeat(5) {
            val code = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val ok = runCatching {
                withTimeout(TIMEOUT_MS) {
                    db.collection("inviteCodes").document(code).set(
                        mapOf(
                            "ownerUid" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "expiresAt" to System.currentTimeMillis() + 7 * 24 * 3_600_000L,
                        ),
                    ).await()
                }
            }.isSuccess
            if (ok) return code
        }
        error("Couldn't create a code. Check your connection and try again.")
    }

    /** Uses a friend's code: creates a pending friendship they can accept. */
    suspend fun redeemInviteCode(rawCode: String, myUsername: String) {
        val db = fs ?: error(AuthRepository.NOT_CONFIGURED)
        val uid = me ?: error("Please sign in first.")
        val code = rawCode.trim().uppercase()
        require(code.length == 6 && code.all { it in ALPHABET }) { "Codes are 6 letters/numbers, like A7K29P." }
        val invite = withTimeout(TIMEOUT_MS) { db.collection("inviteCodes").document(code).get().await() }
        val owner = invite.getString("ownerUid") ?: error("That code doesn't exist.")
        val expires = invite.getLong("expiresAt") ?: 0
        if (expires < System.currentTimeMillis()) error("That code has expired. Ask for a new one.")
        if (owner == uid) error("That's your own code — send it to your friend instead.")
        val pair = pairId(uid, owner)
        withTimeout(TIMEOUT_MS) {
            db.collection("friendships").document(pair).set(
                mapOf(
                    "users" to listOf(uid, owner).sorted(),
                    "requester" to uid,
                    "requesterName" to myUsername.take(30),
                    "receiver" to owner,
                    "status" to "pending",
                    "code" to code,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }

    fun observeFriendships(): Flow<List<Friendship>> {
        val db = fs ?: return emptyFlow()
        val uid = me ?: return emptyFlow()
        return callbackFlow {
            val reg = db.collection("friendships").whereArrayContains("users", uid).addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snap?.documents?.mapNotNull { d ->
                        @Suppress("UNCHECKED_CAST")
                        val users = d.get("users") as? List<String> ?: return@mapNotNull null
                        Friendship(
                            id = d.id,
                            users = users,
                            requester = d.getString("requester") ?: "",
                            receiver = d.getString("receiver") ?: "",
                            status = d.getString("status") ?: "pending",
                            requesterName = d.getString("requesterName") ?: "Your friend",
                        )
                    } ?: emptyList(),
                )
            }
            awaitClose { reg.remove() }
        }
    }

    suspend fun acceptFriend(friendshipId: String) {
        val db = fs ?: return
        withTimeout(TIMEOUT_MS) {
            db.collection("friendships").document(friendshipId)
                .update(mapOf("status" to "accepted", "acceptedAt" to FieldValue.serverTimestamp())).await()
        }
    }

    suspend fun removeFriend(friendshipId: String) {
        val db = fs ?: return
        withTimeout(TIMEOUT_MS) { db.collection("friendships").document(friendshipId).delete().await() }
    }

    // ------------------------------------------------------------------ challenges

    suspend fun createChallenge(friendUid: String, type: ChallengeType, targetMinutes: Int, startDate: String, endDate: String) {
        val db = fs ?: return
        val uid = me ?: return
        withTimeout(TIMEOUT_MS) {
            db.collection("challenges").add(
                mapOf(
                    "users" to listOf(uid, friendUid).sorted(),
                    "pairId" to pairId(uid, friendUid),
                    "type" to type.name,
                    "targetMinutes" to targetMinutes,
                    "startDate" to startDate,
                    "endDate" to endDate,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }

    fun observeChallenges(): Flow<List<Challenge>> {
        val db = fs ?: return emptyFlow()
        val uid = me ?: return emptyFlow()
        return callbackFlow {
            val reg = db.collection("challenges").whereArrayContains("users", uid).addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snap?.documents?.mapNotNull { d ->
                        @Suppress("UNCHECKED_CAST")
                        val users = d.get("users") as? List<String> ?: return@mapNotNull null
                        Challenge(
                            id = d.id,
                            users = users,
                            type = runCatching { ChallengeType.valueOf(d.getString("type") ?: "") }.getOrNull()
                                ?: return@mapNotNull null,
                            targetMinutes = d.getLong("targetMinutes")?.toInt() ?: 0,
                            startDate = d.getString("startDate") ?: return@mapNotNull null,
                            endDate = d.getString("endDate") ?: return@mapNotNull null,
                            createdBy = d.getString("createdBy") ?: "",
                        )
                    }?.sortedByDescending { it.startDate } ?: emptyList(),
                )
            }
            awaitClose { reg.remove() }
        }
    }

    suspend fun deleteChallenge(id: String) {
        val db = fs ?: return
        withTimeout(TIMEOUT_MS) { db.collection("challenges").document(id).delete().await() }
    }

    // ------------------------------------------------------------------ account deletion

    /** Deletes every document this user owns or participates in. Called before deleting auth. */
    suspend fun deleteAllMyData() {
        val db = fs ?: return
        val uid = me ?: return
        suspend fun deleteQuery(q: Query) {
            while (true) {
                val snap = withTimeout(TIMEOUT_MS) { q.limit(400).get().await() }
                if (snap.isEmpty) return
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                withTimeout(TIMEOUT_MS) { batch.commit().await() }
                if (snap.size() < 400) return
            }
        }
        deleteQuery(db.collection("users").document(uid).collection("sessions"))
        deleteQuery(db.collection("friendships").whereArrayContains("users", uid))
        deleteQuery(db.collection("challenges").whereArrayContains("users", uid))
        deleteQuery(db.collection("inviteCodes").whereEqualTo("ownerUid", uid))
        withTimeout(TIMEOUT_MS) { db.collection("users").document(uid).delete().await() }
    }

    // ------------------------------------------------------------------ mapping

    private fun DocumentSnapshot.toProfile(): RemoteProfile {
        @Suppress("UNCHECKED_CAST")
        val live = get("live") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val achievements = (get("achievements") as? List<String>) ?: emptyList()
        return RemoteProfile(
            uid = id,
            username = getString("username") ?: "Friend",
            avatar = getString("avatar") ?: "📚",
            zone = getString("zone") ?: "UTC",
            weeklyGoalMinutes = getLong("weeklyGoalMinutes")?.toInt(),
            achievements = achievements,
            liveSubject = live?.get("subject") as? String,
            liveStartedAt = (live?.get("startedAt") as? Number)?.toLong(),
            liveTargetMinutes = (live?.get("targetMinutes") as? Number)?.toInt(),
        )
    }

    private fun DocumentSnapshot.toRecord(): StudyRecord? {
        val start = getLong("startUtc") ?: return null
        val end = getLong("endUtc") ?: return null
        val study = getLong("studyMs") ?: return null
        return StudyRecord(id = id, subject = getString("subject") ?: "Study", startUtc = start, endUtc = end, studyMs = study)
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        /** No 0/O/1/I: codes are read aloud and typed by hand. */
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun pairId(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"
    }
}
