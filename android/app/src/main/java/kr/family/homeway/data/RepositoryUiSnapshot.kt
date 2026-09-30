package kr.family.homeway.data

/** Detached display data prepared on IO; composing a screen must never query the database. */
data class RepositoryUiSnapshot(
    val snapshot: FamilySnapshot,
    val careSnapshot: FamilySnapshot?,
    val role: String,
    val privateRole: String,
    val configured: Boolean,
    val demoMode: Boolean,
    val paired: Boolean,
    val room: FamilyChatRoom?,
    val selfBotId: Long,
    val botUsername: String,
    val peerBotUsername: String,
    val careEnabled: Boolean,
    val careChildren: List<FamilyChatMember>,
    val selectedChildBotId: Long?,
    val carePending: Boolean,
    val careStatus: String?,
    val sharingEnabled: Boolean,
    val trackingStatus: String,
    val connectionError: String?,
    val latestLocation: FamilyEvent? = null,
)
