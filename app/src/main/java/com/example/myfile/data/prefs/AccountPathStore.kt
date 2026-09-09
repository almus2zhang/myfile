package com.example.myfile.data.prefs

import android.content.Context
import android.content.SharedPreferences

class AccountPathStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("account_last_paths", Context.MODE_PRIVATE)

    fun saveLastPath(accountId: String, path: String) {
        if (accountId.isNotBlank()) {
            val p = if (path.isBlank()) "/" else path
            prefs.edit().putString(accountId, p).apply()
        }
    }

    fun saveLastPath(accountId: Long, path: String) {
        saveLastPath(accountId.toString(), path)
    }

    fun getLastPath(accountId: String): String {
        return prefs.getString(accountId, "/") ?: "/"
    }

    fun getLastPath(accountId: Long): String {
        return getLastPath(accountId.toString())
    }
}
