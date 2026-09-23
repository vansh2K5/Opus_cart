package com.studytimelapse.app.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.studytimelapse.app.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** Signed-in user as the app sees it. */
data class AuthUser(val uid: String, val email: String?)

/**
 * Firebase Authentication (email + password). Passwords never touch our code beyond being handed
 * to the Firebase SDK over TLS; Firebase stores only salted hashes (scrypt).
 */
class AuthRepository(context: Context) {

    /** False when the build has no google-services.json: the app then runs in local-only mode. */
    val available: Boolean = BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    private val auth: FirebaseAuth? get() = if (available) FirebaseAuth.getInstance() else null

    val currentUser: AuthUser? get() = auth?.currentUser?.let { AuthUser(it.uid, it.email) }

    val user: Flow<AuthUser?> = if (!available) flowOf(null) else callbackFlow {
        val a = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { fa ->
            trySend(fa.currentUser?.let { AuthUser(it.uid, it.email) })
        }
        a.addAuthStateListener(listener)
        awaitClose { a.removeAuthStateListener(listener) }
    }

    suspend fun signIn(email: String, password: String): Result<AuthUser> = attempt {
        val a = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        val r = a.signInWithEmailAndPassword(email.trim(), password).await()
        AuthUser(r.user!!.uid, r.user!!.email)
    }

    suspend fun signUp(email: String, password: String): Result<AuthUser> = attempt {
        val a = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        val r = a.createUserWithEmailAndPassword(email.trim(), password).await()
        AuthUser(r.user!!.uid, r.user!!.email)
    }

    suspend fun resetPassword(email: String): Result<Unit> = attempt {
        val a = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        a.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    fun signOut() {
        auth?.signOut()
    }

    /** Deleting an account requires a fresh login; the password is re-checked first. */
    suspend fun reauthenticate(password: String): Result<Unit> = attempt {
        val u = auth?.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        u.reauthenticate(EmailAuthProvider.getCredential(u.email ?: "", password)).await()
        Unit
    }

    suspend fun deleteAuthUser(): Result<Unit> = attempt {
        val u = auth?.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        u.delete().await()
        Unit
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(Exception(friendlyMessage(e), e))
    }

    companion object {
        const val NOT_CONFIGURED = "Accounts aren't set up in this build yet. See docs/BACKEND_SETUP.md."

        fun friendlyMessage(e: Throwable): String = when (e) {
            is FirebaseAuthWeakPasswordException -> "Please use a password with at least 6 characters."
            is FirebaseAuthInvalidUserException -> "No account found for this email."
            is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
            is FirebaseAuthUserCollisionException -> "An account with this email already exists."
            is FirebaseAuthRecentLoginRequiredException -> "Please enter your password again to continue."
            is FirebaseNetworkException -> "No internet connection. Try again when you're online."
            else -> e.message ?: "Something went wrong."
        }
    }
}
