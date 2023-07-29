package com.kylecorry.andromeda.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.preference.PreferenceManager
import com.kylecorry.andromeda.core.cache.MemoryCachedValue
import com.kylecorry.andromeda.core.topics.generic.Topic
import com.kylecorry.andromeda.core.tryOrDefault
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.sol.units.Coordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// TODO: Create a suspend version of preferences, and this can just be a runBlocking wrapper around it
class SynchronousDataStorePreferences(
    private val context: Context,
    name: String,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    migrations: List<DataMigration<Preferences>> = emptyList()
) : IPreferences, Closeable {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = corruptionHandler,
        migrations = migrations,
        scope = scope
    ) {
        context.applicationContext.preferencesDataStoreFile(name)
    }

    private val cache = MemoryCachedValue<Preferences>()
    private var job: Job? = null

    init {
        // Initial load
        job = scope.launch {
            cache.getOrPut { dataStore.data.first() }
        }
    }

    override val onChange: Topic<String> = Topic()

    override fun remove(key: String) {
        editBlocking(key) {
            // Since this doesn't allow for Any types, we just ignore the exception
            tryOrNothing {
                it.remove(stringPreferencesKey(key))
            }
        }
    }

    override fun contains(key: String): Boolean = getBlocking {
        // Need to try all types since we don't know what type it is
        it.contains(stringPreferencesKey(key)) ||
                it.contains(booleanPreferencesKey(key)) ||
                it.contains(intPreferencesKey(key)) ||
                it.contains(longPreferencesKey(key)) ||
                it.contains(floatPreferencesKey(key)) ||
                it.contains(doublePreferencesKey(key)) ||
                it.contains(stringSetPreferencesKey(key))
    }

    override fun putInt(key: String, value: Int) {
        editBlocking(key) {
            it[intPreferencesKey(key)] = value
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        editBlocking(key) {
            it[booleanPreferencesKey(key)] = value
        }
    }

    override fun putString(key: String, value: String) {
        editBlocking(key) {
            it[stringPreferencesKey(key)] = value
        }
    }

    override fun putFloat(key: String, value: Float) {
        editBlocking(key) {
            it[floatPreferencesKey(key)] = value
        }
    }

    override fun putDouble(key: String, value: Double) {
        editBlocking(key) {
            it[doublePreferencesKey(key)] = value
        }
    }

    override fun putLong(key: String, value: Long) {
        editBlocking(key) {
            it[longPreferencesKey(key)] = value
        }
    }

    override fun getInt(key: String): Int? {
        return getBlocking {
            it[intPreferencesKey(key)]
        }
    }

    override fun getBoolean(key: String): Boolean? {
        return getBlocking {
            it[booleanPreferencesKey(key)]
        }
    }

    override fun getString(key: String): String? {
        return getBlocking {
            it[stringPreferencesKey(key)]
        }
    }

    override fun getFloat(key: String): Float? {
        return getBlocking {
            it[floatPreferencesKey(key)]
        }
    }

    override fun getDouble(key: String): Double? {
        return getBlocking {
            it[doublePreferencesKey(key)]
        }
    }

    override fun getLong(key: String): Long? {
        return getBlocking {
            it[longPreferencesKey(key)]
        }
    }

    override fun putCoordinate(key: String, value: Coordinate) {
        putString(key, "${value.latitude},${value.longitude}")
    }

    override fun getCoordinate(key: String): Coordinate? {
        val raw = getString(key) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) {
            return null
        }
        return tryOrDefault(null) {
            Coordinate(parts[0].toDouble(), parts[1].toDouble())
        }
    }

    override fun getLocalDate(key: String): LocalDate? {
        val raw = getString(key) ?: return null
        return try {
            LocalDate.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    override fun putLocalDate(key: String, date: LocalDate) {
        putString(key, date.toString())
    }

    override fun putInstant(key: String, value: Instant) {
        putLong(key, value.toEpochMilli())
    }

    override fun getInstant(key: String): Instant? {
        val time = getLong(key) ?: return null
        return Instant.ofEpochMilli(time)
    }

    override fun getDuration(key: String): Duration? {
        val millis = getLong(key) ?: return null
        return Duration.ofMillis(millis)
    }

    override fun putDuration(key: String, duration: Duration) {
        putLong(key, duration.toMillis())
    }

    override fun getAll(): Map<String, *> {
        return getBlocking {
            it.toMap()
        }
    }


    private inline fun <T> getBlocking(crossinline block: (Preferences) -> T): T = runBlocking {
        val data = cache.getOrPut { dataStore.data.first() }
        block(data)
    }

    private inline fun editBlocking(key: String, crossinline block: (MutablePreferences) -> Unit) {
        runBlocking {
            val prefs = dataStore.edit {
                block(it)
            }
            cache.put(prefs)
            onChange.publish(key)
        }
    }

    private fun Preferences.toMap(): Map<String, *> {
        return asMap().map { entry ->
            entry.key.name to entry.value
        }.toMap()
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
    }

    companion object {

        fun deleteDataStore(context: Context, name: String) {
            val datastoreFile = context.applicationContext.preferencesDataStoreFile(name)
            datastoreFile.delete()
        }

        fun getDefaultSharedPreferencesMigration(
            context: Context,
            keysToMigrate: Collection<String>? = null
        ): DataMigration<Preferences> {
            if (keysToMigrate == null) {
                return SharedPreferencesMigration({
                    PreferenceManager.getDefaultSharedPreferences(
                        context.applicationContext
                    )
                })
            }

            return SharedPreferencesMigration({
                PreferenceManager.getDefaultSharedPreferences(
                    context.applicationContext
                )
            }, keysToMigrate.toSet())
        }
    }
}