package me.rerere.rikkahub.skills.js

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

private const val TAG = "SkillSecretsStore"

/**
 * Legacy inventory and removal helper for the retired generic-JavaScript secret store.
 *
 * Generic JavaScript is not a typed trusted adapter, so this class never writes or decrypts a
 * credential. Existing preferences are retained only long enough for the Vault migration UI to
 * report them as requiring user re-entry and for the user to remove them. New typed local host
 * capabilities use [me.rerere.rikkahub.security.SecondUserSecretVault] instead.
 */
class SkillSecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Kept only for binary compatibility. It deliberately does not create another persisted
     * credential copy, and does not log any caller-controlled name or value.
     */
    fun set(skillName: String, secretName: String, value: String): Boolean {
        Log.w(TAG, "Rejected legacy generic-JS secret write")
        return false
    }

    /** Generic JavaScript can never retrieve a legacy raw secret. */
    fun get(skillName: String, secretName: String): String? = null

    /** Remove one legacy record, including both historical IV key layouts. */
    fun remove(skillName: String, secretName: String) {
        prefs.edit {
            remove(prefKey(skillName, secretName))
            remove(ivKey(skillName, secretName))
            remove(legacyIvKey(skillName, secretName))
        }
    }

    /** All `(skillName, secretName)` pairs still present in the old private preferences. */
    fun list(): List<Pair<String, String>> {
        // The old IV key `skill_secret_iv_<skill>__<name>` overlaps the old secret prefix.
        // It is an IV rather than a secret only if its matching original key still exists.
        val allKeys = prefs.all.keys
        return allKeys.mapNotNull { key ->
            if (!key.startsWith(SECRET_PREFIX)) return@mapNotNull null
            val rest = key.removePrefix(SECRET_PREFIX)
            val separator = rest.indexOf("__")
            if (separator <= 0) return@mapNotNull null
            if (key.startsWith(LEGACY_IV_PREFIX)) {
                val ivRest = key.removePrefix(LEGACY_IV_PREFIX)
                if (allKeys.contains(SECRET_PREFIX + ivRest)) return@mapNotNull null
            }
            rest.substring(0, separator) to rest.substring(separator + 2)
        }
    }

    /** Remove all retained legacy records for an uninstalled skill. */
    fun removeAllForSkill(skillName: String) {
        val toRemove = prefs.all.keys.filter {
            it.startsWith("$SECRET_PREFIX$skillName" + "__") ||
                it.startsWith("$IV_PREFIX$skillName" + "__") ||
                it.startsWith("$LEGACY_IV_PREFIX$skillName" + "__")
        }
        if (toRemove.isNotEmpty()) prefs.edit { toRemove.forEach { remove(it) } }
    }

    private fun prefKey(skill: String, name: String): String = "$SECRET_PREFIX${skill}__${name}"
    private fun ivKey(skill: String, name: String): String = "$IV_PREFIX${skill}__${name}"
    private fun legacyIvKey(skill: String, name: String): String =
        "$LEGACY_IV_PREFIX${skill}__${name}"

    private companion object {
        private const val PREFS_NAME = "skill_secrets"
        private const val SECRET_PREFIX = "skill_secret_"
        private const val IV_PREFIX = "skill_iv_"
        private const val LEGACY_IV_PREFIX = "skill_secret_iv_"
    }
}
