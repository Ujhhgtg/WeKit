package dev.ujhhgtg.wekit.pet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pet registry — the multi-pet contract. Ported from dsh-pet registry.ts.
 * One pet is a directory holding a 'pet.json' manifest plus an atlas image.
 * The manifest follows the Codex/hatch-pet contract (8 columns x 9 rows of
 * 192x208 cells, the 9-state row order). Legacy manifests that only carry
 * 'frames' keep working: geometry, per-row frame counts and per-track rhythm
 * all fall back to the contract defaults.
 */

/** A normalized pet definition (renderable, client-visible). */
data class PetDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val cell: PetCell,
    val columns: Int,
    val rows: List<Int>,
    val atlasRows: Int,
    val tracks: Map<PetAnimation, PetTrackDef>,
    val sequences: Map<ActivityPhase, List<PetAnimation>>?,
    val remarks: PetRemarks?,
    val spritesheetPath: String,
)

/** A resolved pet plus its asset directory. */
data class PetEntry(
    val definition: PetDefinition,
    val assetDir: String,
)

/** Registry load result: resolved entries plus load warnings. */
data class PetRegistry(
    val entries: List<PetEntry>,
    val warnings: List<String>,
) {
    private val byId = entries.associateBy { it.definition.id }
    fun byId(id: String): PetEntry? = byId[id]
    fun defaultEntry(): PetEntry? = entries.firstOrNull()
}

/** Stable id charset: lowercase kebab. */
private val PET_ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

private val PET_PHASES: List<String> = ActivityPhase.entries.map { it.id }

const val PET_NAME_MAX_LENGTH = 80

/** Shared JSON config for manifest parsing (lenient, ignores unknown keys). */
private val petJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

private fun finiteInt(value: JsonElement?, fallback: Int, max: Int): Int {
    val n = value?.jsonPrimitive?.intOrNull ?: return fallback
    return if (n in 1..max) n else fallback
}

/** Normalize a manifest 'tracks' block (per-track rhythm overrides). */
private fun normalizeTracks(
    raw: JsonElement?,
    id: String,
    warn: (String) -> Unit,
): Map<PetAnimation, List<Int>> {
    if (raw == null) return emptyMap()
    val obj = raw as? JsonObject ?: return emptyMap()
    val result = mutableMapOf<PetAnimation, List<Int>>()
    for ((key, value) in obj) {
        val anim = PetAnimation.fromId(key)
        if (anim == null) {
            warn("manifest $id: unknown track $key")
            continue
        }
        val durations = ((value as? JsonObject)?.get("durations") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.filter { it > 0 }
            ?: emptyList()
        if (durations.isNotEmpty()) result[anim] = durations
    }
    return result
}

/** Validate optional scene sequences without rejecting an otherwise usable pet. */
private fun normalizeSequences(
    raw: JsonElement?,
    id: String,
    warn: (String) -> Unit,
): Map<ActivityPhase, List<PetAnimation>>? {
    if (raw == null) return null
    val obj = raw as? JsonObject ?: return null
    val sequences = mutableMapOf<ActivityPhase, List<PetAnimation>>()
    for ((phase, value) in obj) {
        if (phase !in PET_PHASES) {
            warn("manifest $id: unknown sequence phase $phase")
            continue
        }
        val anims = (value as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.let { a -> PetAnimation.fromId(a) }
        } ?: emptyList()
        if (anims.size < 5) {
            warn("manifest $id: sequence $phase must contain at least 5 animations")
            continue
        }
        sequences[ActivityPhase.fromId(phase)!!] = anims
    }
    return if (sequences.isEmpty()) null else sequences
}

/** Normalize one parsed manifest into a renderable pet, or null on contract violation. */
fun resolvePetManifest(
    raw: JsonObject,
    warnings: MutableList<String> = mutableListOf(),
): PetDefinition? {
    val warn: (String) -> Unit = { warnings.add(it) }
    val id = (raw["id"] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
    if (!PET_ID_PATTERN.matches(id)) {
        warn("manifest id $id is not a lowercase kebab id")
        return null
    }
    val displayName = ((raw["displayName"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        ?: id
    val description = ((raw["description"] as? JsonPrimitive)?.contentOrNull?.trim()) ?: ""
    val spritesheet = ((raw["spritesheetPath"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        ?: "spritesheet.webp"

    val rawCell = raw["cell"] as? JsonObject
    val cell = PetCell(
        width = finiteInt(rawCell?.get("width"), DEFAULT_PET_CELL.width, 2048),
        height = finiteInt(rawCell?.get("height"), DEFAULT_PET_CELL.height, 2048),
    )
    val columns = finiteInt(raw["columns"], DEFAULT_PET_COLUMNS, 32)
    // v2 atlases (spriteVersionNumber 2) hold 11 rows; v1 holds 9.
    val atlasRows = if ((raw["spriteVersionNumber"] as? JsonPrimitive)?.intOrNull == 2) 11 else DEFAULT_PET_ROW_COUNT

    val rawFrames = raw["frames"] as? JsonArray
    val rows = DEFAULT_FRAME_COUNTS.mapIndexed { index, fallback ->
        finiteInt(rawFrames?.getOrNull(index), fallback, columns)
    }

    val trackDurations = normalizeTracks(raw["tracks"], id, warn)
    val sequences = normalizeSequences(raw["sequences"], id, warn)

    val tracks = mutableMapOf<PetAnimation, PetTrackDef>()
    for (animation in PET_ROW_ORDER) {
        val pattern = DEFAULT_TRACK_PATTERNS[animation]!!
        val durations = trackDurations[animation] ?: pattern.durations
        if (durations.isEmpty()) {
            warn("manifest $id: track ${animation.id} carries no usable durations")
            return null
        }
        val frameCount = 1.coerceAtLeast(rows[animation.row].coerceAtMost(columns))
        val sized = if (durations.size >= frameCount) {
            durations.take(frameCount)
        } else {
            List(frameCount) { index -> durations[index % durations.size] }
        }
        tracks[animation] = PetTrackDef(
            frames = List(frameCount) { it },
            durations = sized,
            loop = pattern.loop,
            fallback = pattern.fallback,
        )
    }

    // remarks block: {slot: string | string[]}
    val rawRemarks = raw["remarks"] as? JsonObject
    val remarks = if (rawRemarks != null) {
        val map = mutableMapOf<String, Any?>()
        for ((k, v) in rawRemarks) {
            map[k] = if (v is JsonArray) v.mapNotNull { it.jsonPrimitive.contentOrNull } else (v as? JsonPrimitive)?.contentOrNull
        }
        normalizePetRemarks(map) { m -> warn("manifest $id: $m") }
    } else null

    return PetDefinition(
        id = id,
        displayName = displayName.take(PET_NAME_MAX_LENGTH),
        description = description,
        cell = cell,
        columns = columns,
        rows = rows,
        atlasRows = atlasRows,
        tracks = tracks,
        sequences = sequences,
        remarks = remarks,
        spritesheetPath = spritesheet,
    )
}

/** Parse a manifest JSON string into a pet definition, or null on failure. */
fun parsePetManifest(json: String, warnings: MutableList<String> = mutableListOf()): PetDefinition? {
    return try {
        val obj = petJson.parseToJsonElement(json).jsonObject
        resolvePetManifest(obj, warnings)
    } catch (e: Exception) {
        warnings.add("parse manifest: ${e.message}")
        null
    }
}
