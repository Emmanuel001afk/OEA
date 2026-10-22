package com.oea.launcher.miniapps

/** Pure, uncapped conversation grouping over rows returned by Android's SMS provider. */
internal class OeaSmsThreadIndex {
    private data class Summary(
        var snippet: String,
        var date: Long,
        var count: Int = 0,
        var unreadCount: Int = 0,
        var latestSubscriptionId: Int = -1,
        var latestType: Int = 0
    )

    private val byAddress = linkedMapOf<String, Summary>()

    fun add(
        addressValue: String?,
        body: String?,
        date: Long,
        isRead: Boolean,
        subscriptionId: Int = -1,
        type: Int = 0
    ) {
        val address = addressValue?.trim().orEmpty()
        if (address.isEmpty()) return
        val summary = byAddress[address]
        if (summary == null) {
            byAddress[address] = Summary(
                body.orEmpty().take(140), date, count = 1,
                unreadCount = if (isRead) 0 else 1,
                latestSubscriptionId = subscriptionId,
                latestType = type
            )
        } else {
            summary.count++
            if (!isRead) summary.unreadCount++
            if (date > summary.date) {
                summary.date = date
                summary.snippet = body.orEmpty().take(140)
                summary.latestSubscriptionId = subscriptionId
                summary.latestType = type
            }
        }
    }

    fun threads(): List<OeaMessagingApp.SmsThread> = byAddress.map { (address, summary) ->
        OeaMessagingApp.SmsThread(
            address = address,
            snippet = summary.snippet,
            date = summary.date,
            count = summary.count,
            read = summary.unreadCount == 0,
            unreadCount = summary.unreadCount,
            latestSubscriptionId = summary.latestSubscriptionId,
            latestType = summary.latestType
        )
    }.sortedByDescending { it.date }
}
