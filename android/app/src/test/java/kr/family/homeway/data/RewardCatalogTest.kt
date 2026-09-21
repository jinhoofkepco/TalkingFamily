package kr.family.homeway.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RewardCatalogTest {
    private val rewardId = "11111111-1111-4111-8111-111111111111"
    private fun upsert(name: String = "함께 산책", cost: Int = 3) =
        JSONObject().put("rewardId", rewardId).put("name", name).put("cost", cost)
    private fun request(reward: Reward) = JSONObject().put("rewardId", reward.id).put("reward", reward.name).put("cost", reward.cost)

    @Test fun `delivery failure reason remains available after caching`() {
        val event = FamilyEvent(rewardId, "reward_upsert", upsert(), "guardian", "2026-09-21T06:00:00Z", "failed", "약속이 변경되었어요.")
        val parsed = FamilyEvent.parse(JSONObject(event.json().toString()))
        assertEquals(event.id, parsed.id)
        assertEquals("failed", parsed.delivery)
        assertEquals("약속이 변경되었어요.", parsed.deliveryError)
        val oldJson = event.json().apply { remove("deliveryError") }
        assertNull(FamilyEvent.parse(oldJson).deliveryError)
    }

    @Test fun `old cache parses and old demo seeds only a missing catalog`() {
        val old = DemoState.initial().apply { remove("rewards") }
        assertTrue(FamilySnapshot.parse(old).rewards.isEmpty())
        assertEquals(2, FamilySnapshot.parse(DemoState.migrate(old)).rewards.size)
        old.put("rewards", JSONArray())
        assertTrue(FamilySnapshot.parse(DemoState.migrate(old)).rewards.isEmpty())
    }

    @Test fun `reward creation edit and deletion survive JSON round trip`() {
        var state = DemoState.apply(DemoState.initial(), "reward_upsert", upsert("  함께 산책  "), "guardian")
        assertEquals(Reward(rewardId, "함께 산책", 3), FamilySnapshot.parse(JSONObject(state.toString())).rewards.last())
        state = DemoState.apply(state, "reward_upsert", upsert("공원 나들이", 7), "guardian")
        val rewards = FamilySnapshot.parse(state).rewards
        assertEquals(3, rewards.size)
        assertEquals(Reward(rewardId, "공원 나들이", 7), rewards.last())
        state = DemoState.apply(state, "reward_delete", JSONObject().put("rewardId", rewardId), "guardian")
        assertEquals(2, FamilySnapshot.parse(state).rewards.size)
    }

    @Test fun `only guardian can manage catalog and grant or approve stickers`() {
        val state = DemoState.initial()
        for (kind in listOf("reward_upsert", "reward_delete", "sticker_award", "sticker_redeem_approve")) {
            assertThrows(IllegalArgumentException::class.java) { DemoState.apply(state, kind, upsert(), "child") }
        }
        val reward = FamilySnapshot.parse(state).rewards.first()
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sticker_redeem_request", request(reward), "guardian")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sharing_status", JSONObject().put("enabled", false), "guardian")
        }
    }

    @Test fun `pending request retains selected promise after edits and deletion`() {
        var state = DemoState.initial()
        val original = FamilySnapshot.parse(state).rewards.first()
        state = DemoState.apply(state, "sticker_redeem_request", request(original), "child")
        val pending = FamilySnapshot.parse(state).redemptions.single()
        assertEquals(8, FamilySnapshot.parse(state).stickerBalance)
        state = DemoState.apply(state, "reward_upsert", JSONObject().put("rewardId", original.id).put("name", "새 약속").put("cost", 7), "guardian")
        state = DemoState.apply(state, "reward_delete", JSONObject().put("rewardId", original.id), "guardian")
        assertEquals(pending, FamilySnapshot.parse(state).redemptions.single())
        state = DemoState.apply(state, "sticker_redeem_approve", JSONObject().put("requestId", pending.id).put("accepted", true), "guardian")
        assertEquals(3, FamilySnapshot.parse(state).stickerBalance)
        assertEquals(pending.copy(status = "approved"), FamilySnapshot.parse(state).redemptions.single())
        val completed = state.toString()
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sticker_redeem_approve", JSONObject().put("requestId", pending.id).put("accepted", true), "guardian")
        }
        assertEquals(completed, state.toString())
    }

    @Test fun `stale or fabricated requests leave state untouched`() {
        var state = DemoState.initial()
        val original = FamilySnapshot.parse(state).rewards.first()
        state = DemoState.apply(state, "reward_upsert", JSONObject().put("rewardId", original.id).put("name", "새 아이스크림").put("cost", 6), "guardian")
        val before = state.toString()
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sticker_redeem_request", request(original), "child")
        }
        assertThrows(IllegalStateException::class.java) {
            DemoState.apply(state, "sticker_redeem_request", request(original.copy(id = rewardId)), "child")
        }
        assertEquals(before, state.toString())
    }

    @Test fun `approval rechecks balance across multiple pending requests`() {
        var state = DemoState.initial()
        val reward = FamilySnapshot.parse(state).rewards.first()
        repeat(2) { state = DemoState.apply(state, "sticker_redeem_request", request(reward), "child") }
        val pending = FamilySnapshot.parse(state).redemptions
        state = DemoState.apply(state, "sticker_redeem_approve", JSONObject().put("requestId", pending.first().id).put("accepted", true), "guardian")
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sticker_redeem_approve", JSONObject().put("requestId", pending.last().id).put("accepted", true), "guardian")
        }
        state = DemoState.apply(state, "sticker_redeem_approve", JSONObject().put("requestId", pending.last().id).put("accepted", false), "guardian")
        assertEquals(3, FamilySnapshot.parse(state).stickerBalance)
        assertEquals("rejected", FamilySnapshot.parse(state).redemptions.last().status)
    }

    @Test fun `reward bounds prevent empty names and invalid sticker costs`() {
        val state = DemoState.initial()
        for (payload in listOf(upsert(" "), upsert("a".repeat(61)), upsert(cost = 0), upsert(cost = 1000))) {
            assertThrows(IllegalArgumentException::class.java) { DemoState.apply(state, "reward_upsert", payload, "guardian") }
        }
        assertEquals(2, FamilySnapshot.parse(state).rewards.size)
    }

    @Test fun `initial rewards use catalog prices and insufficient balance is rejected`() {
        val state = DemoState.initial()
        val snapshot = FamilySnapshot.parse(state)
        assertEquals(listOf(5, 10), snapshot.rewards.map { it.cost })
        assertThrows(IllegalArgumentException::class.java) {
            DemoState.apply(state, "sticker_redeem_request", request(snapshot.rewards.last()), "child")
        }
    }
}
