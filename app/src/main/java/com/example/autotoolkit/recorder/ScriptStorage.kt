package com.example.autotoolkit.recorder

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ScriptStorage {

    private const val PREF = "autotoolkit_scripts"
    private const val KEY = "saved_scripts"

    data class SavedScript(
        val name: String,
        val actions: List<RecordedAction>,
        val createdAt: Long
    )

    private val gson = Gson()

    fun save(context: Context, name: String, actions: List<RecordedAction>) {
        val list = loadAll(context).toMutableList()
        list.removeAll { it.name == name }
        list.add(SavedScript(name, actions, System.currentTimeMillis()))
        persist(context, list)
    }

    fun loadAll(context: Context): List<SavedScript> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedScript>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun delete(context: Context, name: String) {
        val list = loadAll(context).toMutableList()
        list.removeAll { it.name == name }
        persist(context, list)
    }

    private fun persist(context: Context, list: List<SavedScript>) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }
}
