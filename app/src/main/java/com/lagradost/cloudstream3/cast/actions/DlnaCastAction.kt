package com.lagradost.cloudstream3.cast.actions

import android.content.Context
import com.lagradost.cloudstream3.AcraApplication.Companion.getActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastDeviceType
import com.lagradost.cloudstream3.cast.CastHeaderManager
import com.lagradost.cloudstream3.cast.CastMediaPayload
import com.lagradost.cloudstream3.cast.CastSessionManager
import com.lagradost.cloudstream3.cast.CastSubtitle
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.txt

/**
 * Cast to a DLNA MediaRenderer on the local network.
 *
 * Streams are routed through the phone's [StreamRelayServer]
 * so that old devices receive a plain HTTP URL with no auth headers.
 *
 * Only shown when DLNA devices have been discovered.
 */
class DlnaCastAction : VideoClickAction() {

    override val name = txt("Cast to DLNA")
    override val oneSource = true
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return CastSessionManager.allDevices.value.any { it.type == CastDeviceType.DLNA }
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        val dlnaDevices = CastSessionManager.allDevices.value.filter {
            it.type == CastDeviceType.DLNA
        }

        if (dlnaDevices.isEmpty()) return

        // Show device picker
        uiThread {
            context?.getActivity()?.showBottomDialog(
                dlnaDevices.map { it.name },
                -1,
                "Select DLNA device",
                false,
                {}
            ) { selectedIndex ->
                val device = dlnaDevices.getOrNull(selectedIndex) ?: return@showBottomDialog

                // Connect and cast
                ioSafe {
                    val session = CastSessionManager.connect(device)

                    // Prepare the link (routes through relay for DLNA)
                    val readyLink = CastHeaderManager.prepareForCast(
                        link, device, CastSessionManager.relay
                    )

                    val position = getViewPos(video.id)?.position

                    // Build subtitles
                    val subs = result.subs.map { sub ->
                        CastSubtitle(
                            url = sub.url,
                            language = sub.languageCode ?: "",
                            label = sub.name,
                            mimeType = sub.mimeType
                        )
                    }

                    val payload = CastMediaPayload(
                        url = readyLink.url,
                        mimeType = readyLink.mimeType,
                        title = video.name,
                        subtitles = subs,
                        startPositionMs = position,
                        isRelayed = readyLink.isRelayed
                    )

                    session.loadMedia(payload)
                }
            }
        }
    }
}
