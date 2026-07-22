package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.database.Cursor
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.SnsCommentActionProto
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.moments.AntiMomentCommentsDelete.INTERCEPTED_FLAG
import dev.ujhhgtg.wekit.features.items.moments.AntiMomentCommentsDelete.INTERCEPT_MARKER
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.StrArr
import dev.ujhhgtg.wekit.utils.reflection.int

@Feature(name = "朋友圈评论防撤回", categories = ["朋友圈"], description = "拦截朋友圈评论删除并添加标记")
object AntiMomentCommentsDelete : SwitchFeature(), IResolveDex {

    private const val TAG = "AntiMomentCommentsDelete"

    // Bit 8 (value 256) — unused by WeChat; persists the "intercepted" state in the DB.
    private const val INTERCEPTED_FLAG = 256

    private const val SNS_COMMENT = "SnsComment"

    // Share the same marker string as the moments-level feature.
    private val INTERCEPT_MARKER get() = AntiMomentsDelete.INTERCEPT_MARKER

    // ── dex matchers ────────────────────────────────────────────────────────────

    // Safety-net: low-level SQL executor inside SnsSqliteDB / SnsCommentStorage
    private val methodSnsSqliteDbExecSql1 by dexMethod {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "execSQL")
            paramCount = 2
        }
    }
    private val methodSnsSqliteDbExecSql2 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "execSQL")
            paramCount = 3
        }
    }

    // SnsCommentStorage.deleteComment(snsId, commentSvrId, type)
    private val methodSnsCommentStorageDeleteComment by dexMethod {
        matcher {
            usingEqStrings("deleteComment", "com.tencent.mm.plugin.sns.storage.SnsCommentStorage")
        }
    }

    // SnsCommentStorage.deleteBySnsId(snsId)
    private val methodSnsCommentStorageDeleteCommentBySnsId by dexMethod {
        matcher {
            usingEqStrings("deleteBySnsId", "com.tencent.mm.plugin.sns.storage.SnsCommentStorage")
        }
    }

    // SnsComment.setCommentDelFlag() — prevents WeChat's in-memory soft-delete flag
    private val methodSnsCommentSetCommentDelFlag by dexMethod {
        matcher {
            usingEqStrings("setCommentDelFlag", "com.tencent.mm.plugin.sns.storage.SnsComment")
        }
    }

    // SnsComment.convertFrom(Cursor) — called when WeChat reads a comment row from DB
    private val methodSnsCommentConvertFromCursor by dexMethod {
        matcher {
            usingEqStrings("convertFrom", "com.tencent.mm.plugin.sns.storage.SnsComment")
        }
    }

    // ── DB helpers ───────────────────────────────────────────────────────────────

    /**
     * Retrieves the l75.k0 DB handle from a SnsCommentStorage instance.
     * w1 (SnsCommentStorage) holds it as the sole non-builtin final instance field.
     */
    private fun getSnsSqliteDb(param: HookParam): Any {
        return param.thisObject!!.reflekt().firstField {
            type { !it.isBuiltin }
            modifiers(Modifiers.FINAL)
        }.get()!!
    }

    /**
     * update(table, ContentValues, whereClause, whereArgs) — present in all known versions.
     * 8.0.72: y55.i0.e  |  8.0.74+: l75.k0.p
     * Using ContentValues avoids the execSQL(table, sql, Object[]) overload that is absent
     * in older builds, and handles BLOB columns (curActionBuf) natively.
     */
    private fun updateRow(
        param: HookParam,
        table: String,
        values: ContentValues,
        whereClause: String,
        whereArgs: Array<String>,
    ): Int {
        return getSnsSqliteDb(param).reflekt().firstMethod {
            parameters(BString, ContentValues::class, BString, StrArr)
            returnType = int
        }.invoke(table, values, whereClause, whereArgs) as Int
    }

    /** k0.B / y55.i0.j — raw query returning a Cursor */
    private fun rawQuery(
        param: HookParam,
        sql: String,
        args: Array<String>,
    ): Cursor {
        return getSnsSqliteDb(param).reflekt().firstMethod {
            parameters(BString, StrArr)
            returnType = Cursor::class
        }.invoke(sql, args) as Cursor
    }

    // ── hooks ────────────────────────────────────────────────────────────────────

    override fun onEnable() {
        // Safety net: block any raw DELETE SQL that bypasses the storage methods.
        listOf(
            methodSnsSqliteDbExecSql1,
            methodSnsSqliteDbExecSql2
        ).forEach {
            if (it.isPlaceholder) return@forEach
            it.hookBefore {
                val table = args.getOrNull(0) as? String ?: return@hookBefore
                val sql = args.getOrNull(1) as? String ?: return@hookBefore
                if (table == SNS_COMMENT && sql.lowercase().contains("delete from")) {
                    result = false
                }
            }
        }

        // Block WeChat's own in-memory soft-delete bit so the object stays "live".
        methodSnsCommentSetCommentDelFlag.hookBefore {
            result = null
        }

        // Rescue comments that were soft-deleted before the module was active.
        // These rows are still in the DB but carry WeChat's delete bit (bit 0) in commentflag.
        // hookAfter ensures all fields are populated from the cursor before we inspect them.
        methodSnsCommentConvertFromCursor.hookAfter {
            val flagField = thisObject!!.reflekt().firstField {
                name = "field_commentflag"
                superclass()
            }
            val flag = flagField.get() as? Int ?: return@hookAfter

            // Bit 0 = WeChat's own delete marker; bit 8 = our INTERCEPTED_FLAG.
            // Only act on rows WeChat deleted but we haven't yet processed.
            if (flag and 1 == 0) return@hookAfter

            // Clear WeChat's delete bit so the comment is treated as live,
            // and stamp our intercepted bit so markAndBlockDelete skips it on future deletes.
            flagField.set(flag and 1.inv() or INTERCEPTED_FLAG)

            // Inject the visual marker into the comment text in memory.
            val bufField = thisObject!!.reflekt().firstField {
                name = "field_curActionBuf"
                superclass()
            }
            val buf = bufField.get() as? ByteArray ?: return@hookAfter
            bufField.set(injectMarkerIntoBuf(buf))
        }

        // deleteComment(snsId: Long, commentSvrId: Long, type: Int) — single comment
        methodSnsCommentStorageDeleteComment.hookBefore {
            val snsId = args[0] as Long
            val commentSvrId = args[1] as Long
            markAndBlockDelete(
                param = this,
                whereClause = "snsID = ? AND commentSvrID = ?",
                whereArgs = arrayOf(snsId.toString(), commentSvrId.toString()),
            )
        }

        // deleteBySnsId(snsId: Long) — all comments on a moment
        methodSnsCommentStorageDeleteCommentBySnsId.hookBefore {
            val snsId = args[0] as Long
            markAndBlockDelete(
                param = this,
                whereClause = "snsID = ?",
                whereArgs = arrayOf(snsId.toString()),
            )
        }
    }

    // ── core logic ───────────────────────────────────────────────────────────────

    /**
     * Iterates over every comment matching [whereClause], injects [INTERCEPT_MARKER] into
     * the text field of the curActionBuf protobuf, sets [INTERCEPTED_FLAG] in commentflag,
     * persists both back to DB, then cancels the deletion by setting result = true.
     *
     * Uses rowid for per-row updates so that each comment's curActionBuf is updated
     * independently (important for deleteBySnsId which may match many rows).
     */
    private fun markAndBlockDelete(
        param: HookParam,
        whereClause: String,
        whereArgs: Array<String>,
    ) {
        WeLogger.i(TAG, "intercepted delete: $whereClause / ${whereArgs.toList()}")
        try {
            val cursor = rawQuery(
                param,
                "SELECT rowid, curActionBuf, commentflag FROM $SNS_COMMENT WHERE $whereClause",
                whereArgs,
            )
            cursor.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(0)
                    val actionBuf = cursor.getBlob(1)     // nullable — some rows may have no buf
                    val currentFlag = cursor.getInt(2)

                    // Skip if already marked (idempotent).
                    if (currentFlag and INTERCEPTED_FLAG != 0) continue

                    val newFlag = currentFlag or INTERCEPTED_FLAG
                    val newBuf = injectMarkerIntoBuf(actionBuf)

                    val cv = ContentValues().apply {
                        put("curActionBuf", newBuf)
                        put("commentflag", newFlag)
                    }
                    updateRow(param, SNS_COMMENT, cv, "rowid = ?", arrayOf(rowId.toString()))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "markAndBlockDelete failed", e)
        }
        // Cancel the deletion regardless of whether the marker injection succeeded.
        param.result = true
    }

    /**
     * Decodes [buf] as [SnsCommentActionProto], prepends [INTERCEPT_MARKER] to
     * [SnsCommentActionProto.content] (field 1), and re-encodes.
     *
     * For sticker/emoji comments field 1 is null or absent — those return [buf] unchanged.
     * WeChat renders stickers from the opaque sub-messages at fields 14/16, so a missing
     * or modified field 1 has no effect on sticker display. The [INTERCEPTED_FLAG] bit in
     * `commentflag` still marks the row in the DB.
     *
     * Returns [buf] unchanged on any parse or encode failure.
     */
    private fun injectMarkerIntoBuf(buf: ByteArray?): ByteArray {
        if (buf == null || buf.isEmpty()) return buf ?: ByteArray(0)
        return try {
            val proto = SnsCommentActionProto.decode(buf)
            val content = proto.content
                ?: return buf  // field 1 absent — sticker/emoji comment, nothing to mark
            if (content.contains(INTERCEPT_MARKER)) return buf
            proto.copy(content = "$INTERCEPT_MARKER $content")
                .encode()
                .takeIf { it.isNotEmpty() } ?: buf
        } catch (e: Exception) {
            WeLogger.e(TAG, "injectMarkerIntoBuf failed", e)
            buf
        }
    }
}
