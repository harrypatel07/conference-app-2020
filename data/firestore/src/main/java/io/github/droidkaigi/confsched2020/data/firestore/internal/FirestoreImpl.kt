package io.github.droidkaigi.confsched2020.data.firestore.internal

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import io.github.droidkaigi.confsched2020.data.firestore.Firestore
import io.github.droidkaigi.confsched2020.model.SessionId
import io.github.droidkaigi.confsched2020.model.Shard
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import timber.log.debug
import javax.inject.Inject
import kotlin.math.floor

internal class FirestoreImpl @Inject constructor() : Firestore {
    companion object {
        const val NUM_SHARDS = 10
    }

    override fun getFavoriteSessionIds(): Flow<List<String>> {
        val setupFavorites = flow {
            signInIfNeeded()
            val favoritesRef = getFavoritesRef()
            val snapshot = favoritesRef
                .fastGet()
            if (snapshot.isEmpty) {
                favoritesRef.add(mapOf("initialized" to true)).await()
            }
            emit(favoritesRef)
        }
        val favoritesSnapshotFlow = setupFavorites.flatMapLatest {
            it.whereEqualTo("favorite", true).toFlow()
        }
        return favoritesSnapshotFlow.mapLatest { favorites ->
            Timber.debug { "favoritesSnapshotFlow onNext" }
            favorites.mapNotNull { item -> item.id }
        }
    }

    override suspend fun toggleFavorite(sessionId: SessionId) {
        Timber.debug { "toggleFavorite: start" }
        signInIfNeeded()
        val document = getFavoritesRef().document(sessionId.id).fastGet()
        val nowFavorite = document.exists() && (document.data?.get(sessionId.id) == true)
        val newFavorite = !nowFavorite
        if (document.exists()) {
            Timber.debug { "toggleFavorite: $sessionId document exits" }
            document.reference
                .delete()
                .await()
        } else {
            Timber.debug { "toggleFavorite: $sessionId document not exits" }
            document.reference
                .set(mapOf("favorite" to newFavorite))
                .await()
        }
        Timber.debug { "toggleFavorite: end" }
    }

    override fun getThumbsUpCount(sessionId: SessionId): Flow<Int> {
        val setupThumbsUp = flow {
            signInIfNeeded()
            val counterRef = getThumbsUpCounterRef(sessionId)
            createShardsIfNeeded(counterRef)
            emit(counterRef)
        }

        val thumbsUpSnapshot = setupThumbsUp.flatMapLatest {
            it.toFlow()
        }

        return thumbsUpSnapshot.map { shards ->
            var count = 0
            shards.forEach { snap ->
                val shard = snap.toObject(Shard::class.java)
                count += shard.count
            }
            count
        }
    }

    override suspend fun incrementThumbsUpCount(sessionId: SessionId) {
        signInIfNeeded()
        val counterRef = getThumbsUpCounterRef(sessionId)
        val shardId = floor(Math.random() * NUM_SHARDS).toInt()
        counterRef
            .document(shardId.toString())
            .update("count", FieldValue.increment(1))
            .await()
    }

    private fun getFavoritesRef(): CollectionReference {
        val firebaseAuth = FirebaseAuth.getInstance()
        val firebaseUserId = firebaseAuth.currentUser?.uid ?: throw RuntimeException(
            "RuntimeException"
        )
        return FirebaseFirestore
            .getInstance()
            .collection("confsched/2020/users/$firebaseUserId/favorites")
    }

    private suspend fun signInIfNeeded() {
        Timber.debug { "signInIfNeeded start" }
        val firebaseAuth = FirebaseAuth.getInstance()
        if (firebaseAuth.currentUser != null) {
            Timber.debug { "signInIfNeeded user already exists" }
            return
        }
        firebaseAuth.signInAnonymously().await()
        Timber.debug { "signInIfNeeded end" }
    }

    private fun getThumbsUpCounterRef(sessionId: SessionId): CollectionReference {
        return FirebaseFirestore
            .getInstance()
            .collection("confsched/2020/sessions/$sessionId/thumbsup_counter")
    }

    private suspend fun createShardsIfNeeded(counterRef: CollectionReference) {
        if (counterRef.fastGet().isEmpty) {
            Timber.debug { "createShardsIfNeeded shards already exist" }
            return
        }

        val tasks = arrayListOf<Task<Void>>()
        (0 until NUM_SHARDS).forEach {
            val makeShard = counterRef
                .document(it.toString())
                .set(Shard(count = 0))
            tasks.add(makeShard)
        }

        Tasks.whenAll(tasks).await()
        Timber.debug { "createShardsIfNeeded creating shards completed" }
    }
}

private suspend fun DocumentReference.fastGet(): DocumentSnapshot {
    return try {
        get(Source.CACHE).await()
    } catch (e: Exception) {
        get(Source.SERVER).await()
    }
}

private suspend fun Query.fastGet(): QuerySnapshot {
    return try {
        get(Source.CACHE).await()
    } catch (e: Exception) {
        get(Source.SERVER).await()
    }
}

private suspend fun CollectionReference.fastGet(): QuerySnapshot {
    return try {
        get(Source.CACHE).await()
    } catch (e: Exception) {
        get(Source.SERVER).await()
    }
}

private fun Query.toFlow(): Flow<QuerySnapshot> {
    return callbackFlow<QuerySnapshot> {
        val listenerRegistration = addSnapshotListener { snapshot, exception ->
            if (exception != null) close(exception)
            else if (snapshot != null) {
                offer(snapshot)
            }
        }
        awaitClose { listenerRegistration.remove() }
    }
}
