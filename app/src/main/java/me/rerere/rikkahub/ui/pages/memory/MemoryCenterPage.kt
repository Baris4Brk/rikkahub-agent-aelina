package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MemoryCenterPage(id: String) {
    val vm: MemoryCenterVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val assistants by vm.assistants.collectAsStateWithLifecycle()
    val viewGlobal by vm.viewGlobal.collectAsStateWithLifecycle()
    val filter by vm.libraryFilter.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val latestFailure by vm.latestFailure.collectAsStateWithLifecycle()
    val extractionModel by vm.extractionModel.collectAsStateWithLifecycle()
    val modelOptions by vm.modelOptions.collectAsStateWithLifecycle()
    val recallState by vm.recallTestState.collectAsStateWithLifecycle()
    val actionMessage by vm.lastActionMessage.collectAsStateWithLifecycle()
    val developerMode by vm.developerMode.collectAsStateWithLifecycle()
    val observerDiagnostic by vm.observerDiagnostic.collectAsStateWithLifecycle()
    val dreamProjection by vm.dreamProjection.collectAsStateWithLifecycle()
    val dreamDetailState by vm.dreamDetailState.collectAsStateWithLifecycle()
    val dreamingScopePreferences by vm.dreamingScopePreferences.collectAsStateWithLifecycle()
    val dreamingCostPolicy by vm.dreamingCostPolicy.collectAsStateWithLifecycle()
    val memories = vm.library.collectAsLazyPagingItems()
    val candidates = vm.candidates.collectAsLazyPagingItems()
    val relationCandidates by vm.relationCandidates.collectAsStateWithLifecycle()
    val tabs = remember(developerMode) { memoryCenterTabs(developerMode) }
    val pageCount by rememberUpdatedState(tabs.size)
    val pagerState = rememberPagerState { pageCount }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val narrativeSelfFallback = stringResource(R.string.memory_v2_narrative_self_fallback)
    val narrativeCompanionFallback = stringResource(R.string.memory_v2_narrative_companion_fallback)
    val narrativeSharedNamesFormat = stringResource(R.string.memory_v2_narrative_shared_names)
    val narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames = remember(
        assistant,
        assistants,
        narrativeSelfFallback,
        narrativeCompanionFallback,
        narrativeSharedNamesFormat,
    ) {
        { originAssistantId: String? ->
            assistants.memoryNarrativeNamesFor(
                originAssistantId = originAssistantId,
                fallbackAssistant = assistant,
                defaultSelfName = narrativeSelfFallback,
                defaultCompanionName = narrativeCompanionFallback,
                sharedNameFormat = narrativeSharedNamesFormat,
            )
        }
    }

    val appliedMessage = stringResource(R.string.memory_v2_action_completed)
    val conflictMessage = stringResource(R.string.memory_v2_action_conflict)
    val failedMessage = stringResource(R.string.memory_v2_action_failed)
    val dreamAuthorityPendingMessage = stringResource(R.string.memory_dream_authority_applied_rebuild_pending)
    LaunchedEffect(actionMessage) {
        val message = when (actionMessage) {
            null -> return@LaunchedEffect
            "conflict" -> conflictMessage
            "failed", "not_found" -> failedMessage
            "dream_authority_applied_rebuild_pending" -> dreamAuthorityPendingMessage
            else -> appliedMessage
        }
        snackbarHostState.showSnackbar(message)
        vm.lastActionMessage.value = null
    }
    LaunchedEffect(developerMode, tabs.size) {
        if (pagerState.currentPage >= tabs.size) {
            pagerState.scrollToPage((tabs.size - 1).coerceAtLeast(0))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.onDreamUiHidden()
    }
    DisposableEffect(vm) {
        onDispose(vm::onDreamUiHidden)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.memory_v2_title))
                        Text(
                            text = assistant.name,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MemoryScopeSummary(
                assistant = assistant,
                viewGlobal = viewGlobal,
                stats = stats,
                onViewGlobalChange = vm::setViewGlobal,
            )
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            if (tab == MemoryCenterTab.REVIEW && stats.pendingReview > 0) {
                                BadgedBox(badge = { Badge { Text(stats.pendingReview.toString()) } }) {
                                    Text(tab.title())
                                }
                            } else {
                                Text(tab.title())
                            }
                        },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) { page ->
                when (tabs.getOrElse(page) { MemoryCenterTab.SETTINGS }) {
                    MemoryCenterTab.LIBRARY -> MemoryLibraryTab(
                        memories = memories,
                        narrativeNamesForOrigin = narrativeNamesForOrigin,
                        filter = filter,
                        onFilterChange = vm::updateFilter,
                        onCreate = vm::createMemory,
                        onUpdate = vm::updateMemory,
                        onArchive = vm::archive,
                        onRestore = vm::restore,
                        revisions = vm::revisions,
                        onRestoreRevision = vm::restoreRevision,
                    )

                    MemoryCenterTab.REVIEW -> MemoryReviewTab(
                        candidates = candidates,
                        relationCandidates = relationCandidates,
                        narrativeNamesForOrigin = narrativeNamesForOrigin,
                        onLoadMemories = vm::loadMemories,
                        onResolveSource = vm::resolveSource,
                        onAccept = vm::acceptCandidate,
                        onReject = vm::rejectCandidate,
                        onAcceptSafeNew = vm::acceptSafeNewCandidates,
                        onRejectAllPending = vm::rejectAllPendingCandidates,
                        onAcceptRelation = vm::acceptRelationCandidate,
                        onRejectRelation = vm::rejectRelationCandidate,
                    )

                    MemoryCenterTab.DREAM -> MemoryDreamTab(
                        projection = dreamProjection,
                        detailState = dreamDetailState,
                        onOpenClaim = vm::openDreamClaim,
                        onCloseClaim = vm::closeDreamClaim,
                        onRevealEvidence = vm::revealDreamEvidence,
                        onRejectClaim = vm::rejectDreamClaim,
                        onCorrectClaim = vm::correctDreamClaim,
                        onClearDerived = vm::clearDreamDerived,
                    )

                    MemoryCenterTab.SETTINGS -> MemorySettingsTab(
                        assistant = assistant,
                        stats = stats,
                        latestFailure = latestFailure,
                        extractionModel = extractionModel,
                        modelOptions = modelOptions,
                        recallState = recallState,
                        dreamingScopePreferences = dreamingScopePreferences,
                        dreamingCostPolicy = dreamingCostPolicy,
                        narrativeNamesForOrigin = narrativeNamesForOrigin,
                        onUpdateAssistant = vm::updateAssistant,
                        onAutoSaveModeChange = vm::setAutoSaveMode,
                        onScheduleTuningChange = vm::setScheduleTuning,
                        onNarrativeNamesChange = vm::setNarrativeNames,
                        onOriginChange = vm::setOrigin,
                        onExtractionModelChange = vm::setExtractionModel,
                        onProcessNow = { vm.processNow(false) },
                        onRetryFailed = { vm.processNow(true) },
                        onRecallTest = vm::runRecallTest,
                        onDreamingScopePreferenceChange = vm::updateDreamingScopePreference,
                        onDreamingCostPolicyChange = vm::updateDreamingCostPolicy,
                    )

                    MemoryCenterTab.OBSERVER -> MemoryObserverDiagnosticsTab(
                        diagnostic = observerDiagnostic,
                        onRefresh = vm::refreshObserverDiagnostics,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCenterTab.title(): String = when (this) {
    MemoryCenterTab.LIBRARY -> stringResource(R.string.memory_v2_library_tab)
    MemoryCenterTab.DREAM -> stringResource(R.string.memory_dream_tab)
    MemoryCenterTab.REVIEW -> stringResource(R.string.memory_v2_review_tab)
    MemoryCenterTab.SETTINGS -> stringResource(R.string.memory_v2_settings_tab)
    MemoryCenterTab.OBSERVER -> stringResource(R.string.memory_v2_observer_tab)
}
