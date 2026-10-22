package com.oea.launcher.miniapps

/** Pure, uncapped conversation grouping over rows returned by Android's SMS provider. */
internal class OeaSmsThreadIndex {
    private data class Summary(var snippet: String, var date: Long, var count: Int = 0, var unreadCount: Int = 0)
    private val byAddress = linkedMapOf<String, Summary>()

    fun add(addressValue: String?, body: String?, date: Long, isRead: Boolean) {
        val address = addressValue?.trim().orEmpty()
        if (address.isEmpty()) return
        val summary = byAddress[address]
        if (summary == null) {
            byAddress[address] = Summary(body.orEmpty().take(140), date, count = 1, unreadCount = if (isRead) 0 else 1)
        } else {
            summary.count++
            if (!isRead) summary.unreadCount++
            if (date > summary.date) {
                summary.date = date
                summary.snippet = body.orEmpty().take(140)
            }
        }
    }

    fun threads(): List<OeaMessagingApp.SmsThread> = byAddress.map { (address, summary) ->
        OeaMessagingApp.SmsThread(address, summary.snippet, summary.date, summary.count, summary.unreadCount == 0)
    }.sortedByDescending { it.date }
}
