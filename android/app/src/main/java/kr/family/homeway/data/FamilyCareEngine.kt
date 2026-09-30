package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Each child serializes its own praise board; both parents receive that child's committed state. */
class FamilyCareEngine(
    private val client: TelegramClient,
    private val store: FamilyCareStore,
    room: FamilyChatRoom,
    private val ownBotId: Long,
    private val lock: Any = Any(),
    private val now: () -> Long = System::currentTimeMillis,
    private val checkActive: () -> Unit = {},
    private val transport: FamilyTransport? = null,
    private val canSyncDeltas: (Long) -> Boolean = { false },
    private val canSendLatestLocation: (Long) -> Boolean = { false },
) {
    private val room = FamilyChatValidation.room(room)
    private val parents get() = room.members.filter { FamilyCareValidation.isParent(room, it.botId) }.map { it.botId }
    init { require(this.room.members.any { it.botId == ownBotId }) }
    var receivedAcknowledgements: Int = 0
        private set

    fun currentSnapshot(childId: Long): FamilyCareState? = synchronized(lock) {
        store.state(room.id, childId)?.let { it.copy(state = JSONObject(it.state.toString())) }
    }

    /** A location preview never substitutes for, or advances, the ordered praise-board state. */
    fun latestLocation(childId: Long): FamilyEvent? = synchronized(lock) {
        val current = store.state(room.id, childId)
        val cached = store.latestLocationHead(room.id, childId)?.takeIf { current == null || it.epoch == current.epoch }?.event
        val ordered = current?.state?.optJSONObject("latestLocation")?.let(FamilyEvent::parse)
        listOfNotNull(cached, ordered).maxByOrNull { Instant.parse(it.measuredAt) }
            ?.let { it.copy(payload = JSONObject(it.payload.toString())) }
    }

    /** A transport ACK alone never makes a parent command appear completed. */
    fun hasPending(childId: Long): Boolean = synchronized(lock) {
        val state = store.stateMetadata(room.id, childId)
        val outcomes = store.outcomes(room.id, childId).associateBy { it.commandId }
        store.commands(room.id, childId).filter { it.actorId == ownBotId }.any { command ->
            val outcome = outcomes[command.id]
            outcome == null || (outcome.accepted && (state == null || state.epoch != outcome.epoch || state.revision < outcome.revision))
        }
    }

    fun status(childId: Long): String? = synchronized(lock) {
        val latest = store.commands(room.id, childId).lastOrNull { it.actorId == ownBotId }
        val outcome = latest?.let { store.outcome(room.id, it.id) }
        store.error(room.id, childId) ?: when {
            store.stateMetadata(room.id, childId) == null -> "자녀의 칭찬판을 처음 동기화하고 있어요."
            hasPending(childId) -> "자녀 휴대폰에서 처리 결과를 확인하고 있어요."
            outcome?.accepted == false -> outcome.reason
            else -> null
        }
    }

    /** Only the child's existing paired ledger is a valid migration seed. Parent caches are replicas. */
    fun ensureAuthority(seed: JSONObject? = null): FamilyCareState = synchronized(lock) {
        require(FamilyCareValidation.isChild(room, ownBotId)) { "자녀 휴대폰에서 칭찬판을 시작할 수 있어요." }
        store.transaction {
            store.state(room.id, ownBotId)?.also { require(it.authoritative) } ?: run {
                val state = seedState(seed)
                val created = FamilyCareState(room.id, ownBotId, UUID.randomUUID().toString(), 0, true, state)
                val chunks = FamilyCareSnapshots.chunks(created)
                store.saveState(created)
                queueSnapshot(chunks, parents)
                archiveProjection(created)
                created
            }
        }
    }

    fun enqueueCommand(childId: Long, kind: String, payload: JSONObject,
        id: String = UUID.randomUUID().toString(), expectedRewardVersion: Long? = null): String = synchronized(lock) {
        store.transaction {
            val current = store.state(room.id, childId)
                ?: error("자녀의 칭찬판을 처음 동기화하고 있어요. 잠시 후 다시 시도해 주세요.")
            val rewardId = payload.optString("rewardId")
            val version = if (kind in setOf("reward_upsert", "reward_delete", "sticker_redeem_request"))
                expectedRewardVersion ?: current.rewardVersion(rewardId) else null
            val command = FamilyCareValidation.command(room, FamilyCareCommand(id, room.id, childId, ownBotId,
                current.epoch, kind, JSONObject(payload.toString()), Instant.ofEpochMilli(now()).toString(), version))
            store.saveCommand(command)
            store.setError(room.id, childId, null)
            if (ownBotId == childId) {
                require(current.authoritative)
                val outcome = executeCommand(command)
                if (!outcome.accepted) store.setError(room.id, childId, outcome.reason)
            } else {
                require(FamilyCareValidation.isParent(room, ownBotId))
                val packet = FamilyCareProtocol.outgoing(room, ownBotId, childId, childId, "command", command.json(), command.id)
                store.queuePacket(packet)
            }
            command.id
        }
    }

    /** A telemetry revision is sent only to parents and is durable even while both are offline. */
    fun emitChildEvent(input: FamilyEvent): FamilyCareState = synchronized(lock) {
        store.transaction {
            require(FamilyCareValidation.isChild(room, ownBotId) && input.kind in FamilyCareValidation.telemetryKinds)
            val event = TelegramLedger.validate(input.copy(sender = "child", delivery = "relayed"))
            val current = store.state(room.id, ownBotId) ?: ensureAuthority()
            require(current.authoritative)
            if (alreadyApplied(current, event)) {
                return@transaction current
            }
            val next = current.copy(revision = nextRevision(current), state = TelegramLedger.apply(current.state, event))
            val body = JSONObject().put("epoch", next.epoch).put("revision", next.revision).put("event", event.json())
            val delta = FamilyCareDelta.between(current, next, event)
            // Live telemetry keeps its established v4 form; history can repair financial gaps with deltas.
            val packets = parents.map { FamilyCareProtocol.outgoing(room, ownBotId, ownBotId, it, "child_event", body) }
            store.saveState(next)
            store.saveDelta(delta)
            store.archiveEvent(room.id, ownBotId, event)
            packets.forEach(store::queuePacket)
            if (event.kind == "location" && next.state.optJSONObject("latestLocation")?.optString("id") == event.id) {
                val head = FamilyCareLocationHead(room.id, ownBotId, next.epoch, next.revision, event)
                store.saveLatestLocationHead(head)
                parents.filter(canSendLatestLocation).forEach { peer ->
                    store.queueLatestLocationHead(FamilyCareProtocol.outgoing(room, ownBotId, ownBotId, peer, "care_location_head", head.json()))
                }
            }
            next
        }
    }

    fun requestSync(childId: Long) = synchronized(lock) { store.transaction { queueSync(childId) } }

    /** Called after valid legacy input, in the very same transaction as the v2 cache and receive offset. */
    fun processLegacy(input: FamilyEvent, peerId: Long): Boolean {
        if (!FamilyCareValidation.isChild(room, ownBotId) || !FamilyCareValidation.isParent(room, peerId) ||
            input.kind !in FamilyCareValidation.parentKinds) return true
        val current = store.state(room.id, ownBotId) ?: return false
        if (!current.authoritative) return false
        val event = TelegramLedger.validate(input.copy(sender = "guardian", delivery = "relayed"))
        try {
            if (alreadyApplied(current, event)) {
                return true
            }
            if (event.kind == "sticker_redeem_approve") require(current.snapshot.redemptions
                .firstOrNull { it.id == event.payload.getString("requestId") }?.status == "pending") {
                "이미 처리한 사용 요청이에요."
            }
            val nextState = TelegramLedger.apply(current.state, event)
            bumpRewardVersion(nextState, event.kind, event.payload)
            val next = current.copy(revision = nextRevision(current), state = nextState)
            FamilyCareSnapshots.validateSize(next)
            store.saveState(next)
            val delta = FamilyCareDelta.between(current, next, event)
            store.saveDelta(delta)
            queueCommittedChange(next, delta)
            return true
        } catch (_: IllegalArgumentException) {
            store.setError(room.id, ownBotId, "기존 1:1 칭찬 기록과 가족 칭찬판이 달라요. 기존 연결의 미전달 기록을 확인해 주세요.")
            return false
        }
    }

    /** The surrounding exchange transaction also commits its Telegram update offset. */
    fun processUpdate(update: JSONObject) {
        val packet = FamilyCareProtocol.receive(update, room, ownBotId) ?: return
        if (packet.type == "care_location_head") {
            if (acceptLocationHead(packet) != null) store.queueReceipt(FamilyCareReceipt(room.id, packet.id, packet.childId, packet.actorId, packet.digest))
            return
        }
        if (packet.type == "care_ack") {
            val digest = try {
                FamilyChatValidation.keys(packet.body, setOf("digest"))
                FamilyCareValidation.hash(packet.body.getString("digest"))
            } catch (_: IllegalArgumentException) { return }
            catch (_: org.json.JSONException) { return }
            val live = store.pendingLatestLocationHead(room.id, packet.id, packet.actorId)
            if (live != null && live.childId == packet.childId && live.digest == digest && live.sentAt > 0) {
                store.acknowledgeLatestLocationHead(room.id, packet.id, packet.actorId, digest)
                transport?.noteAcknowledgedPacket(packet.actorId, packet.id)
                return
            }
            // Storage failures must roll back the shared receive offset, not disappear as malformed input.
            val pending = store.pendingPacket(room.id, packet.id, packet.actorId)
            if (pending != null && pending.childId == packet.childId && pending.digest == digest && pending.sentAt > 0) {
                store.acknowledge(room.id, packet.id, packet.actorId, digest)
                store.setRetryAfter(room.id, packet.actorId, 0)
                transport?.noteAcknowledgedPacket(packet.actorId, packet.id)
                receivedAcknowledgements++
            }
            return
        }
        val prior = store.receivedDigest(room.id, packet.id)
        if (prior != null && prior != packet.digest) return
        if (prior != null) transport?.noteReplayedPacket(packet.actorId, packet.id)
        if (prior == null) {
            val accepted = try {
                when (packet.type) {
                    "command" -> {
                        val command = runCatching { FamilyCareCommand.parse(packet.body) }.getOrNull()
                        if (command == null) rejectMalformedCommand(packet)
                        else {
                            require(command.roomId == room.id && command.childId == packet.childId && command.actorId == packet.actorId)
                            executeCommand(command)
                        }
                    }
                    "sync_request" -> {
                        FamilyChatValidation.keys(packet.body, emptySet())
                        val state = store.state(room.id, ownBotId) ?: ensureAuthority()
                        require(state.authoritative)
                        queueSnapshot(FamilyCareSnapshots.chunks(state), listOf(packet.actorId))
                    }
                    "care_sync" -> receiveSyncRequest(packet)
                    "care_delta" -> receiveDelta(FamilyCareDelta.parse(room.id, packet.childId, packet.body))
                    "snapshot_chunk" -> receiveChunk(FamilyCareSnapshots.parseChunk(room.id, packet.childId, packet.body))
                    "outcome" -> {
                        val outcome = FamilyCareOutcome.parse(packet.body)
                        require(outcome.roomId == room.id && outcome.childId == packet.childId && outcome.actorId == ownBotId)
                        val command = store.commands(room.id, packet.childId).firstOrNull { it.id == outcome.commandId }
                        val existing = store.state(room.id, packet.childId)
                        if (command == null) Unit // A restored parent may no longer retain this old request.
                        else if (command.digest != outcome.commandDigest) throw IllegalArgumentException("요청 내용이 달라요.")
                        else if (existing != null && existing.epoch != outcome.epoch) epochError(packet.childId)
                        else {
                            store.saveOutcome(outcome)
                            if (!outcome.accepted) store.setError(room.id, packet.childId, outcome.reason ?: "요청을 처리하지 못했어요.")
                        }
                    }
                    "child_event" -> receiveChildEvent(packet)
                }
                true
            } catch (_: IllegalArgumentException) {
                store.setError(room.id, packet.childId, "받은 가족 기록의 형식을 확인하지 못했어요.")
                false
            } catch (_: org.json.JSONException) {
                store.setError(room.id, packet.childId, "받은 가족 기록의 형식을 확인하지 못했어요.")
                false
            }
            if (!accepted) return
            store.recordReceived(room.id, packet.id, packet.digest)
        }
        store.queueReceipt(FamilyCareReceipt(room.id, packet.id, packet.childId, packet.actorId, packet.digest))
    }

    /** Used before a blocked file download: no cursor, finance, replay index or historical ACK changes. */
    fun processLatestPreview(update: JSONObject): Boolean {
        val packet = FamilyCareProtocol.receive(update, room, ownBotId) ?: return false
        if (packet.type != "care_location_head") return false
        return acceptLocationHead(packet) == true
    }

    private fun acceptLocationHead(packet: FamilyCareProtocol.Packet): Boolean? {
        val head = try { FamilyCareLocationHead.parse(room.id, packet.childId, packet.body) }
            catch (_: IllegalArgumentException) { return null }
            catch (_: org.json.JSONException) { return null }
        val known = store.stateMetadata(room.id, packet.childId)
        if (known != null && known.epoch != head.epoch) return null
        val prior = store.latestLocationHead(room.id, packet.childId)
        if (prior != null && prior.epoch != head.epoch && known == null) return null
        if (prior != null && prior.epoch == head.epoch && prior.revision == head.revision && prior.eventDigest != head.eventDigest) return null
        val eventDigest = TelegramLedger.eventDigest(head.event)
        val replayDigest = store.eventDigest(room.id, packet.childId, head.event.id)
        val archivedDigest = store.movementEventDigest(room.id, packet.childId, head.event.id)
        if ((replayDigest != null && replayDigest != eventDigest) || (archivedDigest != null && archivedDigest != eventDigest)) return null
        val changed = store.saveLatestLocationHead(head)
        store.archiveEvent(room.id, packet.childId, head.event)
        return changed
    }

    private fun rejectMalformedCommand(packet: FamilyCareProtocol.Packet) {
        val current = store.state(room.id, ownBotId) ?: ensureAuthority()
        val id = runCatching { FamilyChatValidation.identifier(packet.body.getString("id")) }.getOrDefault(packet.id)
        val outcome = FamilyCareOutcome(room.id, ownBotId, id, packet.actorId, FamilyCareValidation.digest(packet.body),
            current.epoch, current.revision, false, "요청 형식을 확인하지 못했어요. 앱을 업데이트하고 다시 시도해 주세요.")
        val previous = store.outcome(room.id, id)
        require(previous == null || previous == outcome)
        if (previous == null) store.saveOutcome(outcome)
        queueOutcome(outcome)
    }

    private fun executeCommand(input: FamilyCareCommand): FamilyCareOutcome {
        val prior = store.outcome(room.id, input.id)
        if (prior != null) {
            require(prior.commandDigest == input.digest && prior.actorId == input.actorId && prior.childId == input.childId) {
                "같은 요청의 내용이 달라졌어요."
            }
            queueOutcome(prior)
            return prior
        }
        val current = store.state(room.id, input.childId) ?: ensureAuthority()
        require(input.childId == ownBotId && current.authoritative)
        var delta: FamilyCareDelta? = null
        var next: FamilyCareState? = null
        val failure = try {
            val command = FamilyCareValidation.command(room, input)
            require(command.baseEpoch == current.epoch) { "자녀의 칭찬판이 다시 시작되었어요. 새 가족방 코드로 함께 연결해 주세요." }
            validateTransition(current, command)
            val event = FamilyEvent(command.id, command.kind, command.payload,
                if (command.kind in FamilyCareValidation.parentKinds) "guardian" else "child", command.createdAt, "relayed")
            val alreadyApplied = alreadyApplied(current, event)
            val nextState = if (alreadyApplied) current.state else TelegramLedger.apply(current.state, event)
            if (!alreadyApplied) bumpRewardVersion(nextState, event.kind, event.payload)
            next = current.copy(revision = if (alreadyApplied) current.revision else nextRevision(current), state = nextState)
            FamilyCareSnapshots.validateSize(next!!)
            if (!alreadyApplied) delta = FamilyCareDelta.between(current, next!!, event)
            null
        } catch (error: IllegalArgumentException) { error.message?.take(240) ?: "이 요청을 처리할 수 없어요." }
        catch (_: org.json.JSONException) { "요청 형식을 확인하지 못했어요. 칭찬판에서 다시 시도해 주세요." }
        val outcome = FamilyCareOutcome(room.id, input.childId, input.id, input.actorId, input.digest,
            current.epoch, next?.revision?.takeIf { failure == null } ?: current.revision, failure == null, failure)
        if (failure == null) {
            store.saveState(next!!)
            delta?.let(store::saveDelta)
            store.setError(room.id, input.childId, null)
        }
        store.saveOutcome(outcome)
        queueOutcome(outcome)
        if (failure == null) queueCommittedChange(next!!, delta)
        return outcome
    }

    private fun validateTransition(current: FamilyCareState, command: FamilyCareCommand) {
        val payload = command.payload
        if (command.kind in setOf("reward_upsert", "reward_delete", "sticker_redeem_request")) {
            require(command.expectedRewardVersion == current.rewardVersion(payload.getString("rewardId"))) {
                "다른 가족이 보상을 변경했어요. 최신 칭찬판에서 다시 확인해 주세요."
            }
        }
        if (command.kind == "sticker_redeem_request") {
            val reward = current.snapshot.rewards.firstOrNull { it.id == payload.getString("rewardId") }
            require(reward != null && reward.name == payload.getString("reward") && reward.cost == payload.getInt("cost")) {
                "보상이 변경되었어요. 최신 칭찬판에서 다시 선택해 주세요."
            }
        }
        if (command.kind == "sticker_redeem_approve") {
            val request = current.snapshot.redemptions.firstOrNull { it.id == payload.getString("requestId") }
            require(request != null) { "사용 요청을 찾지 못했어요. 칭찬판을 동기화한 뒤 다시 확인해 주세요." }
            require(request.status == "pending") { "다른 부모님이 이미 이 사용 요청을 처리했어요." }
        }
    }

    private fun receiveChunk(chunk: FamilyCareChunk) {
        val current = store.state(room.id, chunk.childId)
        require(current?.authoritative != true)
        if (current != null && current.epoch != chunk.epoch) { epochError(chunk.childId); return }
        if (current != null && chunk.revision < current.revision) return
        store.putSnapshotChunk(chunk)
        val parts = store.snapshotChunks(room.id, chunk.transferId)
        if (parts.size != chunk.count) return
        val snapshot = FamilyCareSnapshots.assemble(parts)
        if (current == null || snapshot.revision > current.revision) {
            store.saveState(snapshot)
            store.setError(room.id, chunk.childId, null)
            archiveProjection(snapshot)
        } else if (FamilyCareValidation.digest(FamilyCareSnapshots.projection(current.state)) != FamilyCareValidation.digest(snapshot.state)) {
            store.setError(room.id, chunk.childId, "같은 순서의 칭찬 기록이 달라요. 새 가족방 코드로 함께 연결해 주세요.")
        }
        store.removeSnapshotChunks(room.id, chunk.transferId)
    }

    private fun receiveChildEvent(packet: FamilyCareProtocol.Packet) {
        FamilyChatValidation.keys(packet.body, setOf("epoch", "revision", "event"))
        val epoch = FamilyChatValidation.identifier(packet.body.getString("epoch"))
        val revision = FamilyCareValidation.counter(packet.body.opt("revision"))
        val event = TelegramLedger.validate(FamilyEvent.parse(packet.body.getJSONObject("event")))
        require(event.sender == "child" && event.kind in FamilyCareValidation.telemetryKinds)
        val current = store.state(room.id, packet.childId)
        if (current != null && current.epoch != epoch) { epochError(packet.childId); return }
        val duplicate = current != null && alreadyApplied(current, event)
        store.archiveEvent(room.id, packet.childId, event)
        if (current == null || revision > current.revision + 1) { queueSync(packet.childId); return }
        require(!current.authoritative)
        if (revision <= current.revision) return
        store.saveState(current.copy(revision = revision, state = if (duplicate) current.state else TelegramLedger.apply(current.state, event)))
    }

    private fun receiveSyncRequest(packet: FamilyCareProtocol.Packet) {
        FamilyChatValidation.keys(packet.body, setOf("epoch", "revision", "digest"))
        val epoch = packet.body.opt("epoch").let { if (it == JSONObject.NULL) null else { require(it is String); FamilyChatValidation.identifier(it) } }
        val revision = FamilyCareValidation.counter(packet.body.opt("revision"))
        val digest = packet.body.opt("digest").let { if (it == JSONObject.NULL) null else { require(it is String); FamilyCareValidation.hash(it) } }
        require((epoch == null) == (digest == null) && (epoch != null || revision == 0L))
        val state = store.state(room.id, ownBotId) ?: ensureAuthority()
        require(state.authoritative)
        val currentDigest = FamilyCareDelta.projectionDigest(state)
        if (epoch == state.epoch && revision == state.revision && digest == currentDigest) return
        val missing = state.revision - revision
        val history = if (canSyncDeltas(packet.actorId) && epoch == state.epoch && missing in 1..FamilyCareDelta.HISTORY_LIMIT.toLong())
            store.deltas(room.id, ownBotId, state.epoch, revision, FamilyCareDelta.HISTORY_LIMIT) else emptyList()
        var expectedRevision = revision
        var expectedDigest = digest
        val contiguous = history.size.toLong() == missing && history.all { delta ->
            val matches = delta.baseRevision == expectedRevision && delta.baseDigest == expectedDigest
            expectedRevision = delta.revision
            expectedDigest = delta.resultDigest
            matches
        } && expectedRevision == state.revision && expectedDigest == currentDigest
        val packets = if (contiguous) history.map { deltaPacket(it, packet.actorId) } else emptyList()
        if (contiguous && packets.all { it != null }) packets.forEach { store.queuePacket(requireNotNull(it)) }
        else queueSnapshot(FamilyCareSnapshots.chunks(state), listOf(packet.actorId))
    }

    private fun receiveDelta(delta: FamilyCareDelta) {
        val current = store.state(room.id, delta.childId)
        require(current?.authoritative != true)
        if (current == null) { queueSync(delta.childId, forceSnapshot = true); return }
        if (current.epoch != delta.epoch) { epochError(delta.childId); return }
        if (delta.revision <= current.revision) {
            // A full snapshot may have overtaken an older delta; its authenticated timeline is still useful.
            alreadyApplied(current, delta.event)
            if (delta.revision == current.revision && FamilyCareDelta.projectionDigest(current) != delta.resultDigest) {
                store.setError(room.id, delta.childId, "같은 순서의 칭찬 기록이 달라요. 가족 연결을 확인해 주세요.")
                queueSync(delta.childId, forceSnapshot = true)
                return
            }
            store.recordEventDigest(room.id, delta.childId, delta.event.id, TelegramLedger.eventDigest(delta.event))
            store.archiveEvent(room.id, delta.childId, delta.event)
            return
        }
        if (delta.baseRevision != current.revision) { queueSync(delta.childId); return }
        if (delta.baseDigest != FamilyCareDelta.projectionDigest(current)) {
            store.setError(room.id, delta.childId, "칭찬판의 기준 기록이 달라요. 전체 기록을 다시 확인하고 있어요.")
            queueSync(delta.childId, forceSnapshot = true)
            return
        }
        if (alreadyApplied(current, delta.event)) {
            store.setError(room.id, delta.childId, "이미 반영한 변경 기록의 순서가 달라요. 전체 기록을 다시 확인하고 있어요.")
            queueSync(delta.childId, forceSnapshot = true)
            return
        }
        val applied = TelegramLedger.apply(current.state, delta.event)
        bumpRewardVersion(applied, delta.event.kind, delta.event.payload)
        val projected = FamilyCareSnapshots.projection(applied)
        if (FamilyCareValidation.digest(projected) != delta.resultDigest) {
            store.setError(room.id, delta.childId, "변경 기록의 결과가 달라요. 전체 기록을 다시 확인하고 있어요.")
            queueSync(delta.childId, forceSnapshot = true)
            return
        }
        // Replay identities are persisted separately, even though the parent projection is bounded.
        projected.put("appliedEventIds", applied.optJSONObject("appliedEventIds") ?: JSONObject())
        store.saveState(current.copy(revision = delta.revision, state = projected))
        store.recordEventDigest(room.id, delta.childId, delta.event.id, TelegramLedger.eventDigest(delta.event))
        store.archiveEvent(room.id, delta.childId, delta.event)
        store.setError(room.id, delta.childId, null)
    }

    private fun archiveProjection(state: FamilyCareState) {
        state.snapshot.events.filter { it.kind in FamilyCareValidation.telemetryKinds }.distinctBy { it.id }
            .forEach { store.archiveEvent(room.id, state.childId, it) }
    }

    private fun queueSync(childId: Long, forceSnapshot: Boolean = false) {
        require(FamilyCareValidation.isParent(room, ownBotId) && FamilyCareValidation.isChild(room, childId))
        if (store.hasPendingKind(room.id, childId, "sync_request")) return
        if (!forceSnapshot && canSyncDeltas(childId)) {
            if (store.hasPendingKind(room.id, childId, "care_sync")) return
            val current = store.state(room.id, childId)
            val body = JSONObject().put("epoch", current?.epoch ?: JSONObject.NULL).put("revision", current?.revision ?: 0L)
                .put("digest", current?.let(FamilyCareDelta::projectionDigest) ?: JSONObject.NULL)
            store.queuePacket(FamilyCareProtocol.outgoing(room, ownBotId, childId, childId, "care_sync", body))
        } else store.queuePacket(FamilyCareProtocol.outgoing(room, ownBotId, childId, childId, "sync_request", JSONObject()))
    }

    private fun deltaPacket(delta: FamilyCareDelta, peer: Long): FamilyCareOutgoing? = runCatching {
        FamilyCareProtocol.outgoing(room, ownBotId, delta.childId, peer, "care_delta", delta.json())
    }.getOrNull()

    private fun queueCommittedChange(state: FamilyCareState, delta: FamilyCareDelta?) {
        var chunks: List<FamilyCareChunk>? = null
        for (peer in parents) {
            val packet = delta?.takeIf { canSyncDeltas(peer) }?.let { deltaPacket(it, peer) }
            if (packet != null) store.queuePacket(packet)
            else {
                if (chunks == null) chunks = FamilyCareSnapshots.chunks(state)
                queueSnapshot(requireNotNull(chunks), listOf(peer))
            }
        }
    }

    private fun queueSnapshot(chunks: List<FamilyCareChunk>, recipients: List<Long>) {
        for (peer in recipients) for (chunk in chunks) store.queuePacket(FamilyCareProtocol.outgoing(room,
            ownBotId, chunk.childId, peer, "snapshot_chunk", FamilyCareSnapshots.chunkJson(chunk)))
    }

    private fun queueOutcome(outcome: FamilyCareOutcome) {
        if (outcome.actorId != ownBotId) store.queuePacket(FamilyCareProtocol.outgoing(room, ownBotId,
            outcome.childId, outcome.actorId, "outcome", outcome.json()))
    }

    private fun epochError(childId: Long) = store.setError(room.id, childId,
        "자녀의 칭찬판이 다시 시작되었어요. 기록을 덮어쓰지 않았어요. 새 가족방 코드로 함께 연결해 주세요.")

    private fun nextRevision(state: FamilyCareState): Long = FamilyCareValidation.counter(state.revision + 1)

    private fun alreadyApplied(state: FamilyCareState, event: FamilyEvent): Boolean {
        val prior = store.eventDigest(room.id, state.childId, event.id)
            ?: state.state.optJSONObject("appliedEventIds")?.optString(event.id)?.takeIf { it.isNotBlank() }
            ?: return false
        require(prior == TelegramLedger.eventDigest(event)) { "같은 기록의 내용이 달라졌어요. 연결을 확인해 주세요." }
        return true
    }

    private fun bumpRewardVersion(state: JSONObject, kind: String, payload: JSONObject) {
        if (kind !in setOf("reward_upsert", "reward_delete")) return
        val versions = state.optJSONObject("careRewardVersions") ?: JSONObject().also { state.put("careRewardVersions", it) }
        val id = payload.getString("rewardId")
        versions.put(id, FamilyCareValidation.counter(versions.optLong(id, 0) + 1))
    }

    private fun seedState(seed: JSONObject?): JSONObject {
        if (seed == null) return TelegramLedger.emptyState().put("careRewardVersions", JSONObject())
        val source = JSONObject(seed.toString())
        val state = TelegramLedger.emptyState()
        require(source.optInt("stickerBalance") >= 0)
        state.put("stickerBalance", source.optInt("stickerBalance"))
        state.put("sharingEnabled", source.optBoolean("sharingEnabled"))
        listOf("redemptions", "rewards").forEach { key -> state.put(key, source.optJSONArray(key) ?: JSONArray()) }
        state.put("appliedEventIds", source.optJSONObject("appliedEventIds") ?: JSONObject())
        val events = source.optJSONArray("events") ?: JSONArray()
        state.put("events", JSONArray((0 until events.length()).map { events.getJSONObject(it) }.filter { it.optString("kind") != "chat" }))
        listOf("latestLocation", "latestHeartbeat").forEach { key -> source.optJSONObject(key)?.let { state.put(key, it) } }
        val versions = source.optJSONObject("careRewardVersions") ?: JSONObject()
        val rewards = state.getJSONArray("rewards")
        for (i in 0 until rewards.length()) {
            val id = rewards.getJSONObject(i).getString("id")
            if (!versions.has(id)) versions.put(id, 1L)
        }
        state.put("careRewardVersions", versions)
        return state
    }

    fun hasReadyWork(): Boolean = synchronized(lock) {
        if (readyLatestLocations().isNotEmpty()) return@synchronized true
        val peers = store.pendingPeers(room.id)
        peers.any { peer -> store.retryAfter(room.id, peer) <= now() &&
            (store.firstReceipt(room.id, peer) != null || nextSendable(store.pendingPackets(room.id, peer, outgoingWindow(peer))) != null)
        }
    }

    fun hasReadyReceipts(): Boolean = synchronized(lock) {
        store.pendingPeers(room.id).any { peer -> store.retryAfter(room.id, peer) <= now() && store.firstReceipt(room.id, peer) != null }
    }

    /** Confirmed HTTP sends may await ACK together; an ambiguous attempt blocks everything after it. */
    private fun nextSendable(packets: List<FamilyCareOutgoing>, sentThisFlush: Set<String> = emptySet()): FamilyCareOutgoing? {
        for (packet in packets.take(FamilyTransportProtocol.MAX_FILE_PACKETS)) {
            if (packet.packetId in sentThisFlush) continue
            if (packet.sentAt == 0L) return packet
            val retryDue = now() - packet.sentAt >= RETRY_MILLIS
            if (!packet.sendConfirmed) return packet.takeIf { retryDue }
            if (retryDue) return packet
        }
        return null
    }

    /** A slow family member cannot block the other parent, another child, or the v2/v3 lanes. */
    fun flush() {
        val peers = synchronized(lock) { store.pendingPeers(room.id) }
        for (peer in peers) {
            checkActive()
            val member = room.members.firstOrNull { it.botId == peer } ?: continue
            if (synchronized(lock) { store.retryAfter(room.id, peer) > now() }) continue
            try {
                if (transport != null) {
                    flushTransport(member, transport)
                    continue
                }
                for (index in 0 until MAX_RECEIPTS_PER_FLUSH) {
                    val receipt = synchronized(lock) { store.firstReceipt(room.id, peer) } ?: break
                    checkActive()
                    client.sendToFamilyMember(member, FamilyCareProtocol.receipt(room, ownBotId, receipt), checkActive)
                    synchronized(lock) { store.transaction { store.removeReceipt(receipt) } }
                }
                val sentThisFlush = mutableSetOf<String>()
                for (index in 0 until MAX_PACKETS_PER_FLUSH) {
                    val packet = synchronized(lock) { nextSendable(store.pendingPackets(room.id, peer, MAX_UNACKNOWLEDGED_PER_PEER), sentThisFlush) } ?: break
                    synchronized(lock) { store.transaction { store.markSent(room.id, packet.packetId, peer, now().coerceAtLeast(1)) } }
                    checkActive()
                    client.sendToFamilyMember(member, packet.text, checkActive)
                    synchronized(lock) { store.transaction { store.markSendConfirmed(room.id, packet.packetId, peer) } }
                    // Slow HTTP/peer resolution can outlast the retry interval. A successful
                    // packet must still consume only one slot in this flush's bounded budget.
                    sentThisFlush += packet.packetId
                }
            } catch (error: TelegramException) {
                if (error.errorCode in setOf(401, 404, 409, 429)) throw error
                synchronized(lock) { store.transaction { store.setRetryAfter(room.id, peer, now() + 15_000) } }
            }
        }
    }

    private fun flushTransport(member: FamilyChatMember, transport: FamilyTransport) {
        val peer = member.botId
        synchronized(lock) { store.transaction { prepareCompatibilityFallbacks(peer, transport) } }
        val queuedReceipts = synchronized(lock) { store.receipts(room.id, peer, outgoingWindow(peer)) }
        val receipts = queuedReceipts.take(transport.batchableAcknowledgementCount(peer, queuedReceipts.map { it.packetId }))
        if (receipts.isNotEmpty()) transport.send(member, receipts.map { FamilyCareProtocol.receipt(room, ownBotId, it) },
            allowBatch = transport.canBatchAcknowledgements(peer, receipts.map { it.packetId }), afterSend = { count ->
            synchronized(lock) { store.transaction { receipts.take(count).forEach(store::removeReceipt) } }
        })
        val candidates = synchronized(lock) { store.pendingPackets(room.id, peer, outgoingWindow(peer)) }
        val first = nextSendable(candidates) ?: return
        // Preserve FIFO, but include every due original in a retry batch. The transport independently
        // falls back for peers that stop acknowledging batches, without changing original identities.
        val packets = candidates.dropWhile { it.packetId != first.packetId }
            .takeWhile { it.sentAt == 0L || now() - it.sentAt >= RETRY_MILLIS }
        transport.send(member, packets.map { it.text }, allowBatch = true, beforeSend = { count ->
            synchronized(lock) { store.transaction {
                packets.take(count).forEach { store.markSent(room.id, it.packetId, peer, now().coerceAtLeast(1)) }
            } }
        }, afterSend = { count ->
            synchronized(lock) { store.transaction {
                packets.take(count).forEach { store.markSendConfirmed(room.id, it.packetId, peer) }
            } }
        })
    }

    private fun outgoingWindow(peer: Long): Int = if (transport?.supportsFiles(peer) == true)
        FamilyTransportProtocol.MAX_FILE_PACKETS else MAX_UNACKNOWLEDGED_PER_PEER

    private fun readyLatestLocations(): List<FamilyCareOutgoing> = store.pendingLatestLocationHeads(room.id).filter { packet ->
        val last = store.latestLocationSentAt(room.id, packet.peerId)
        canSendLatestLocation(packet.peerId) && store.retryAfter(room.id, packet.peerId) <= now() &&
            (last == 0L || now() < last || now() - last >= LATEST_LOCATION_MIN_INTERVAL_MILLIS) &&
            (packet.sentAt == 0L || now() < packet.sentAt || now() - packet.sentAt >= RETRY_MILLIS)
    }

    /** Called before chat/history lanes. A persisted five-second limit leaves them repeated send turns. */
    fun flushLatestLocations() {
        val sender = transport ?: return
        synchronized(lock) { store.transaction { seedLatestLocations() } }
        val packets = synchronized(lock) { readyLatestLocations() }
        for (packet in packets) {
            checkActive()
            if (sender.shouldFallbackLatestLocation(packet.peerId)) {
                sender.noteLatestLocationFallback(packet.peerId)
                continue
            }
            val member = room.members.firstOrNull { it.botId == packet.peerId } ?: continue
            try {
                sender.send(member, listOf(packet.text), allowBatch = false, beforeSend = {
                    synchronized(lock) { store.transaction { store.markLatestLocationSent(room.id, packet.packetId, packet.peerId, now().coerceAtLeast(1)) } }
                }, afterSend = {
                    synchronized(lock) { store.transaction { store.markLatestLocationConfirmed(room.id, packet.packetId, packet.peerId) } }
                })
            } catch (error: TelegramException) {
                if (error.errorCode in setOf(401, 404, 409, 429)) throw error
                synchronized(lock) { store.transaction { store.setRetryAfter(room.id, packet.peerId, now() + 15_000) } }
            }
        }
    }

    /** A newly negotiated parent receives the already known position, including just after an upgrade. */
    private fun seedLatestLocations() {
        if (!FamilyCareValidation.isChild(room, ownBotId)) return
        val recipients = parents.filter(canSendLatestLocation)
        if (recipients.isEmpty()) return
        val metadata = store.stateMetadata(room.id, ownBotId) ?: return
        if (!metadata.authoritative) return
        val cached = store.latestLocationHead(room.id, ownBotId)?.takeIf { it.epoch == metadata.epoch }
        val head = cached ?: store.state(room.id, ownBotId)?.state?.optJSONObject("latestLocation")?.let { location ->
            FamilyCareLocationHead(room.id, ownBotId, metadata.epoch, metadata.revision,
                TelegramLedger.validate(FamilyEvent.parse(location).copy(delivery = "relayed")))
        }?.also(store::saveLatestLocationHead) ?: return
        val pending = store.pendingLatestLocationHeads(room.id).filter { it.childId == ownBotId }.associateBy { it.peerId }
        for (peer in recipients) {
            val active = pending[peer]?.let { FamilyCareLocationHead.parse(room.id, ownBotId, JSONObject(it.text).getJSONObject("body")) }
            if (active?.epoch == head.epoch && active.eventDigest == head.eventDigest) continue
            val acknowledged = store.acknowledgedLatestLocationHead(room.id, ownBotId, peer)
            if (acknowledged?.epoch == head.epoch && acknowledged.eventDigest == head.eventDigest) continue
            store.queueLatestLocationHead(FamilyCareProtocol.outgoing(room, ownBotId, ownBotId, peer, "care_location_head", head.json()))
        }
    }

    private fun prepareCompatibilityFallbacks(peer: Long, transport: FamilyTransport) {
        val originals = store.packetsNeedingCompatibility(room.id, peer, FamilyCareDelta.HISTORY_LIMIT)
            .filter { !canSyncDeltas(peer) || (it.sentAt > 0 && now() - it.sentAt >= RETRY_MILLIS && transport.shouldFallbackCarePacket(peer, it.packetId)) }
        if (originals.isEmpty()) return
        transport.noteCareDeltaFallback(peer)
        val checkpoints = mutableMapOf<Long, List<FamilyCareOutgoing>>()
        for (original in originals) {
            val json = JSONObject(original.text)
            val replacements = if (json.optString("type") == "care_sync") {
                listOf(FamilyCareProtocol.outgoing(room, ownBotId, original.childId, peer, "sync_request", JSONObject()))
            } else {
                val delta = FamilyCareDelta.parse(room.id, original.childId, json.getJSONObject("body"))
                if (delta.event.kind in FamilyCareValidation.telemetryKinds) {
                    listOf(FamilyCareProtocol.outgoing(room, ownBotId, delta.childId, peer, "child_event", JSONObject()
                        .put("epoch", delta.epoch).put("revision", delta.revision).put("event", delta.event.json())))
                } else checkpoints.getOrPut(delta.childId) {
                    val state = requireNotNull(store.state(room.id, delta.childId))
                    require(state.authoritative && state.epoch == delta.epoch && state.revision >= delta.revision)
                    FamilyCareSnapshots.chunks(state).map { chunk -> FamilyCareProtocol.outgoing(room, ownBotId,
                        delta.childId, peer, "snapshot_chunk", FamilyCareSnapshots.chunkJson(chunk)) }
                }
            }
            replacements.forEach(store::queuePacket)
            store.replacePacket(original, replacements)
        }
    }

    companion object {
        private const val MAX_UNACKNOWLEDGED_PER_PEER = 16
        private const val MAX_PACKETS_PER_FLUSH = 8
        private const val MAX_RECEIPTS_PER_FLUSH = 8
        private const val RETRY_MILLIS = 30_000L
        private const val LATEST_LOCATION_MIN_INTERVAL_MILLIS = 5_000L
    }
}
