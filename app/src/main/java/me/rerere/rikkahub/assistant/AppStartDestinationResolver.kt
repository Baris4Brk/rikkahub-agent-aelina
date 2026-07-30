package me.rerere.rikkahub.assistant

import kotlin.uuid.Uuid

sealed interface AppStartDestination {
    data class SecondUserConversation(val conversationId: Uuid) : AppStartDestination
    data object AuthorityRecovery : AppStartDestination
    data object LegacyDefault : AppStartDestination
}

/** Resolves only an implicit launcher start; explicit routes always win in RouteActivity. */
class AppStartDestinationResolver(
    private val authority: SecondUserAuthorityService,
) {
    suspend fun resolveImplicitStart(): AppStartDestination {
        // Avoid racing a fresh upgrade into a random chat before the legacy candidate is
        // converted to its fail-closed PENDING_CONFIRMATION authority record.
        authority.initializeLegacyMigration()
        return when (val state = authority.resolve()) {
        is SecondUserAuthorityResolution.Active ->
            AppStartDestination.SecondUserConversation(state.snapshot.conversationId)
        is SecondUserAuthorityResolution.Pending,
        is SecondUserAuthorityResolution.Invalid,
        -> AppStartDestination.AuthorityRecovery
        SecondUserAuthorityResolution.Unconfigured -> AppStartDestination.LegacyDefault
        }
    }
}
