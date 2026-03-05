package com.lagradost.cloudstream3.cast.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.cast.CastHeaderManager
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Share a stream URL to external apps (VLC, WebVideoCaster, MX Player, etc.).
 *
 * Uses Android's ACTION_VIEW intent with the video URL.
 * Passes headers and subtitles via standard Intent extras where supported:
 * - VLC: "extra_headers", "subtitles_location"
 * - MX Player: "headers", "subs", "subs.name"
 *
 * Always available — doesn't depend on device discovery.
 */
class ShareToAppAction : VideoClickAction() {

    override val name = txt("Share to external app")
    override val oneSource = true
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        val headers = CastHeaderManager.buildFullHeaders(link)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(link.url), link.type.getMimeType())

            // Title
            putExtra("title", video.name ?: "")

            // ── VLC-style headers ──
            // VLC for Android reads headers from this extra
            if (headers.isNotEmpty()) {
                val headerArray = ArrayList<String>()
                headers.forEach { (key, value) ->
                    headerArray.add("$key: $value")
                }
                putExtra("extra_headers", headerArray)
            }

            // ── MX Player-style headers ──
            val headerArray = Array(headers.size) { i ->
                val entry = headers.entries.toList()[i]
                "${entry.key}: ${entry.value}"
            }
            putExtra("headers", headerArray)

            // ── Subtitles ──
            if (result.subs.isNotEmpty()) {
                // VLC-style
                putExtra("subtitles_location", result.subs.firstOrNull()?.url ?: "")

                // MX Player-style
                val subUris = result.subs.map { Uri.parse(it.url) }.toTypedArray()
                val subNames = result.subs.map { it.name }.toTypedArray()
                putExtra("subs", subUris)
                putExtra("subs.name", subNames)
            }

            // ── Position ──
            val position = com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos(video.id)?.position
            if (position != null && position > 0) {
                putExtra("position", position.toInt())
            }

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Use chooser so user picks the app
        val chooser = Intent.createChooser(intent, "Stream with...")
        launch(chooser)
    }
}
