package com.example.myfile.data.webdav

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "webdav_accounts")

class AccountStore(private val context: Context) {

    private val key = stringPreferencesKey("accounts_json")

    val accounts: Flow<List<WebDavAccount>> = context.accountStore.data.map { p ->
        p[key]?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WebDavAccount(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        url = o.getString("url"),
                        username = o.getString("username"),
                        password = o.getString("password"),
                        extraUrls = o.optJSONArray("extraUrls")?.let { ea ->
                            (0 until ea.length()).map { j -> ea.getString(j) }
                        } ?: emptyList()
                    )
                }
            } catch (e: Exception) { emptyList() }
        } ?: emptyList()
    }

    suspend fun save(accounts: List<WebDavAccount>) {
        context.accountStore.edit { p ->
            val arr = JSONArray()
            accounts.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("url", a.url)
                    put("username", a.username)
                    put("password", a.password)
                    if (a.extraUrls.isNotEmpty()) {
                        put("extraUrls", JSONArray().apply { a.extraUrls.forEach { put(it) } })
                    }
                })
            }
            p[key] = arr.toString()
        }
    }
}
