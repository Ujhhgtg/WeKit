package dev.ujhhgtg.wekit.pet

import dev.ujhhgtg.wekit.pet.core.AffinityState
import dev.ujhhgtg.wekit.pet.core.AFFINITY_MAX
import dev.ujhhgtg.wekit.pet.core.TreatLedger
import dev.ujhhgtg.wekit.pet.core.emptyAffinity
import dev.ujhhgtg.wekit.pet.core.emptyTreatLedger
import dev.ujhhgtg.wekit.preferences.WePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pet persistence — a single JSON document stored in WePrefs (MMKV), mirroring
 * the dsh-pet `pet.json` store. Tolerant read: corrupt/missing data falls back
 * to defaults. Serialized via kotlinx.serialization.
 */

/** Display configuration the user can tweak. */
@Serializable
data class PetDisplayConfig(
    val visible: Boolean = true,
    /** Pet sprite height in dp (drives width via the atlas aspect ratio). */
    val size: Int = DEFAULT_DISPLAY_SIZE,
)

@Serializable
data class PersistAffinity(
    val points: Long = 0,
    val lastPetAt: Long = 0,
    val lastFeedAt: Long = 0,
    val pets: Long = 0,
    val feeds: Long = 0,
    val petRejects: Long = 0,
    val feedRejects: Long = 0,
    val turns: Long = 0,
)

@Serializable
data class PersistTreats(
    val treats: Long = 0,
    val lastTreatGrantAt: Long = 0,
    val turnsAtLastTreatGrant: Long = 0,
)

/** Everything persisted for the pet. */
@Serializable
data class PetPersistData(
    val petId: String = DEFAULT_PET_ID,
    val names: Map<String, String> = emptyMap(),
    val affinity: PersistAffinity = PersistAffinity(),
    val treats: PersistTreats = PersistTreats(),
    val display: PetDisplayConfig = PetDisplayConfig(),
)

const val DEFAULT_PET_ID = "whale-girl"
const val DEFAULT_PET_NAME = "鲸鱼娘"
const val PET_NAME_MAX_LENGTH = 20

const val DEFAULT_DISPLAY_SIZE = 160
const val DISPLAY_SIZE_MIN = 64
const val DISPLAY_SIZE_MAX = 512

/** Convert a persisted affinity doc into the core model. */
fun PersistAffinity.toCore(): AffinityState = AffinityState(
    points = points.coerceIn(0, AFFINITY_MAX),
    lastPetAt = lastPetAt.coerceAtLeast(0),
    lastFeedAt = lastFeedAt.coerceAtLeast(0),
    pets = pets.coerceAtLeast(0),
    feeds = feeds.coerceAtLeast(0),
    petRejects = petRejects.coerceAtLeast(0),
    feedRejects = feedRejects.coerceAtLeast(0),
    turns = turns.coerceAtLeast(0),
)

/** Convert a core affinity model into its persisted form. */
fun AffinityState.toPersist(): PersistAffinity = PersistAffinity(
    points = points, lastPetAt = lastPetAt, lastFeedAt = lastFeedAt,
    pets = pets, feeds = feeds, petRejects = petRejects, feedRejects = feedRejects, turns = turns,
)

fun PersistTreats.toCore(): TreatLedger = TreatLedger(
    treats = treats.coerceAtLeast(0),
    lastTreatGrantAt = lastTreatGrantAt.coerceAtLeast(0),
    turnsAtLastTreatGrant = turnsAtLastTreatGrant.coerceAtLeast(0),
)

fun TreatLedger.toPersist(): PersistTreats = PersistTreats(
    treats = treats, lastTreatGrantAt = lastTreatGrantAt, turnsAtLastTreatGrant = turnsAtLastTreatGrant,
)

fun emptyPersist(): PetPersistData = PetPersistData(
    petId = DEFAULT_PET_ID,
    names = emptyMap(),
    affinity = emptyAffinity().toPersist(),
    treats = emptyTreatLedger().toPersist(),
    display = PetDisplayConfig(),
)

/** Shared JSON config (lenient, ignores unknown keys). */
private val persistJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** Loads/atomically persists the pet document in WePrefs. */
object PetPersist {
    private const val KEY = "pet_persist_v1"

    fun load(): PetPersistData {
        val raw = WePrefs.getStringOrDef(KEY, "")
        if (raw.isBlank()) return emptyPersist()
        return try {
            persistJson.decodeFromString<PetPersistData>(raw)
        } catch (e: Exception) {
            emptyPersist()
        }
    }

    fun save(data: PetPersistData) {
        WePrefs.putString(KEY, persistJson.encodeToString(data))
    }
}
