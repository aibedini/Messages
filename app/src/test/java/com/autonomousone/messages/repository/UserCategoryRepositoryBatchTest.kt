package com.autonomousone.messages.repository

import com.autonomousone.messages.data.UserCategoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.5.0 Phase 4 — the multi-select (batch) contract.
 *
 * This is the surface the Home multi-select depends on, and it is where an N+1 hides
 * most easily: "assign this category to the 100 conversations I selected" is one user
 * action, and it must cost one transaction and at most one statement per scope TYPE —
 * never one per conversation. [UserCategoryTestStore] counts the scope reads and the
 * batched deletes so that claim is measured, not asserted by inspection.
 */
class UserCategoryRepositoryBatchTest {

    private lateinit var store: UserCategoryTestStore
    private lateinit var repository: UserCategoryRepository

    private val uuid = AtomicInteger(0)

    @Before
    fun setUp() {
        store = UserCategoryTestStore()
        uuid.set(0)
        repository = UserCategoryRepository(
            categoryDao = store.categoryDao,
            assignmentDao = store.assignmentDao,
            transactions = store.transactions,
            uuidFactory = { "cat-${uuid.incrementAndGet()}" },
            diagnostics = { _, _ -> }
        )
        store.resetQueryCounters()
    }

    @After
    fun tearDown() = store.close()

    private fun create(name: String, now: Long = 1_000L): UserCategoryEntity = runBlocking {
        (repository.createCategory(name, now) as CreateCategoryResult.Created).category
    }

    private fun addresses(count: Int, prefix: String = "+9891200"): Set<UserCategoryScope> =
        (0 until count)
            .map { index -> UserCategoryScope.Address("$prefix%05d".format(index)) }
            .toSet()

    private fun threads(count: Int): Set<UserCategoryScope> =
        (1..count).map { UserCategoryScope.Thread(it.toLong()) }.toSet()

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH ASSIGN
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `batch 100AddressScopesOneBatch`() = runBlocking {
        val vpn = create("VPN")
        val scopes = addresses(100)
        store.resetQueryCounters()

        val result = repository.applyToScopes(scopes, vpn.categoryId, desiredAssigned = true, now = 2_000L)

        assertEquals(BatchAssignmentResult.Applied(100, 0, 100), result)
        assertEquals(100L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        // ONE validation query, whatever the scope count. No per-scope read at all.
        assertEquals(0, store.scopeReadQueries)
    }

    @Test
    fun `batch 100ThreadScopesOneBatch`() = runBlocking {
        val family = create("Family")
        val scopes = threads(100)

        val result = repository.applyToScopes(scopes, family.categoryId, desiredAssigned = true, now = 2_000L)

        assertEquals(BatchAssignmentResult.Applied(100, 0, 100), result)
        assertEquals(100L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(0, store.scopeReadQueries)
    }

    @Test
    fun `batch mixedScopesMaxTwoReadGroups`() = runBlocking {
        val vpn = create("VPN")
        val mixed = addresses(60) + threads(40)

        val assigned = repository.applyToScopes(mixed, vpn.categoryId, desiredAssigned = true, now = 2_000L)
        assertEquals(BatchAssignmentResult.Applied(100, 0, 100), assigned)

        store.resetQueryCounters()
        val membership = repository.categoryMembershipForScopes(mixed)

        // ONE query for the ADDRESS group, ONE for the THREAD group — never 100.
        assertEquals(2, store.scopeReadQueries)
        assertEquals(100, membership.size)
        assertTrue(membership.values.all { it == setOf(vpn.categoryId) })
    }

    @Test
    fun `batch duplicateScopesDeduped`() = runBlocking {
        val vpn = create("VPN")
        // The Set already collapses two equal scopes; the repository ALSO de-duplicates the
        // encoded form, so the reported scope count and the number of rows must be 1.
        val scopes = setOf(
            UserCategoryScope.Address("+989121234567"),
            UserCategoryScope.Address("+989121234567")
        )

        val result = repository.applyToScopes(scopes, vpn.categoryId, desiredAssigned = true, now = 2_000L)

        assertEquals(BatchAssignmentResult.Applied(1, 0, 1), result)
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `batch batchAssignIdempotent`() = runBlocking {
        val vpn = create("VPN")
        val scopes = addresses(20)

        val first = repository.applyToScopes(scopes, vpn.categoryId, true, 2_000L)
        val second = repository.applyToScopes(scopes, vpn.categoryId, true, 9_000L)

        assertEquals(BatchAssignmentResult.Applied(20, 0, 20), first)
        assertEquals("nothing was rewritten", BatchAssignmentResult.Applied(0, 0, 20), second)
        assertEquals(20L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(
            "the original assignment moment survives a repeated multi-select",
            2_000L,
            store.scalar("SELECT MIN(createdAt) FROM user_category_assignments")
        )
    }

    @Test
    fun `batch batchRemoveOnlyTargetCategory`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        val scopes = addresses(50)
        repository.applyToScopes(scopes, vpn.categoryId, true, 2_000L)
        repository.applyToScopes(scopes, clients.categoryId, true, 2_500L)

        store.resetQueryCounters()
        val removed = repository.applyToScopes(scopes, vpn.categoryId, desiredAssigned = false, now = 3_000L)

        assertEquals(BatchAssignmentResult.Applied(0, 50, 50), removed)
        // At most two delete statements for the whole batch (one per scope type here).
        assertTrue("expected at most 2 batched deletes but saw ${store.batchDeleteQueries}", store.batchDeleteQueries <= 2)
        assertEquals(50L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(
            "every remaining row belongs to the untouched category",
            clients.categoryId,
            store.text("SELECT DISTINCT categoryId FROM user_category_assignments")
        )
    }

    @Test
    fun `batch unknownCategoryNoMutation`() = runBlocking {
        val vpn = create("VPN")
        val scopes = addresses(30)
        repository.applyToScopes(scopes, vpn.categoryId, true, 2_000L)

        val result = repository.applyToScopes(scopes, "ghost", desiredAssigned = true, 3_000L)

        assertEquals(BatchAssignmentResult.UnknownCategories(setOf("ghost")), result)
        assertEquals(30L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(vpn.categoryId, store.text("SELECT DISTINCT categoryId FROM user_category_assignments"))

        // …and the unassign direction leaves the real memberships alone too.
        val removeAttempt = repository.applyToScopes(scopes, "ghost", desiredAssigned = false, 3_500L)
        assertEquals(BatchAssignmentResult.UnknownCategories(setOf("ghost")), removeAttempt)
        assertEquals(30L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `batch emptyScopeSetIsANoOp`() = runBlocking {
        val vpn = create("VPN")

        val result = repository.applyToScopes(emptySet(), vpn.categoryId, true, 2_000L)

        assertEquals(BatchAssignmentResult.Applied(0, 0, 0), result)
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEMBERSHIP READ (tri-state)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `membership everyRequestedScopeIsPresentEvenWhenEmpty`() = runBlocking {
        val vpn = create("VPN")
        val assigned = addresses(3)
        val unassigned = setOf(UserCategoryScope.Address("+989129999999"), UserCategoryScope.Thread(404L))
        repository.applyToScopes(assigned, vpn.categoryId, true, 2_000L)

        val membership = repository.categoryMembershipForScopes(assigned + unassigned)

        assertEquals(5, membership.size)
        assigned.forEach { assertEquals(setOf(vpn.categoryId), membership[it]) }
        // Absent is reported as an EMPTY SET, not as a missing key: that is what makes
        // "indeterminate" computable from one round trip.
        unassigned.forEach { assertEquals(emptySet<String>(), membership[it]) }
    }

    @Test
    fun `membership emptyRequestReturnsEmptyMap`() = runBlocking {
        assertEquals(emptyMap<UserCategoryScope, Set<String>>(), repository.categoryMembershipForScopes(emptySet()))
        assertEquals(0, store.scopeReadQueries)
    }

    @Test
    fun `membership multipleCategoriesPerScope`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        val scope = UserCategoryScope.Address("+989121234567")
        repository.assign(scope, setOf(vpn.categoryId, clients.categoryId), 1_500L)

        assertEquals(
            setOf(vpn.categoryId, clients.categoryId),
            repository.categoryMembershipForScopes(setOf(scope))[scope]
        )
    }

    @Test
    fun `categoryIdsForScope readsOnlyThatScope`() = runBlocking {
        val vpn = create("VPN")
        val other = UserCategoryScope.Address("+989120000000")
        repository.assign(UserCategoryScope.Address("+989121234567"), setOf(vpn.categoryId), 1_500L)
        repository.assign(other, setOf(vpn.categoryId), 1_500L)

        assertEquals(
            setOf(vpn.categoryId),
            repository.categoryIdsForScope(UserCategoryScope.Address("+989121234567"))
        )
        assertEquals(emptySet<String>(), repository.categoryIdsForScope(UserCategoryScope.Address("+989128888888")))
    }

    @Test
    fun `membership ignoresUnrelatedScopes`() = runBlocking {
        val vpn = create("VPN")
        val mine = UserCategoryScope.Address("+989121234567")
        repository.assign(mine, setOf(vpn.categoryId), 1_500L)

        val membership = repository.categoryMembershipForScopes(setOf(mine, UserCategoryScope.Thread(7L)))

        assertEquals(setOf(vpn.categoryId), membership[mine])
        assertEquals(emptySet<String>(), membership[UserCategoryScope.Thread(7L)])
    }

    @Test
    fun `batch neverTouchesOtherTables`() = runBlocking {
        store.seedMessage(100)
        store.seedConversation(7)
        store.seedSmartCategoryOverride(7, "TRANSACTION")
        val vpn = create("VPN")

        repository.applyToScopes(addresses(10) + threads(10), vpn.categoryId, true, 2_000L)
        repository.applyToScopes(addresses(10) + threads(10), vpn.categoryId, false, 3_000L)

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM messages"))
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM conversations"))
        assertEquals(
            "TRANSACTION",
            store.text("SELECT categoryOverride FROM conversation_preferences WHERE threadId = 7")
        )
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertFalse(store.membership(vpn.categoryId, "ADDRESS", "+989120000000"))
    }
}
