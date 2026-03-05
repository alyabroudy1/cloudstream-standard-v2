package com.lagradost.cloudstream3.cast.actions

import android.content.Context
import com.lagradost.cloudstream3.AcraApplication.Companion.getActivity
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.VideoClickAction
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
 * Cast to another CloudStream instance on the local network.
 *
 * Full headers and subtitles are sent in the payload —
 * no relay needed since both ends are CloudStream.
 *
 * The receiver auto-plays immediately.
 */
class CloudStreamCastAction : VideoClickAction() {

    override val name = txt("Cast to CloudStream")
    override val oneSource = true
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return CastSessionManager.allDevices.value.any { it.type == CastDeviceType.CLOUDSTREAM }
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        val c2cDevices = CastSessionManager.allDevices.value.filter {
            it.type == CastDeviceType.CLOUDSTREAM
        }

        if (c2cDevices.isEmpty()) return

        uiThread {
            context?.getActivity()?.showBottomDialog(
                c2cDevices.map { it.name },
                -1,
                "Select CloudStream device",
                false,
                {}
            ) { selectedIndex ->
                val device = c2cDevices.getOrNull(selectedIndex) ?: return@showBottomDialog

                ioSafe {
                    val session = CastSessionManager.connect(device)

                    // C2C uses direct URL with full headers (no relay)
                    val headers = CastHeaderManager.buildFullHeaders(link)
                    val position = getViewPos(video.id)?.position

                    val subs = result.subs.map { sub ->
                        CastSubtitle(
                            url = sub.url,
                            language = sub.languageCode ?: "",
                            label = sub.name,
                            mimeType = sub.mimeType
                        )
                    }

                    val payload = CastMediaPayload(
                        url = link.url,
                        mimeType = link.type.getMimeType(),
                        title = video.name,
                        subtitles = subs,
                        startPositionMs = position,
                        headers = headers,
                        isRelayed = false
                    )

                    session.loadMedia(payload)
                }
            }
        }
    }
}
