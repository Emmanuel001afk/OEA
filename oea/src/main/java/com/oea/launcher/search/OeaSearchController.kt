package com.oea.launcher.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils

data class OeaSearchAction(val title: String, val subtitle: String, val intent: Intent)

class OeaSearchController(private val context: Context) {
    fun actions(query: String): List<OeaSearchAction> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = Uri.encode(q)
        val result = mutableListOf<OeaSearchAction>()

        result += OeaSearchAction("Search the web", q, Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
        result += OeaSearchAction(
            "Search Maps", q,
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        )
        result += OeaSearchAction(
            "Search Spotify", q,
            Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
        )
        result += OeaSearchAction(
            "Search YouTube", q,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
        )
        result += OeaSearchAction(
            "Search Play Store", q,
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded"))
        )
        result += OeaSearchAction(
            "Search contacts", q,
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(q).build())
        )
        return result.filter { canResolve(it.intent) }
    }

    private fun canResolve(intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null
}
