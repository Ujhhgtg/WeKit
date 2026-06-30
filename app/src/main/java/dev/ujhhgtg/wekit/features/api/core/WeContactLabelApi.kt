package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi.modifyLabel
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.DexKit
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.util.LinkedList

@Feature(name = "联系人标签服务", categories = ["API"], description = "提供联系人标签查询与修改能力")
object WeContactLabelApi : ApiFeature(), IResolveDex {

    private val TAG = This.Class.simpleName

    data class ContactLabel(val labelId: Int, val labelName: String)

    private val classNetSceneModifyContactLabelList by dexClass {
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/modifycontactlabellist")
            addMethod {
                name = "<init>"
                paramTypes(LinkedList::class.java)
            }
        }
    }

    /**
     * Get all contact labels.
     * @return List of ContactLabel sorted alphabetically by name
     */
    fun getAllLabels(): List<ContactLabel> {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT labelID, labelName FROM ContactLabel ORDER BY labelName"
            )
            val labels = mutableListOf<ContactLabel>()
            cursor.use {
                while (it.moveToNext()) {
                    labels.add(
                        ContactLabel(
                            it.getInt(it.getColumnIndexOrThrow("labelID")),
                            it.getString(it.getColumnIndexOrThrow("labelName"))
                        )
                    )
                }
            }
            labels
        } catch (e: Exception) {
            WeLogger.e(TAG, "getAllLabels failed", e)
            emptyList()
        }
    }

    /**
     * Get contacts belonging to a specific label, by label ID.
     */
    fun getContactsByLabelId(labelId: Int): List<String> {
        return try {
            val raw = WeDatabaseApi.rawQuery(
                "SELECT username FROM rcontact WHERE ',' || contactLabelIds || ',' LIKE ?",
                arrayOf("%,$labelId,%")
            )
            val wxids = mutableListOf<String>()
            raw.use {
                while (it.moveToNext()) {
                    wxids.add(it.getString(0))
                }
            }
            wxids
        } catch (e: Exception) {
            WeLogger.e(TAG, "getContactsByLabelId failed", e)
            emptyList()
        }
    }

    /**
     * Get the label names currently associated with a contact.
     * Reads the comma-separated `contactLabelIds` column off `rcontact` and resolves each id
     * back to its name. Useful for additive label edits, since [modifyLabel] replaces the whole
     * label set of a contact rather than appending to it.
     */
    fun getLabelNamesForContact(username: String): List<String> {
        return try {
            val labelIds = WeDatabaseApi.rawQuery(
                "SELECT contactLabelIds FROM rcontact WHERE username = ?",
                arrayOf(username)
            ).use {
                if (it.moveToFirst()) it.getString(0) else null
            }

            if (labelIds.isNullOrBlank()) return emptyList()

            val idToName = getAllLabels().associate { it.labelId to it.labelName }
            labelIds.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .mapNotNull { idToName[it] }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getLabelNamesForContact failed", e)
            emptyList()
        }
    }

    /**
     * Get contacts belonging to a specific label, by label name.
     * First resolves the label name to label ID, then queries contacts.
     */
    fun getContactsByLabelName(labelName: String): List<String> {
        return try {
            val labelId = getLabelIdByName(labelName) ?: return emptyList()
            getContactsByLabelId(labelId)
        } catch (e: Exception) {
            WeLogger.e(TAG, "getContactsByLabelName failed", e)
            emptyList()
        }
    }

    /**
     * The contact-label protobuf class (e.g. `fs4.il6` on 8.0.65) cannot be located by structural
     * matching alone: ~250 protobuf classes share the exact same shape (extends
     * `com.tencent.mm.protobuf.f`, exactly two `String` fields). Instead, we anchor on the unique
     * string "UserLabelInfoList".
     *
     * The response handler that uses that string (e.g. `m03.m2.call`) iterates a
     * `LinkedList<ContactLabelPb>` and reads the `UserName` String field off each element. That
     * field read is an `iget-object` whose declaring class is exactly the protobuf class we want.
     * So: among methods that use "UserLabelInfoList", find a read of a `String` field whose
     * declaring class has exactly two fields, both `String` — that class is the target.
     */
    private val classContactLabelPb by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
        val candidates = DexKit.findMethod {
            matcher {
                usingEqStrings("UserLabelInfoList")
            }
        }.asSequence()
            .flatMap { it.usingFields.asSequence() }
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter { it.typeName == "java.lang.String" }
            .map { it.declaredClass }
            .filter { clazz ->
                val fields = clazz.fields
                fields.size == 2 && fields.all { it.typeName == "java.lang.String" }
            }
            .map { it.name }
            .distinct()
            .toList()

        val className = candidates.singleOrNull()
        if (className == null) {
            WeLogger.e(TAG, "contactLabelPbClass resolution failed, candidates=$candidates")
            error("failed to locate classContactLabelPb")
        }

        classContactLabelPb.setDescriptor(className)
    }

    /**
     * Modify contact labels.
     * @param username Target contact username
     * @param labelNames List of label names to associate with the contact
     */
    fun modifyLabel(username: String, labelNames: List<String>) {
        try {
            WeLogger.i(TAG, "modifyLabel: username=$username, labels=$labelNames")

            val labelIds = mutableListOf<String>()
            for (name in labelNames) {
                val id = getLabelIdByName(name)
                if (id != null) {
                    labelIds.add(id.toString())
                } else {
                    WeLogger.w(TAG, "modifyLabel: label '$name' not found in database, skipping")
                }
            }

            val pbClass = classContactLabelPb.clazz

            val pbInstance = pbClass.createInstance()

            val stringFields = pbClass.declaredFields.filter { it.type == String::class.java }
            if (stringFields.size < 2) {
                throw IllegalStateException("ContactLabelPb does not have at least 2 String fields")
            }

            val fieldUsername = stringFields[0].makeAccessible()
            val fieldLabelIds = stringFields[1].makeAccessible()

            fieldUsername.set(pbInstance, username)

            val joinedIds = labelIds.joinToString(",")
            fieldLabelIds.set(pbInstance, joinedIds)

            val linkedList = LinkedList<Any>()
            linkedList.push(pbInstance)

            val netSceneInstance = classNetSceneModifyContactLabelList.clazz.createInstance(linkedList)

            WeNetSceneApi.sendNetScene(netSceneInstance)
            WeLogger.i(TAG, "modifyLabel netscene dispatched successfully")
        } catch (e: Exception) {
            WeLogger.e(TAG, "modifyLabel failed", e)
        }
    }

    /**
     * Get a label ID from its name.
     */
    private fun getLabelIdByName(labelName: String): Int? {
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT labelID FROM ContactLabel WHERE labelName = ?",
            arrayOf(labelName)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return null
    }
}
