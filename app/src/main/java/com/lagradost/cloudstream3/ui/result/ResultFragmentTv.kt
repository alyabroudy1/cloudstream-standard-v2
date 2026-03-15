package com.lagradost.cloudstream3.ui.result

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.FragmentResultTvBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.isRtl
import com.lagradost.cloudstream3.utils.AppContextUtils.loadCache
import com.lagradost.cloudstream3.utils.AppContextUtils.updateHasTrailers
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.UIHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import kotlinx.coroutines.launch

class ResultFragmentTv : Fragment() {
    private lateinit var viewModel: ResultViewModel2
    private var binding: FragmentResultTvBinding? = null

    override fun onDestroyView() {
        binding = null
        updateUIEvent -= ::updateUI
        activity?.detachBackPressedCallback(this@ResultFragmentTv.toString())
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel =
            ViewModelProvider(this)[ResultViewModel2::class.java]
        viewModel.EPISODE_RANGE_SIZE = 50
        updateUIEvent += ::updateUI

        val localBinding = FragmentResultTvBinding.inflate(inflater, container, false)
        binding = localBinding
        return localBinding.root
    }

    private fun updateUI(id: Int?) {
        viewModel.reloadEpisodes()
    }

    private var currentRecommendations: List<SearchResponse> = emptyList()

    private fun handleSelection(data: Any) {
        when (data) {
            is EpisodeRange -> {
                viewModel.changeRange(data)
            }

            is Int -> {
                viewModel.changeSeason(data)
            }

            is DubStatus -> {
                viewModel.changeDubStatus(data)
            }

            is String -> {
                setRecommendations(currentRecommendations, data)
            }
        }
    }

    private fun RecyclerView?.select(index: Int) {
        (this?.adapter as? SelectAdaptor?)?.select(index, this)
    }

    private fun RecyclerView?.update(data: List<SelectData>) {
        (this?.adapter as? SelectAdaptor?)?.updateSelectionList(data)
        this?.isVisible = data.size > 1
    }

    private fun RecyclerView?.setAdapter() {
        this?.adapter = SelectAdaptor { data ->
            handleSelection(data)
        }
    }

//    private fun hasNoFocus(): Boolean {
//        val focus = activity?.currentFocus
//        if (focus == null || !focus.isVisible) return true
//        return focus == binding?.resultRoot
//    }

    /**
     * Force focus any play button.
     * Note that this will steal any focus if the episode loading is too slow (unlikely).
     */
    private fun focusPlayButton() {
        binding?.resultPlayMovieButton?.requestFocus()
        binding?.resultPlaySeriesButton?.requestFocus()
        binding?.resultResumeSeriesButton?.requestFocus()
    }

    private fun setRecommendations(rec: List<SearchResponse>?, validApiName: String?) {
        currentRecommendations = rec ?: emptyList()
        val isInvalid = rec.isNullOrEmpty()
        binding?.apply {
            resultRecommendationsList.isGone = isInvalid
            resultRecommendationsHolder.isGone = isInvalid
            val matchAgainst = validApiName ?: rec?.firstOrNull()?.apiName
            (resultRecommendationsList.adapter as? SearchAdapter)?.updateList(rec?.filter { it.apiName == matchAgainst }
                ?: emptyList())

            rec?.map { it.apiName }?.distinct()?.let { apiNames ->
                // very dirty selection
                resultRecommendationsFilterSelection.isVisible = apiNames.size > 1
                resultRecommendationsFilterSelection.update(apiNames.map {
                    com.lagradost.cloudstream3.utils.txt(
                        it
                    ) to it
                })
                resultRecommendationsFilterSelection.select(apiNames.indexOf(matchAgainst))
            } ?: run {
                resultRecommendationsFilterSelection.isVisible = false
            }
        }
    }

    var loadingDialog: Dialog? = null
    var popupDialog: Dialog? = null

    private fun reloadViewModel(forceReload: Boolean) {
        if (!viewModel.hasLoaded() || forceReload) {
            val storedData = getStoredData() ?: return
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        }
    }

    override fun onResume() {
        activity?.let {
            @Suppress("DEPRECATION")
            it.window?.navigationBarColor =
                it.colorFromAttribute(R.attr.primaryBlackBackground)
        }
        afterPluginsLoadedEvent += ::reloadViewModel
        super.onResume()
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        super.onStop()
    }

    private fun View.fade(turnVisible: Boolean) {
        if (turnVisible) {
            isVisible = true
        }

        this.animate().alpha(if (turnVisible) 0.97f else 0.0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    this@fade.isVisible = turnVisible
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
        this.animate().translationX(if (turnVisible) 0f else if (isRtl()) -100.0f else 100f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
    }

    private fun toggleEpisodes(show: Boolean) {
        binding?.apply {
            if (show) {
                activity?.attachBackPressedCallback(this@ResultFragmentTv.toString()) {
                    toggleEpisodes(false)
                }
            } else {
                activity?.detachBackPressedCallback(this@ResultFragmentTv.toString())
            }
            episodesShadow.fade(show)
            episodeHolderTv.fade(show)
            if (episodesShadow.isRtl()) {
                episodesShadowBackground.scaleX = -1f
            } else {
                episodesShadowBackground.scaleX = 1f
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ===== setup =====
        val storedData = getStoredData() ?: return
        activity?.window?.decorView?.clearFocus()
        activity?.loadCache()
        hideKeyboard()
        if (storedData.restart || !viewModel.hasLoaded())
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        // ===== ===== =====
        var comingSoon = false

        binding?.apply {
            //episodesShadow.rotationX = 180.0f//if(episodesShadow.isRtl()) 180.0f else 0.0f

            // parallax on background
            resultFinishLoading.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { view, _, scrollY, _, oldScrollY ->
                backgroundPosterHolder.translationY = -scrollY.toFloat() * 0.8f
            })

            redirectToPlay.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                toggleEpisodes(false)

                binding?.apply {
                    val views = listOf(
                        resultPlayMovieButton,
                        resultPlaySeriesButton,
                        resultResumeSeriesButton,
                        resultPlayTrailerButton,
                        resultBookmarkButton,
                        resultFavoriteButton,
                        resultSubscribeButton,
                        resultSearchButton
                    )
                    for (requestView in views) {
                        if (!requestView.isVisible) continue
                        if (requestView.requestFocus()) break
                    }
                }
            }

            redirectToEpisodes.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                toggleEpisodes(true)
                binding?.apply {
                    val views = listOf(
                        resultDubSelection,
                        resultSeasonSelection,
                        resultRangeSelection,
                        resultEpisodes,
                        resultPlayTrailerButton,
                    )
                    for (requestView in views) {
                        if (!requestView.isShown) continue
                        if (requestView.requestFocus()) break // View.FOCUS_RIGHT
                    }
                }
            }

            mapOf(
                resultPlayMovieButton to resultPlayMovieText,
                resultPlaySeriesButton to resultPlaySeriesText,
                resultResumeSeriesButton to resultResumeSeriesText,
                resultPlayTrailerButton to resultPlayTrailerText,
                resultBookmarkButton to resultBookmarkText,
                resultFavoriteButton to resultFavoriteText,
                resultSubscribeButton to resultSubscribeText,
                resultSearchButton to resultSearchText,
                resultCastActionButton to resultCastActionText,
                resultEpisodesShowButton to resultEpisodesShowText
            ).forEach { (button, text) ->

                button.setOnFocusChangeListener { view, hasFocus ->
                    if (!hasFocus) {
                        text.isSelected = false
                        if (view.id == R.id.result_episodes_show_button) toggleEpisodes(false)
                        return@setOnFocusChangeListener
                    }

                    text.isSelected = true
                    if (button.tag == context?.getString(R.string.tv_no_focus_tag)) {
                        resultFinishLoading.scrollTo(0, 0)
                    }
                    when (button.id) {
                        R.id.result_episodes_show_button -> {
                            toggleEpisodes(true)
                        }

                        else -> {
                            toggleEpisodes(false)
                        }
                    }
                }
            }

            resultEpisodesShowButton.setOnClickListener {
                // toggle, to make it more touch accessible just in case someone thinks that a
                // tv layout is better but is using a touch device
                toggleEpisodes(!episodeHolderTv.isVisible)
            }

            resultCastActionButton.setOnClickListener {
                // Green icon = connected → show cast controls / disconnect dialog
                if (com.lagradost.cloudstream3.cast.CastSessionManager.isConnected()) {
                    val deviceName = com.lagradost.cloudstream3.cast.CastSessionManager.activeSession.value?.device?.name ?: "TV"
                    activity?.showBottomDialog(
                        listOf(
                            getString(R.string.cast_controls),
                            getString(R.string.disconnect_from_device)
                        ),
                        -1,
                        getString(R.string.casting_prefix_format, deviceName),
                        false,
                        {}
                    ) { selectedIndex ->
                        when (selectedIndex) {
                            0 -> {
                                // Open full cast controller
                                activity.navigate(
                                    R.id.global_to_navigation_cast_controller
                                )
                            }
                            1 -> {
                                // Disconnect
                                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.lagradost.cloudstream3.cast.CastSessionManager.disconnect()
                                }
                            }
                        }
                    }
                    return@setOnClickListener
                }

                // Gray icon = not connected → show device picker
                val movie = (viewModel.movie.value as? Resource.Success)?.value?.second
                if (movie != null) {
                    viewModel.handleAction(EpisodeClickEvent(ACTION_CAST_EPISODE, movie))
                    return@setOnClickListener
                }
                val resume = viewModel.resumeWatching.value
                if (resume != null) {
                    viewModel.handleAction(EpisodeClickEvent(ACTION_CAST_EPISODE, resume.result))
                    return@setOnClickListener
                }
                val firstEp = (viewModel.episodes.value as? Resource.Success)?.value?.firstOrNull()
                if (firstEp != null) {
                    viewModel.handleAction(EpisodeClickEvent(ACTION_CAST_EPISODE, firstEp))
                } else {
                    CommonActivity.showToast(R.string.no_links_found_toast, Toast.LENGTH_SHORT)
                }
            }

            // Observe cast session state — green icon when connected, gray when not
            viewLifecycleOwner.lifecycleScope.launch {
                com.lagradost.cloudstream3.cast.CastSessionManager.activeSession.collect { session ->
                    if (session != null &&
                        session.state.value != com.lagradost.cloudstream3.cast.CastSessionState.DISCONNECTED &&
                        session.state.value != com.lagradost.cloudstream3.cast.CastSessionState.ERROR) {
                        resultCastActionButton.iconTint = android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#4CAF50")  // Material Green
                        )
                        resultCastActionText.text = getString(R.string.casting_prefix_format, session.device.name)
                    } else {
                        resultCastActionButton.iconTint = android.content.res.ColorStateList.valueOf(
                            context?.colorFromAttribute(R.attr.white) ?: android.graphics.Color.WHITE
                        )
                        resultCastActionText.text = getString(R.string.cast_to_device)
                    }
                }
            }

            // RTL-aware focus navigation for the episode panel.
            // In LTR: panel is on the right, so right=deeper into panel, left=back to main.
            // In RTL: panel is on the left, so left=deeper into panel, right=back to main.
            val isRtl = view.isRtl()

            // The direction that goes "into" the episode panel (away from main content)
            // LTR: right, RTL: left
            fun View.setNextFocusInward(targetId: Int) {
                if (isRtl) nextFocusLeftId = targetId else nextFocusRightId = targetId
            }
            // The direction that goes "out" of the episode panel (back to main content)
            // LTR: left, RTL: right
            fun View.setNextFocusOutward(targetId: Int) {
                if (isRtl) nextFocusRightId = targetId else nextFocusLeftId = targetId
            }

            // Episodes show button → opens episode panel
            resultEpisodesShowButton.setNextFocusInward(R.id.redirect_to_episodes)

            // Episode panel chain: dub → season → range → episodes
            resultDubSelection.setNextFocusOutward(R.id.result_episodes_show)
            resultDubSelection.setNextFocusInward(R.id.result_season_selection)

            resultSeasonSelection.setNextFocusOutward(R.id.result_dub_selection)
            resultSeasonSelection.setNextFocusInward(R.id.result_range_selection)

            resultRangeSelection.setNextFocusOutward(R.id.result_season_selection)
            resultRangeSelection.setNextFocusInward(R.id.result_episodes)

            resultEpisodes.setNextFocusOutward(R.id.result_range_selection)

            resultEpisodes.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            resultDubSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )
            resultRangeSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )
            resultSeasonSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )

            /*.layoutManager =
                LinearListLayout(resultEpisodes.context, resultEpisodes.isRtl()).apply {
                    setVertical()
                }*/

            resultReloadConnectionerror.setOnClickListener {
                viewModel.load(
                    activity,
                    storedData.url,
                    storedData.apiName,
                    storedData.showFillers,
                    storedData.dubStatus,
                    storedData.start
                )

            }

            resultMetaSite.isFocusable = false

            resultSeasonSelection.setAdapter()
            resultRangeSelection.setAdapter()
            resultDubSelection.setAdapter()
            resultRecommendationsFilterSelection.setAdapter()

            resultCastItems.setOnFocusChangeListener { _, hasFocus ->
                // Always escape focus
                if (hasFocus) binding?.resultBookmarkButton?.requestFocus()
            }
            //resultBack.setOnClickListener {
            //    activity?.popCurrentPage()
            //}

            resultRecommendationsList.spanCount = 8
            resultRecommendationsList.adapter =
                SearchAdapter(
                    ArrayList(),
                    resultRecommendationsList,
                ) { callback ->
                    if (callback.action == SEARCH_ACTION_FOCUSED)
                        toggleEpisodes(false)
                    else
                        SearchHelper.handleSearchClickCallback(callback)
                }

            resultEpisodes.adapter =
                EpisodeAdapter(
                    false,
                    { episodeClick ->
                        viewModel.handleAction(episodeClick)
                    },
                    { downloadClickEvent ->
                        DownloadButtonSetup.handleDownloadClick(downloadClickEvent)
                    }
                )

            resultCastItems.layoutManager = object : LinearListLayout(view.context) {

                override fun onRequestChildFocus(
                    parent: RecyclerView,
                    state: RecyclerView.State,
                    child: View,
                    focused: View?
                ): Boolean {
                    // Make the cast always focus the first visible item when focused
                    // from somewhere else. Otherwise it jumps to the last item.
                    return if (parent.focusedChild == null) {
                        scrollToPosition(this.findFirstCompletelyVisibleItemPosition())
                        true
                    } else {
                        super.onRequestChildFocus(parent, state, child, focused)
                    }
                }
            }.apply {
                setHorizontal()
            }

            val aboveCast = listOf(
                binding?.resultEpisodesShow,
                binding?.resultBookmark,
                binding?.resultFavorite,
                binding?.resultSubscribe,
                binding?.resultCastAction,
            ).firstOrNull {
                it?.isVisible == true
            }

            resultCastItems.adapter = ActorAdaptor(aboveCast?.id) {
                toggleEpisodes(false)
            }

            if (isLayout(EMULATOR)) {
                episodesShadow.setOnClickListener {
                    toggleEpisodes(false)
                }
            }
        }

        observeNullable(viewModel.resumeWatching) { resume ->
            binding?.apply {

                if (resume == null) {
                    return@observeNullable
                }
                resultResumeSeries.isVisible = true
                resultPlayMovie.isVisible = false
                resultPlaySeries.isVisible = false

                // show progress no matter if series or movie
                resume.progress?.let { progress ->
                    resultResumeSeriesTitle.apply {
                        isVisible = !resume.isMovie
                        text =
                            if (resume.isMovie) null else context?.getNameFull(
                                resume.result.name,
                                resume.result.episode,
                                resume.result.season
                            )
                    }
                    resultResumeSeriesProgressText.setText(progress.progressLeft)
                    resultResumeSeriesProgress.apply {
                        isVisible = true
                        this.max = progress.maxProgress
                        this.progress = progress.progress
                    }
                    resultResumeProgressHolder.isVisible = true
                } ?: run {
                    resultResumeProgressHolder.isVisible = false
                }

                focusPlayButton()
                // Stops last button right focus if it is a movie
                if (resume.isMovie)
                    resultSearchButton.nextFocusRightId = R.id.result_search_Button

                resultResumeSeriesText.text =
                    when {
                        resume.isMovie -> context?.getString(R.string.resume)
                        resume.result.season != null ->
                            "${getString(R.string.season_short)}${resume.result.season}:${
                                getString(
                                    R.string.episode_short
                                )
                            }${resume.result.episode}"

                        else -> "${getString(R.string.episode)} ${resume.result.episode}"
                    }

                resultResumeSeriesButton.setOnClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(
                            storedData.playerAction, //?: ACTION_PLAY_EPISODE_IN_PLAYER,
                            resume.result
                        )
                    )
                }

                resultResumeSeriesButton.setOnLongClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(ACTION_SHOW_OPTIONS, resume.result)
                    )
                    return@setOnLongClickListener true
                }

            }
        }

        observe(viewModel.trailers) { trailersLinks ->
            context?.updateHasTrailers()
            if (!LoadResponse.isTrailersEnabled) return@observe
            val trailers = trailersLinks.flatMap { it.mirros }
            binding?.apply {
                resultPlayTrailer.isGone = trailers.isEmpty()
                resultPlayTrailerButton.setOnClickListener {
                    if (trailers.isEmpty()) return@setOnClickListener
                    activity.navigate(
                        R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                            ExtractorLinkGenerator(
                                trailers,
                                emptyList()
                            )
                        )
                    )
                }
            }
        }

        observe(viewModel.watchStatus) { watchType ->
            binding?.apply {
                resultBookmarkText.setText(watchType.stringRes)

                resultBookmarkButton.apply {

                    val drawable = if (watchType.stringRes == R.string.type_none) {
                        R.drawable.outline_bookmark_add_24
                    } else {
                        R.drawable.ic_baseline_bookmark_24
                    }
                    setIconResource(drawable)

                    setOnClickListener { view ->
                        activity?.showBottomDialog(
                            WatchType.entries.map { view.context.getString(it.stringRes) }.toList(),
                            watchType.ordinal,
                            view.context.getString(R.string.action_add_to_bookmarks),
                            showApply = false,
                            {}) {
                            viewModel.updateWatchStatus(WatchType.entries[it], context)
                        }
                    }
                }
            }
        }

        observeNullable(viewModel.favoriteStatus) { isFavorite ->

            binding?.resultFavorite?.isVisible = isFavorite != null

            binding?.resultFavoriteButton?.apply {

                if (isFavorite == null) return@observeNullable

                val drawable = if (isFavorite) {
                    R.drawable.ic_baseline_favorite_24
                } else {
                    R.drawable.ic_baseline_favorite_border_24
                }

                setIconResource(drawable)

                setOnClickListener {
                    viewModel.toggleFavoriteStatus(context) { newStatus: Boolean? ->
                        if (newStatus == null) return@toggleFavoriteStatus

                        val message = if (newStatus) {
                            R.string.favorite_added
                        } else {
                            R.string.favorite_removed
                        }

                        val name = (viewModel.page.value as? Resource.Success)?.value?.title
                            ?: com.lagradost.cloudstream3.utils.txt(R.string.no_data)
                                .asStringNull(context) ?: ""
                        CommonActivity.showToast(
                            com.lagradost.cloudstream3.utils.txt(
                                message,
                                name
                            ), Toast.LENGTH_SHORT
                        )
                    }
                }
            }

            binding?.resultFavoriteText?.apply {
                val text = if (isFavorite == true) {
                    R.string.unfavorite
                } else {
                    R.string.favorite
                }
                setText(text)
            }
        }

        observeNullable(viewModel.subscribeStatus) { isSubscribed ->
            binding?.resultSubscribe?.isVisible = isSubscribed != null && isLayout(EMULATOR)
            binding?.resultSubscribeButton?.apply {

                if (isSubscribed == null) return@observeNullable

                val drawable = if (isSubscribed) {
                    R.drawable.ic_baseline_notifications_active_24
                } else {
                    R.drawable.baseline_notifications_none_24
                }

                setIconResource(drawable)

                setOnClickListener {
                    viewModel.toggleSubscriptionStatus(context) { newStatus: Boolean? ->
                        if (newStatus == null) return@toggleSubscriptionStatus

                        val message = if (newStatus) {
                            // Kinda icky to have this here, but it works.
                            SubscriptionWorkManager.enqueuePeriodicWork(context)
                            R.string.subscription_new
                        } else {
                            R.string.subscription_deleted
                        }

                        val name = (viewModel.page.value as? Resource.Success)?.value?.title
                            ?: com.lagradost.cloudstream3.utils.txt(R.string.no_data)
                                .asStringNull(context) ?: ""
                        CommonActivity.showToast(
                            com.lagradost.cloudstream3.utils.txt(
                                message,
                                name
                            ), Toast.LENGTH_SHORT
                        )
                    }
                }

                binding?.resultSubscribeText?.apply {
                    val text = if (isSubscribed) {
                        R.string.action_unsubscribe
                    } else {
                        R.string.action_subscribe
                    }
                    setText(text)
                }
            }
        }

        observeNullable(viewModel.movie) { data ->
            if (data == null) {
                return@observeNullable
            }

            binding?.apply {

                (data as? Resource.Success)?.value?.let { (_, ep) ->

                    resultPlayMovieButton.setOnClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep)
                        )
                    }
                    resultPlayMovieButton.setOnLongClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                        )
                        return@setOnLongClickListener true
                    }

                    resultPlayMovie.isVisible = !comingSoon && resultResumeSeries.isGone
                    if (comingSoon)
                        resultBookmarkButton.requestFocus()
                    else
                        resultPlayMovieButton.requestFocus()

                    // Stops last button right focus
                    resultSearchButton.nextFocusRightId = R.id.result_search_Button
                }
            }
        }

        observeNullable(viewModel.selectPopup) { popup ->
            if (popup == null) {
                popupDialog?.dismissSafe(activity)
                popupDialog = null
                return@observeNullable
            }

            popupDialog?.dismissSafe(activity)

            popupDialog = activity?.let { act ->
                val options = popup.getOptions(act)
                val title = popup.getTitle(act)

                act.showBottomDialogInstant(
                    options, title, {
                        popupDialog = null
                        popup.callback(null)
                    }, {
                        popupDialog = null
                        popup.callback(it)
                    }
                )
            }
        }

        observeNullable(viewModel.loadedLinks) { load ->
            if (load == null) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
                return@observeNullable
            }
            if (loadingDialog?.isShowing != true) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
            }
            loadingDialog = loadingDialog ?: context?.let { ctx ->
                val builder = BottomSheetDialog(ctx)
                builder.setContentView(R.layout.bottom_loading)
                builder.setOnDismissListener {
                    loadingDialog = null
                    viewModel.cancelLinks()
                }
                builder.setCanceledOnTouchOutside(true)
                builder.show()
                builder
            }
            loadingDialog?.findViewById<MaterialButton>(R.id.overlay_loading_skip_button)?.apply {
                if (load.linksLoaded <= 0) {
                    isInvisible = true
                } else {
                    setOnClickListener {
                        viewModel.skipLoading()
                    }
                    isVisible = true
                    text = "${context.getString(R.string.skip_loading)} (${load.linksLoaded})"
                }
            }
        }


        observeNullable(viewModel.episodesCountText) { count ->
            binding?.resultEpisodesText.setText(count)
        }

        observe(viewModel.selectedRangeIndex) { selected ->
            binding?.resultRangeSelection.select(selected)
        }
        observe(viewModel.selectedSeasonIndex) { selected ->
            binding?.resultSeasonSelection.select(selected)
        }
        observe(viewModel.selectedDubStatusIndex) { selected ->
            binding?.resultDubSelection.select(selected)
        }
        observe(viewModel.rangeSelections) {
            binding?.resultRangeSelection.update(it)
        }
        observe(viewModel.dubSubSelections) {
            binding?.resultDubSelection.update(it)
        }
        observe(viewModel.seasonSelections) {
            binding?.resultSeasonSelection.update(it)
        }
        observe(viewModel.recommendations) { recommendations ->
            setRecommendations(recommendations, null)
        }

        if (isLayout(TV)) {
            observe(viewModel.episodeSynopsis) { description ->
                view.context?.let { ctx ->
                    val builder: AlertDialog.Builder =
                        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                    builder.setMessage(description.html())
                        .setTitle(R.string.synopsis)
                        .setOnDismissListener {
                            viewModel.releaseEpisodeSynopsis()
                        }
                        .show()
                }
            }
        }

        // Used to request focus the first time the episodes are loaded.
        var hasLoadedEpisodesOnce = false
        observeNullable(viewModel.episodes) { episodes ->
            if (episodes == null) return@observeNullable

            binding?.apply {

                if (comingSoon)
                    resultBookmarkButton.requestFocus()

                //    resultEpisodeLoading.isVisible = episodes is Resource.Loading
                if (episodes is Resource.Success) {

                    val lastWatchedIndex = episodes.value.indexOfLast { ep ->
                        ep.getWatchProgress() >= NEXT_WATCH_EPISODE_PERCENTAGE.toFloat() / 100.0f || ep.videoWatchState == VideoWatchState.Watched
                    }

                    val firstUnwatched =
                        episodes.value.getOrElse(lastWatchedIndex + 1) { episodes.value.firstOrNull() }

                    if (firstUnwatched != null) {
                        resultPlaySeriesText.text =
                            when {
                                firstUnwatched.season != null ->
                                    "${getString(R.string.season_short)}${firstUnwatched.season}:${
                                        getString(
                                            R.string.episode_short
                                        )
                                    }${firstUnwatched.episode}"

                                else -> "${getString(R.string.episode)} ${firstUnwatched.episode}"
                            }
                        resultPlaySeriesButton.setOnClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(
                                    ACTION_CLICK_DEFAULT,
                                    firstUnwatched
                                )
                            )
                        }
                        resultPlaySeriesButton.setOnLongClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(ACTION_SHOW_OPTIONS, firstUnwatched)
                            )
                            return@setOnLongClickListener true
                        }
                        if (!hasLoadedEpisodesOnce) {
                            hasLoadedEpisodesOnce = true
                            resultPlaySeries.isVisible = resultResumeSeries.isGone && !comingSoon
                            resultEpisodesShow.isVisible = true && !comingSoon
                            resultPlaySeriesButton.requestFocus()
                        }
                    }


                    (resultEpisodes.adapter as? EpisodeAdapter)?.updateList(episodes.value)
                }
            }
        }

        observeNullable(viewModel.page) { data ->
            if (data == null) return@observeNullable
            binding?.apply {
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        resultVpn.setText(d.vpnText)
                        resultInfo.setText(d.metaText)
                        resultNoEpisodes.setText(d.noEpisodesFoundText)
                        resultTitle.setText(d.titleText)
                        resultMetaSite.setText(d.apiName)
                        resultMetaType.setText(d.typeText)
                        resultMetaYear.setText(d.yearText)
                        resultMetaDuration.setText(d.durationText)
                        resultMetaRating.setText(d.ratingText)
                        resultMetaStatus.setText(d.onGoingText)
                        resultMetaContentRating.setText(d.contentRatingText)
                        resultCastText.setText(d.actorsText)
                        resultNextAiring.setText(d.nextAiringEpisode)
                        resultNextAiringTime.setText(d.nextAiringDate)
                        resultPoster.loadImage(d.posterImage)

                        var isExpanded = false
                        resultDescription.apply {
                            setTextHtml(d.plotText)
                            setOnClickListener {
                                if (isLayout(EMULATOR)) {
                                    isExpanded = !isExpanded
                                    maxLines = if (isExpanded) {
                                        Integer.MAX_VALUE
                                    } else 10
                                } else {
                                    view.context?.let { ctx ->
                                        val builder: AlertDialog.Builder =
                                            AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                                        builder.setMessage(d.plotText.asString(ctx).html())
                                            .setTitle(d.plotHeaderText.asString(ctx))
                                            .show()
                                    }
                                }
                            }
                        }

                        val error = listOf(
                            R.drawable.profile_bg_dark_blue,
                            R.drawable.profile_bg_blue,
                            R.drawable.profile_bg_orange,
                            R.drawable.profile_bg_pink,
                            R.drawable.profile_bg_purple,
                            R.drawable.profile_bg_red,
                            R.drawable.profile_bg_teal
                        ).random()

                        backgroundPoster.loadImage(d.posterBackgroundImage) {
                            error { getImageFromDrawable(context ?: return@error null, error) }
                        }
                        comingSoon = d.comingSoon
                        resultTvComingSoon.isVisible = d.comingSoon

                        UIHelper.populateChips(resultTag, d.tags)
                        resultCastItems.isGone = d.actors.isNullOrEmpty()
                        (resultCastItems.adapter as? ActorAdaptor)?.updateList(
                            d.actors ?: emptyList()
                        )

                        if (d.contentRatingText == null) {
                            // If there is no rating to display, we don't want an empty gap
                            resultMetaContentRating.width = 0
                        }

                        resultSearchButton.setOnClickListener {
                            QuickSearchFragment.pushSearch(activity, d.title)
                        }
                    }

                    is Resource.Loading -> {

                    }

                    is Resource.Failure -> {
                        resultErrorText.text =
                            storedData.url.plus("\n") + data.errorString
                    }
                }

                resultFinishLoading.isVisible = data is Resource.Success

                resultLoading.isVisible = data is Resource.Loading

                resultLoadingError.isVisible = data is Resource.Failure
                //resultReloadConnectionOpenInBrowser.isVisible = data is Resource.Failure
            }
        }
    }
}