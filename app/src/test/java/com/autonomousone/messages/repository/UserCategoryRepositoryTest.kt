package com.autonomousone.messages.repository

import com.autonomousone.messages.data.UserCategoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.5.0 Phase 4 — the custom-category repository, against a REAL SQLite engine.
 *
 * The repository under test is the production class; [UserCategoryTestStore] supplies the
 * Room DAO interfaces and a JDBC transaction runner over the shipped v18 schema, so the
 * UNIQUE index, the composite primary key and the CASCADE foreign key are the same
 * constraints the app runs against. Nothing here touches Room, a device or the Telemetry
 * provider.
 */
class UserCategoryRepositoryTest {

    private lateinit var store: UserCategoryTestStore
    private lateinit var repository: UserCategoryRepository

    private val uuid = AtomicInteger(0)
    private val diagnostics = mutableListOf<Pair<String, String>>()

    private val phone = UserCategoryScope.Address("+989121234567")
    private val otherPhone = UserCategoryScope.Address("+989120000000")

    @Before
    fun setUp() {
        store = UserCategoryTestStore()
        uuid.set(0)
        diagnostics.clear()
        repository = UserCategoryRepository(
            categoryDao = store.categoryDao,
            assignmentDao = store.assignmentDao,
            transactions = store.transactions,
            uuidFactory = { "cat-${uuid.incrementAndGet()}" },
            diagnostics = { category, message -> diagnostics += category to message }
        )
    }

    @After
    fun tearDown() = store.close()

    private fun create(name: String, now: Long = 1_000L): UserCategoryEntity = runBlocking {
        val result = repository.createCategory(name, now)
        assertTrue("expected Created for <$name> but got $result", result is CreateCategoryResult.Created)
        (result as CreateCategoryResult.Created).category
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `create uuidStored`() = runBlocking {
        val created = create("VPN")

        assertEquals("cat-1", created.categoryId)
        assertNotNull(store.categoryRow("cat-1"))
        // The id is the identity, so it is what a rename must preserve.
        assertEquals(
            "cat-1",
            store.text("SELECT categoryId FROM user_categories WHERE normalizedName = 'vpn'")
        )
    }

    @Test
    fun `create sortOrderStartsAtZero`() = runBlocking {
        assertEquals(0, create("First").sortOrder)
        assertEquals(0L, store.scalar("SELECT MIN(sortOrder) FROM user_categories"))
    }

    @Test
    fun `create sortOrderIncrements`() = runBlocking {
        assertEquals(0, create("A").sortOrder)
        assertEquals(1, create("B").sortOrder)
        assertEquals(2, create("C").sortOrder)

        // The stored order is the user's order, not insertion order by rowid.
        assertEquals(
            listOf("A" to 0, "B" to 1, "C" to 2),
            store.categoryDao.all().map { it.name to it.sortOrder }
        )
    }

    @Test
    fun `create duplicateExact`() = runBlocking {
        create("VPN")

        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("VPN", 2_000L)
        )
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `create duplicateCase`() = runBlocking {
        create("VPN")

        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("vpn", 2_000L)
        )
        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("Vpn", 2_000L)
        )
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `create duplicateWhitespace`() = runBlocking {
        create("VPN Clients")

        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("  vpn   clients  ", 2_000L)
        )
        // A non-breaking space is whitespace for this policy too.
        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("VPN\u00A0Clients", 2_000L)
        )
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `create duplicateNfkc`() = runBlocking {
        create("VPN")

        assertEquals(
            CreateCategoryResult.Duplicate,
            repository.createCategory("\uFF36\uFF30\uFF2E", 2_000L)
        )
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `create differentPersianNamesAllowed`() = runBlocking {
        val withZwnj = create("\u0645\u0634\u062A\u0631\u06CC\u200C\u0647\u0627")
        val withSpace = create("\u0645\u0634\u062A\u0631\u06CC \u0647\u0627")

        assertEquals(2L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertTrue(withZwnj.categoryId != withSpace.categoryId)
        assertEquals(0, withZwnj.sortOrder)
        assertEquals(1, withSpace.sortOrder)
    }

    @Test
    fun `create rejects an unusable name without writing anything`() = runBlocking {
        assertEquals(CreateCategoryResult.EmptyName, repository.createCategory("   ", 1_000L))
        assertEquals(CreateCategoryResult.TooLong, repository.createCategory("a".repeat(41), 1_000L))
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `create sortOrderOverflowHandled`() = runBlocking {
        // A list already at the ceiling: the next append may not compute MAX_VALUE + 1.
        store.seedCategory("cat-a", "A", "a", sortOrder = Int.MAX_VALUE - 2, createdAt = 10)
        store.seedCategory("cat-b", "B", "b", sortOrder = Int.MAX_VALUE - 1, createdAt = 11)
        store.seedCategory("cat-c", "C", "c", sortOrder = Int.MAX_VALUE, createdAt = 12)

        val created = create("D")

        // The current order is resequenced to 0..n-1 inside the same transaction and the
        // new category continues after it — no overflow, and the ORDER is unchanged.
        assertEquals(3, created.sortOrder)
        assertEquals(
            listOf("A" to 0, "B" to 1, "C" to 2, "D" to 3),
            store.categoryDao.all().map { it.name to it.sortOrder }
        )
        assertEquals(0L, store.scalar("SELECT MIN(sortOrder) FROM user_categories"))
    }

    /**
     * The database — not the read-then-write check — is the authority on duplicate names.
     *
     * `duplicateLookupIsBlind` reproduces the only race that matters: a create whose
     * duplicate read missed a row that is nonetheless already committed. The UNIQUE index
     * must then reject it, and the repository must translate that violation into
     * [CreateCategoryResult.Duplicate] rather than letting a SQLite exception escape.
     */
    @Test
    fun `twoCreateRace yields one Created and one Duplicate`() = runBlocking {
        create("VPN")
        store.duplicateLookupIsBlind = true

        val raced = repository.createCategory("vpn", 2_000L)

        assertEquals(CreateCategoryResult.Duplicate, raced)
        assertEquals("exactly one row survives the race", 1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertEquals("VPN", store.text("SELECT name FROM user_categories"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RENAME
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `rename idPreserved`() = runBlocking {
        val created = create("VPN", now = 1_000L)

        val result = repository.renameCategory(created.categoryId, "VPN Clients", 2_000L)

        assertTrue(result is RenameCategoryResult.Renamed)
        assertEquals(created.categoryId, (result as RenameCategoryResult.Renamed).category.categoryId)
        assertEquals("cat-1", store.text("SELECT categoryId FROM user_categories"))
    }

    @Test
    fun `rename createdAtPreserved`() = runBlocking {
        val created = create("VPN", now = 1_000L)

        val renamed = repository.renameCategory(created.categoryId, "VPN Clients", 2_000L)

        assertEquals(1_000L, (renamed as RenameCategoryResult.Renamed).category.createdAt)
        assertEquals(1_000L, store.scalar("SELECT createdAt FROM user_categories"))
    }

    @Test
    fun `rename sortOrderPreserved`() = runBlocking {
        create("A")
        val middle = create("B")
        create("C")

        repository.renameCategory(middle.categoryId, "B renamed", 2_000L)

        assertEquals(1, store.scalar("SELECT sortOrder FROM user_categories WHERE categoryId = 'cat-2'"))
        assertEquals(
            listOf("A", "B renamed", "C"),
            store.categoryDao.all().map { it.name }
        )
    }

    @Test
    fun `rename updatedAtChanges`() = runBlocking {
        val created = create("VPN", now = 1_000L)

        val renamed = repository.renameCategory(created.categoryId, "VPN Clients", 5_000L)

        val category = (renamed as RenameCategoryResult.Renamed).category
        assertEquals(5_000L, category.updatedAt)
        assertEquals(1_000L, category.createdAt)
        assertEquals(5_000L, store.scalar("SELECT updatedAt FROM user_categories"))
    }

    @Test
    fun `rename assignmentsPreserved`() = runBlocking {
        val created = create("VPN")
        repository.assign(phone, setOf(created.categoryId), 1_500L)
        repository.assign(otherPhone, setOf(created.categoryId), 1_600L)

        repository.renameCategory(created.categoryId, "VPN Clients", 9_000L)

        assertEquals(2L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertTrue(store.membership(created.categoryId, "ADDRESS", "+989121234567"))
        assertTrue(store.membership(created.categoryId, "ADDRESS", "+989120000000"))
    }

    @Test
    fun `rename sameNormalizedNameAllowed`() = runBlocking {
        val created = create("vpn")

        // Display normalization on the SAME category is not a duplicate.
        val upper = repository.renameCategory(created.categoryId, "VPN", 2_000L)
        assertTrue(upper is RenameCategoryResult.Renamed)
        assertEquals("VPN", (upper as RenameCategoryResult.Renamed).category.name)
        assertEquals("VPN", store.text("SELECT name FROM user_categories"))

        // … and neither is a pure whitespace normalization.
        val spaced = repository.renameCategory(created.categoryId, "  Vpn  ", 3_000L)
        assertTrue(spaced is RenameCategoryResult.Renamed)
        assertEquals("Vpn", store.text("SELECT name FROM user_categories"))
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `rename duplicateOtherRejected`() = runBlocking {
        create("VPN")
        val other = create("Clients")

        assertEquals(
            RenameCategoryResult.Duplicate,
            repository.renameCategory(other.categoryId, "vpn", 2_000L)
        )
        // Nothing changed: not the name, not the timestamp.
        assertEquals("Clients", store.text("SELECT name FROM user_categories WHERE categoryId = 'cat-2'"))
        assertEquals(1_000L, store.scalar("SELECT updatedAt FROM user_categories WHERE categoryId = 'cat-2'"))
    }

    @Test
    fun `rename unknownCategoryNotFound`() = runBlocking {
        assertEquals(
            RenameCategoryResult.NotFound,
            repository.renameCategory("does-not-exist", "VPN", 2_000L)
        )
        assertEquals(RenameCategoryResult.EmptyName, repository.renameCategory("does-not-exist", " ", 2_000L))
        assertEquals(RenameCategoryResult.TooLong, repository.renameCategory("does-not-exist", "a".repeat(41), 2_000L))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `delete deleteExisting`() = runBlocking {
        val created = create("VPN")

        assertEquals(DeleteCategoryResult.Deleted, repository.deleteCategory(created.categoryId))
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertNull(store.categoryRow(created.categoryId))
    }

    @Test
    fun `delete deleteUnknown`() = runBlocking {
        assertEquals(DeleteCategoryResult.NotFound, repository.deleteCategory("nope"))
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    @Test
    fun `delete cascadeAssignments`() = runBlocking {
        val created = create("VPN")
        repository.assign(phone, setOf(created.categoryId), 1_500L)
        repository.assign(UserCategoryScope.Thread(7L), setOf(created.categoryId), 1_600L)
        assertEquals(2L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))

        assertEquals(DeleteCategoryResult.Deleted, repository.deleteCategory(created.categoryId))

        // The FK cascade took every membership with the row — no orphan can survive.
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `delete messagesUntouched`() = runBlocking {
        store.seedMessage(100)
        store.seedMessage(101)
        val created = create("VPN")

        repository.deleteCategory(created.categoryId)

        assertEquals(2L, store.scalar("SELECT COUNT(*) FROM messages"))
        assertEquals("hello", store.text("SELECT body FROM messages WHERE providerId = 100"))
    }

    @Test
    fun `delete conversationsUntouched`() = runBlocking {
        store.seedConversation(7)
        val created = create("VPN")

        repository.deleteCategory(created.categoryId)

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM conversations"))
        assertEquals(7L, store.scalar("SELECT threadId FROM conversations"))
    }

    @Test
    fun `delete conversationPreferencesUntouched`() = runBlocking {
        store.seedSmartCategoryOverride(7, "TRANSACTION")
        val created = create("VPN")

        repository.deleteCategory(created.categoryId)

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM conversation_preferences"))
        assertEquals(0L, store.scalar("SELECT manualUnread FROM conversation_preferences WHERE threadId = 7"))
    }

    /**
     * A custom category and a SMART category are different axes. Deleting one must not
     * touch `categoryOverride` — that column is the single-valued smart override and has
     * nothing to do with the user's own categories.
     */
    @Test
    fun `delete smartCategoryOverrideUntouched`() = runBlocking {
        store.seedSmartCategoryOverride(7, "TRANSACTION")
        store.seedSmartCategoryOverride(8, "OTP")
        val created = create("VPN")

        repository.deleteCategory(created.categoryId)

        assertEquals(
            "TRANSACTION",
            store.text("SELECT categoryOverride FROM conversation_preferences WHERE threadId = 7")
        )
        assertEquals(
            "OTP",
            store.text("SELECT categoryOverride FROM conversation_preferences WHERE threadId = 8")
        )
        // …and the reverse direction: a rename must not touch it either.
        val second = create("Clients")
        repository.renameCategory(second.categoryId, "Clients 2", 9_000L)
        assertEquals(
            "TRANSACTION",
            store.text("SELECT categoryOverride FROM conversation_preferences WHERE threadId = 7")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASSIGN / REMOVE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `assign oneScopeManyCategories`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        val important = create("Important")

        val result = repository.assign(
            phone,
            setOf(vpn.categoryId, clients.categoryId, important.categoryId),
            1_500L
        )

        assertEquals(CategoryAssignmentResult.Applied(3, 0), result)
        assertEquals(
            setOf(vpn.categoryId, clients.categoryId, important.categoryId),
            repository.categoryIdsForScope(phone)
        )
        assertEquals(3L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `assign oneCategoryManyScopes`() = runBlocking {
        val vpn = create("VPN")

        repository.assign(phone, setOf(vpn.categoryId), 1_500L)
        repository.assign(otherPhone, setOf(vpn.categoryId), 1_500L)
        repository.assign(UserCategoryScope.Thread(7L), setOf(vpn.categoryId), 1_500L)

        assertEquals(3L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertTrue(store.membership(vpn.categoryId, "ADDRESS", "+989121234567"))
        assertTrue(store.membership(vpn.categoryId, "ADDRESS", "+989120000000"))
        assertTrue(store.membership(vpn.categoryId, "THREAD", "7"))
    }

    @Test
    fun `assign sameAssignmentIdempotent`() = runBlocking {
        val vpn = create("VPN")

        val first = repository.assign(phone, setOf(vpn.categoryId), 1_500L)
        val second = repository.assign(phone, setOf(vpn.categoryId), 9_999L)

        assertEquals(CategoryAssignmentResult.Applied(1, 0), first)
        assertEquals("re-assigning writes no new row", CategoryAssignmentResult.Applied(0, 0), second)
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `assign existingCreatedAtPreserved`() = runBlocking {
        val vpn = create("VPN")

        repository.assign(phone, setOf(vpn.categoryId), 1_500L)
        repository.assign(phone, setOf(vpn.categoryId), 9_999L)

        assertEquals(
            "an existing membership keeps the moment it was made",
            1_500L,
            store.scalar("SELECT createdAt FROM user_category_assignments")
        )
    }

    @Test
    fun `assign unknownCategoryNoPartialMutation`() = runBlocking {
        val vpn = create("VPN")

        val result = repository.assign(phone, setOf(vpn.categoryId, "ghost"), 1_500L)

        assertEquals(
            CategoryAssignmentResult.UnknownCategories(setOf("ghost")),
            result
        )
        // Not even the known, valid category was written.
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `assign emptyCategorySetIsANoOp`() = runBlocking {
        assertEquals(
            CategoryAssignmentResult.Applied(0, 0),
            repository.assign(phone, emptySet(), 1_500L)
        )
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `remove onlyRemovesTheRequestedCategories`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        repository.assign(phone, setOf(vpn.categoryId, clients.categoryId), 1_500L)

        val removed = repository.remove(phone, setOf(vpn.categoryId))

        assertEquals(CategoryAssignmentResult.Applied(0, 1), removed)
        assertEquals(setOf(clients.categoryId), repository.categoryIdsForScope(phone))
    }

    @Test
    fun `remove unknownCategoryIsASuccessfulNoOp`() = runBlocking {
        assertEquals(
            CategoryAssignmentResult.Applied(0, 0),
            repository.remove(phone, setOf("ghost"))
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REPLACE ASSIGNMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `replace deltaAdd`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        repository.assign(phone, setOf(vpn.categoryId), 1_500L)

        val result = repository.replaceAssignments(
            phone,
            setOf(vpn.categoryId, clients.categoryId),
            2_500L
        )

        assertEquals(CategoryAssignmentResult.Applied(1, 0), result)
        assertEquals(setOf(vpn.categoryId, clients.categoryId), repository.categoryIdsForScope(phone))
    }

    @Test
    fun `replace deltaRemove`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        repository.assign(phone, setOf(vpn.categoryId, clients.categoryId), 1_500L)

        val result = repository.replaceAssignments(phone, setOf(clients.categoryId), 2_500L)

        assertEquals(CategoryAssignmentResult.Applied(0, 1), result)
        assertEquals(setOf(clients.categoryId), repository.categoryIdsForScope(phone))
    }

    @Test
    fun `replace unchangedMembershipPreserved`() = runBlocking {
        val vpn = create("VPN")
        val clients = create("Clients")
        val important = create("Important")
        repository.assign(phone, setOf(vpn.categoryId, clients.categoryId), 1_500L)

        // vpn and clients stay in the desired set: they must NOT be rewritten.
        repository.replaceAssignments(
            phone,
            setOf(vpn.categoryId, clients.categoryId, important.categoryId),
            2_500L
        )

        assertEquals(3L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(
            "the untouched membership keeps its original moment",
            1_500L,
            store.scalar(
                "SELECT createdAt FROM user_category_assignments WHERE categoryId = 'cat-1'"
            )
        )
        assertEquals(
            2_500L,
            store.scalar(
                "SELECT createdAt FROM user_category_assignments WHERE categoryId = 'cat-3'"
            )
        )
    }

    @Test
    fun `replace unchangedCreatedAtPreserved`() = runBlocking {
        val vpn = create("VPN")
        repository.assign(phone, setOf(vpn.categoryId), 1_500L)

        // Replace with exactly the same set, twice: a delete-all/insert-all would reset
        // createdAt, and the delta must not.
        repository.replaceAssignments(phone, setOf(vpn.categoryId), 2_500L)
        repository.replaceAssignments(phone, setOf(vpn.categoryId), 3_500L)

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(1_500L, store.scalar("SELECT createdAt FROM user_category_assignments"))
    }

    @Test
    fun `replace unknownDesiredCategoryRollsBackAll`() = runBlocking {
        val vpn = create("VPN")
        repository.assign(phone, setOf(vpn.categoryId), 1_500L)

        val result = repository.replaceAssignments(phone, setOf("ghost"), 2_500L)

        assertEquals(CategoryAssignmentResult.UnknownCategories(setOf("ghost")), result)
        // The previous membership is intact: the validation ran before any mutation.
        assertEquals(setOf(vpn.categoryId), repository.categoryIdsForScope(phone))
        assertEquals(1_500L, store.scalar("SELECT createdAt FROM user_category_assignments"))
    }

    @Test
    fun `replace withAnEmptySetRemovesEverythingForThatScopeOnly`() = runBlocking {
        val vpn = create("VPN")
        repository.assign(phone, setOf(vpn.categoryId), 1_500L)
        repository.assign(otherPhone, setOf(vpn.categoryId), 1_500L)

        val result = repository.replaceAssignments(phone, emptySet(), 2_500L)

        assertEquals(CategoryAssignmentResult.Applied(0, 1), result)
        assertEquals(emptySet<String>(), repository.categoryIdsForScope(phone))
        assertEquals(setOf(vpn.categoryId), repository.categoryIdsForScope(otherPhone))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE + ASSIGN (atomic)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `createAndAssign singleScope`() = runBlocking {
        val result = repository.createAndAssign("VPN", setOf(phone), 1_500L)

        val created = (result as CreateAndAssignResult.Created).category
        assertEquals(1, result.assignedScopes)
        assertEquals(0, created.sortOrder)
        assertEquals(1_500L, created.createdAt)
        assertEquals(setOf(created.categoryId), repository.categoryIdsForScope(phone))
    }

    @Test
    fun `createAndAssign multipleScopes`() = runBlocking {
        val result = repository.createAndAssign(
            "VPN",
            setOf(phone, otherPhone, UserCategoryScope.Address("+989120000001")),
            1_500L
        )

        val created = (result as CreateAndAssignResult.Created).category
        assertEquals(3, result.assignedScopes)
        assertEquals(3L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertTrue(store.membership(created.categoryId, "ADDRESS", "+989121234567"))
    }

    @Test
    fun `createAndAssign mixedAddressAndThread`() = runBlocking {
        val result = repository.createAndAssign(
            "Family",
            setOf(phone, UserCategoryScope.Thread(7L), UserCategoryScope.Thread(9L)),
            1_500L
        )

        val created = (result as CreateAndAssignResult.Created).category
        assertEquals(3, result.assignedScopes)
        assertTrue(store.membership(created.categoryId, "ADDRESS", "+989121234567"))
        assertTrue(store.membership(created.categoryId, "THREAD", "7"))
        assertTrue(store.membership(created.categoryId, "THREAD", "9"))
    }

    @Test
    fun `createAndAssign newCategoryAssignedToAll`() = runBlocking {
        val existing = create("Existing")
        repository.assign(phone, setOf(existing.categoryId), 1_200L)

        val result = repository.createAndAssign("VPN", setOf(phone, otherPhone), 1_500L)

        val created = (result as CreateAndAssignResult.Created).category
        // The new category is on both, and the pre-existing membership is untouched.
        assertEquals(
            setOf(existing.categoryId, created.categoryId),
            repository.categoryIdsForScope(phone)
        )
        assertEquals(setOf(created.categoryId), repository.categoryIdsForScope(otherPhone))
    }

    @Test
    fun `createAndAssign duplicateNameCreatesNothing`() = runBlocking {
        val existing = create("VPN")
        store.seedAssignment(existing.categoryId, "ADDRESS", "+989120000009", 1_000L)

        val result = repository.createAndAssign("vpn", setOf(phone), 1_500L)

        assertEquals(CreateAndAssignResult.Duplicate, result)
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        // No assignment was added for the requested scope, and the unrelated one survives.
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertFalse(store.membership(existing.categoryId, "ADDRESS", "+989121234567"))
    }

    @Test
    fun `createAndAssign rejectsAnUnusableNameWithoutWriting`() = runBlocking {
        assertEquals(
            CreateAndAssignResult.EmptyName,
            repository.createAndAssign("  ", setOf(phone), 1_500L)
        )
        assertEquals(
            CreateAndAssignResult.TooLong,
            repository.createAndAssign("a".repeat(41), setOf(phone), 1_500L)
        )
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertEquals(0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    /**
     * THE atomicity contract: a failure while writing the memberships must take the new
     * category row down with it. A "created but unassigned" category is a state the user
     * can see and cannot explain, so it must not be reachable.
     */
    @Test
    fun `createAndAssign failureRollsBackCategoryAndAssignments`() = runBlocking {
        store.failAssignmentWrites = true

        var thrown: Throwable? = null
        try {
            repository.createAndAssign("VPN", setOf(phone, otherPhone), 1_500L)
        } catch (error: Throwable) {
            thrown = error
        }

        assertNotNull("the failure must surface, not be swallowed", thrown)
        assertEquals("the category row was rolled back", 0L, store.scalar("SELECT COUNT(*) FROM user_categories"))
        assertEquals("no membership survived", 0L, store.scalar("SELECT COUNT(*) FROM user_category_assignments"))

        // And the repository is usable again afterwards.
        store.failAssignmentWrites = false
        val retry = repository.createAndAssign("VPN", setOf(phone), 2_500L)
        assertTrue(retry is CreateAndAssignResult.Created)
        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM user_categories"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `diagnostics carry counts and no user content`() = runBlocking {
        val created = create("VPN Clients")
        repository.renameCategory(created.categoryId, "مشتری‌ها", 2_000L)
        repository.assign(phone, setOf(created.categoryId), 3_000L)
        repository.remove(phone, setOf(created.categoryId))
        repository.deleteCategory(created.categoryId)

        val categories = diagnostics.map { it.first }
        assertTrue(categories.contains(UserCategoryRepository.USER_CATEGORY_CREATE))
        assertTrue(categories.contains(UserCategoryRepository.USER_CATEGORY_RENAME))
        assertTrue(categories.contains(UserCategoryRepository.USER_CATEGORY_ASSIGN))
        assertTrue(categories.contains(UserCategoryRepository.USER_CATEGORY_REMOVE))
        assertTrue(categories.contains(UserCategoryRepository.USER_CATEGORY_DELETE))

        val text = diagnostics.joinToString(" | ") { "${it.first} ${it.second}" }
        // A category NAME is user content, and a scope key is a phone number or thread id.
        assertFalse("the category name must never be logged", text.contains("VPN"))
        assertFalse("a Persian category name must never be logged", text.contains("مشتری"))
        assertFalse("a phone scope key must never be logged", text.contains("+989121234567"))
        assertFalse("the raw category id must never be logged", text.contains(created.categoryId))
        assertTrue("counts and scope types are safe", text.contains("types=ADDRESS"))
    }
}
