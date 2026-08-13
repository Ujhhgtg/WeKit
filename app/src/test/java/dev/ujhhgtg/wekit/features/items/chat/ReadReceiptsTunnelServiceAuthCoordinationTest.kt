package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelServiceAuthCoordinationTest {

    @Test
    fun `service auth events preserve typed terminals and semantic wire remediation`() {
        data class Case<T>(
            val generation: Long,
            val event: ServiceAuthFailureEvent<T>,
            val expectedErrorCode: ReadReceiptsTunnelErrorCode,
            val expected: AuthOperationTerminal<T>,
        )

        val cases = listOf(
            Case(
                98,
                ServiceAuthFailureEvent.ListCancelled,
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                AuthOperationTerminal.Cancelled,
            ),
            Case(
                99,
                ServiceAuthFailureEvent.BeginCleanupFailed,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                AuthOperationTerminal.Failed("SERVICE_UNAVAILABLE"),
            ),
            Case(
                100,
                ServiceAuthFailureEvent.BeginRejected,
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                AuthOperationTerminal.Failed("BROWSER_CREDENTIAL_INVALID"),
            ),
            Case(
                101,
                ServiceAuthFailureEvent.ListTimeout,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                AuthOperationTerminal.TimedOut,
            ),
            Case(
                102,
                ServiceAuthFailureEvent.ListSessionLost,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                AuthOperationTerminal.Failed("SERVICE_UNAVAILABLE"),
            ),
            Case(
                103,
                ServiceAuthFailureEvent.ListRejected,
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                AuthOperationTerminal.Failed("BROWSER_CREDENTIAL_INVALID"),
            ),
            Case(
                104,
                ServiceAuthFailureEvent.SelectTimeout,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                AuthOperationTerminal.TimedOut,
            ),
            Case(
                105,
                ServiceAuthFailureEvent.SelectSessionLost,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                AuthOperationTerminal.Failed("SERVICE_UNAVAILABLE"),
            ),
            Case(
                106,
                ServiceAuthFailureEvent.SelectRejected,
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                AuthOperationTerminal.Failed("BROWSER_CREDENTIAL_INVALID"),
            ),
            Case(
                107,
                ServiceAuthFailureEvent.SelectCredentialSaveFailed,
                ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED,
                AuthOperationTerminal.Failed("CREDENTIAL_SAVE_FAILED"),
            ),
            Case(
                108,
                ServiceAuthFailureEvent.SelectHealthCheckFailed,
                ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED,
                AuthOperationTerminal.Failed("HEALTH_CHECK_FAILED"),
            ),
            Case(
                109,
                ServiceAuthFailureEvent.SelectCancelled,
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                AuthOperationTerminal.Cancelled,
            ),
            Case(
                110,
                ServiceAuthFailureEvent.SelectUnexpected,
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                AuthOperationTerminal.Failed("UNEXPECTED_FAILURE"),
            ),
        )

        cases.forEach { case ->
            val coordinator = if (case.event.kind === AuthOperationKind.BEGIN) {
                ServiceAuthCoordinator()
            } else {
                authorizedCoordinator(case.generation)
            }
            val key = AuthOperationKey(case.generation, 2)
            val terminals = mutableListOf<AuthOperationTerminal<Any?>>()
            @Suppress("UNCHECKED_CAST")
            val event = case.event as ServiceAuthFailureEvent<Any?>
            val kind = event.kind
            if (event.kind === AuthOperationKind.BEGIN) {
                assertTrue(coordinator.begin(key, terminals::add) is ServiceAuthAdmission.Accepted)
                assertTrue(coordinator.finishBeginBarrier(key))
            } else {
                coordinator.admit(
                    key,
                    kind,
                    ServiceAuthOperationPhase.NATIVE_BLOCKING,
                    terminals::add,
                )
            }

            val plan = coordinator.planFailure(key, event)!!
            assertEquals(case.expectedErrorCode, plan.errorCode, event.toString())
            assertTrue(coordinator.finishFailure(plan))

            assertEquals(listOf(case.expected), terminals, event.toString())
        }
    }

    @Test
    fun `auth admission rejection distinguishes unavailable session from invalid protocol kind`() {
        assertEquals(
            AuthOperationTerminal.Failed("SERVICE_UNAVAILABLE"),
            serviceAuthAdmissionTerminal(ServiceAuthRejectReason.SESSION_UNAVAILABLE),
        )
        assertEquals(
            AuthOperationTerminal.Failed("UNEXPECTED_FAILURE"),
            serviceAuthAdmissionTerminal(ServiceAuthRejectReason.INVALID_KIND),
        )
    }
    @Test
    fun `select commit and timeout claims are mutually exclusive`() {
        val committed = SelectCommitGate()
        assertTrue(committed.tryCommit())
        assertFalse(committed.tryTerminal())

        val timedOut = SelectCommitGate()
        assertTrue(timedOut.tryTerminal())
        assertFalse(timedOut.tryCommit())
    }

    @Test
    fun `planned superseded terminal freezes operation until workers drain`() {
        val coordinator = authorizedCoordinator(9)
        val key = AuthOperationKey(9, 2)
        val terminals = mutableListOf<AuthOperationTerminal<Unit>>()
        coordinator.admit(
            key,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            terminals::add,
        )

        val plan = coordinator.planTerminal(
            key,
            AuthOperationKind.SELECT,
            AuthOperationTerminal.Superseded,
        )!!
        assertFalse(coordinator.canPublish(key, AuthOperationKind.SELECT))
        assertTrue(terminals.isEmpty())
        assertTrue(coordinator.finishTerminal(plan))
        assertEquals(listOf(AuthOperationTerminal.Superseded), terminals)
    }

    @Test
    fun `service admission claims one ACK and delegates exactly one terminal`() {
        val coordinator = ServiceAuthCoordinator()
        val key = AuthOperationKey(10, 1)
        val terminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()

        assertEquals(ServiceAuthSessionPhase.IDLE, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
        assertTrue(coordinator.begin(key, terminals::add) is ServiceAuthAdmission.Accepted)
        assertTrue(coordinator.claimAck(key, AuthOperationKind.BEGIN))
        assertFalse(coordinator.claimAck(key, AuthOperationKind.BEGIN))
        assertFalse(coordinator.claimAck(key, AuthOperationKind.LIST))
        assertTrue(coordinator.finishBeginBarrier(key))
        assertTrue(
            coordinator.complete(
                key,
                AuthOperationKind.BEGIN,
                AuthOperationTerminal.Completed(waitingLoginState()),
            ),
        )
        assertFalse(
            coordinator.complete(
                key,
                AuthOperationKind.BEGIN,
                AuthOperationTerminal.Failed("late duplicate"),
            ),
        )
        assertEquals(1, terminals.size)
    }

    @Test
    fun `new BEGIN supersedes old operations and waits behind cancel join barrier`() {
        val coordinator = authorizedCoordinator(20)
        val oldList = AuthOperationKey(20, 2)
        val oldSelect = AuthOperationKey(20, 3)
        val listTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        val selectTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        assertTrue(
            coordinator.admit(
                oldList,
                AuthOperationKind.LIST,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
                listTerminals::add,
            ) is ServiceAuthAdmission.Accepted,
        )
        assertTrue(
            coordinator.admit(
                oldSelect,
                AuthOperationKind.SELECT,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
                selectTerminals::add,
            ) is ServiceAuthAdmission.Accepted,
        )

        val replacement = AuthOperationKey(21, 1)
        val beginTerminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        assertTrue(coordinator.begin(replacement, beginTerminals::add) is ServiceAuthAdmission.Accepted)
        assertEquals(ServiceAuthSessionPhase.REPLACING, coordinator.snapshot().phase)
        assertEquals(20L, coordinator.snapshot().authGeneration)
        assertTrue(listTerminals.isEmpty())
        assertTrue(selectTerminals.isEmpty())
        assertFalse(coordinator.canPublish(replacement, AuthOperationKind.BEGIN))
        assertFalse(coordinator.canPublish(oldList, AuthOperationKind.LIST))
        assertFalse(coordinator.canPublish(oldSelect, AuthOperationKind.SELECT))
        assertTrue(
            coordinator.admit(
                AuthOperationKey(21, 2),
                AuthOperationKind.LIST,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ) {} is ServiceAuthAdmission.Rejected,
        )
        assertFalse(
            coordinator.complete(
                oldList,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )

        assertTrue(coordinator.finishBeginBarrier(replacement))
        assertEquals(listOf(AuthOperationTerminal.Superseded), listTerminals)
        assertEquals(listOf(AuthOperationTerminal.Superseded), selectTerminals)
        assertEquals(ServiceAuthSessionPhase.WAITING, coordinator.snapshot().phase)
        assertEquals(21L, coordinator.snapshot().authGeneration)
        assertTrue(coordinator.canPublish(replacement, AuthOperationKind.BEGIN))
    }

    @Test
    fun `blocking timeout waits for auth cleanup before publishing one terminal`() {
        val timeoutCoordinator = authorizedCoordinator(30)
        val timeoutKey = AuthOperationKey(30, 2)
        val timeoutTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        val siblingKey = AuthOperationKey(30, 3)
        val siblingTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        timeoutCoordinator.admit(
            timeoutKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            timeoutTerminals::add,
        )
        timeoutCoordinator.admit(
            siblingKey,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            siblingTerminals::add,
        )

        val timeoutPlan = timeoutCoordinator.planFailure(
            timeoutKey,
            ServiceAuthFailureEvent.ListTimeout,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED,
            timeoutPlan.action,
        )
        assertEquals(ServiceAuthSessionPhase.CANCELLING, timeoutCoordinator.snapshot().phase)
        assertEquals(30L, timeoutCoordinator.snapshot().authGeneration)
        assertTrue(timeoutTerminals.isEmpty())
        assertTrue(siblingTerminals.isEmpty())
        assertFalse(timeoutCoordinator.canPublish(timeoutKey, AuthOperationKind.LIST))
        assertFalse(
            timeoutCoordinator.complete(
                timeoutKey,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )

        assertTrue(timeoutCoordinator.finishFailure(timeoutPlan))
        assertEquals(listOf(AuthOperationTerminal.TimedOut), timeoutTerminals)
        assertEquals(listOf(AuthOperationTerminal.Cancelled), siblingTerminals)
        assertFalse(timeoutCoordinator.finishFailure(timeoutPlan))
        assertEquals(ServiceAuthSessionPhase.RESTART_REQUIRED, timeoutCoordinator.snapshot().phase)
        assertEquals(0L, timeoutCoordinator.snapshot().authGeneration)
    }

    @Test
    fun `returned API failure can finish immediately without clearing auth`() {
        val apiCoordinator = authorizedCoordinator(31)
        val apiKey = AuthOperationKey(31, 2)
        val apiTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        apiCoordinator.admit(
            apiKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            apiTerminals::add,
        )
        val apiPlan = apiCoordinator.planFailure(
            apiKey,
            ServiceAuthFailureEvent.ListRejected,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.PRESERVE_AUTH,
            apiPlan.action,
        )
        assertTrue(apiTerminals.isEmpty())
        assertTrue(apiCoordinator.finishFailure(apiPlan))
        assertEquals(ServiceAuthSessionPhase.AUTHORIZED, apiCoordinator.snapshot().phase)
        assertEquals(31L, apiCoordinator.snapshot().authGeneration)
        assertEquals(
            listOf(AuthOperationTerminal.Failed("BROWSER_CREDENTIAL_INVALID")),
            apiTerminals,
        )
    }

    @Test
    fun `blocking cancellation also waits for auth cleanup`() {
        val cancelledCoordinator = authorizedCoordinator(32)
        val cancelledKey = AuthOperationKey(32, 2)
        val cancelledTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        cancelledCoordinator.admit(
            cancelledKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            cancelledTerminals::add,
        )
        val cancelledPlan = cancelledCoordinator.planFailure(
            cancelledKey,
            ServiceAuthFailureEvent.ListCancelled,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED,
            cancelledPlan.action,
        )
        assertTrue(cancelledTerminals.isEmpty())
        assertTrue(cancelledCoordinator.finishFailure(cancelledPlan))
        assertEquals(listOf(AuthOperationTerminal.Cancelled), cancelledTerminals)
        assertEquals(
            ServiceAuthSessionPhase.RESTART_REQUIRED,
            cancelledCoordinator.snapshot().phase,
        )
        assertEquals(0L, cancelledCoordinator.snapshot().authGeneration)
    }

    @Test
    fun `timeout after SELECT token return stops only candidate and preserves auth`() {
        val coordinator = authorizedCoordinator(40)
        val key = AuthOperationKey(40, 2)
        val terminals = mutableListOf<AuthOperationTerminal<Unit>>()
        coordinator.admit(
            key,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            terminals::add,
        )
        assertTrue(coordinator.markSelectValidating(key))

        val timeoutPlan = coordinator.planFailure(
            key,
            ServiceAuthFailureEvent.SelectTimeout,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.STOP_CANDIDATE_AND_PRESERVE_AUTH,
            timeoutPlan.action,
        )
        assertTrue(terminals.isEmpty())
        assertFalse(coordinator.canPublish(key, AuthOperationKind.SELECT))
        assertFalse(
            coordinator.complete(
                key,
                AuthOperationKind.SELECT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertTrue(coordinator.finishFailure(timeoutPlan))
        assertEquals(ServiceAuthSessionPhase.AUTHORIZED, coordinator.snapshot().phase)
        assertEquals(listOf(AuthOperationTerminal.TimedOut), terminals)

        val storageCoordinator = authorizedCoordinator(41)
        val storageKey = AuthOperationKey(41, 2)
        val storageTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        storageCoordinator.admit(
            storageKey,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            storageTerminals::add,
        )
        assertTrue(storageCoordinator.markSelectValidating(storageKey))
        val storagePlan = storageCoordinator.planFailure(
            storageKey,
            ServiceAuthFailureEvent.SelectCredentialSaveFailed,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.STOP_CANDIDATE_AND_PRESERVE_AUTH,
            storagePlan.action,
        )
        assertTrue(storageTerminals.isEmpty())
        assertTrue(storageCoordinator.finishFailure(storagePlan))
        assertEquals(
            listOf(AuthOperationTerminal.Failed("CREDENTIAL_SAVE_FAILED")),
            storageTerminals,
        )
        assertEquals(ServiceAuthSessionPhase.AUTHORIZED, storageCoordinator.snapshot().phase)
    }

    @Test
    fun `clearing a session resets active generation without allowing generation reuse`() {
        val coordinator = authorizedCoordinator(50)
        val listKey = AuthOperationKey(50, 2)
        val listTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        coordinator.admit(
            listKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            listTerminals::add,
        )

        assertTrue(coordinator.clearSession(50, restartRequired = false))
        assertEquals(listOf(AuthOperationTerminal.Cancelled), listTerminals)
        assertEquals(ServiceAuthSessionPhase.IDLE, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
        assertFalse(coordinator.clearSession(50, restartRequired = false))
        assertEquals(1, listTerminals.size)

        var rejectedCallbacks = 0
        val reused = coordinator.begin(AuthOperationKey(50, 3)) { rejectedCallbacks++ }
        assertEquals(
            ServiceAuthRejectReason.STALE_GENERATION,
            (reused as ServiceAuthAdmission.Rejected).reason,
        )
        assertEquals(0, rejectedCallbacks)
        assertTrue(
            coordinator.begin(AuthOperationKey(51, 1)) {} is ServiceAuthAdmission.Accepted,
        )
    }

    @Test
    fun `SELECT success publishes owner completion only after session cleanup`() {
        val coordinator = authorizedCoordinator(70)
        val ownerKey = AuthOperationKey(70, 2)
        val siblingKey = AuthOperationKey(70, 3)
        val ownerTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        val siblingTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        coordinator.admit(
            ownerKey,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ownerTerminals::add,
        )
        coordinator.admit(
            siblingKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            siblingTerminals::add,
        )
        assertTrue(coordinator.markSelectValidating(ownerKey))

        val plan = coordinator.planSessionClear(
            ownerKey,
            AuthOperationKind.SELECT,
            AuthOperationTerminal.Completed(Unit),
        )!!
        assertEquals(ServiceAuthSessionPhase.CANCELLING, coordinator.snapshot().phase)
        assertEquals(70L, coordinator.snapshot().authGeneration)
        assertTrue(ownerTerminals.isEmpty())
        assertTrue(siblingTerminals.isEmpty())
        assertFalse(coordinator.canPublish(ownerKey, AuthOperationKind.SELECT))
        assertFalse(coordinator.canPublish(siblingKey, AuthOperationKind.LIST))
        assertFalse(
            coordinator.complete(
                ownerKey,
                AuthOperationKind.SELECT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertFalse(
            coordinator.complete(
                siblingKey,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )

        assertTrue(coordinator.finishSessionClear(plan, restartRequired = false))
        assertEquals(listOf(AuthOperationTerminal.Completed(Unit)), ownerTerminals)
        assertEquals(listOf(AuthOperationTerminal.Cancelled), siblingTerminals)
        assertEquals(ServiceAuthSessionPhase.IDLE, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
        assertFalse(coordinator.finishSessionClear(plan, restartRequired = false))
    }

    @Test
    fun `CANCEL completion owns terminal while sibling operations are cancelled`() {
        val coordinator = authorizedCoordinator(71)
        val ownerKey = AuthOperationKey(71, 2)
        val siblingKey = AuthOperationKey(71, 3)
        val ownerTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        val siblingTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        coordinator.admit(
            ownerKey,
            AuthOperationKind.CANCEL,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ownerTerminals::add,
        )
        coordinator.admit(
            siblingKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            siblingTerminals::add,
        )

        val plan = coordinator.planSessionClear(
            ownerKey,
            AuthOperationKind.CANCEL,
            AuthOperationTerminal.Completed(Unit),
        )!!
        assertTrue(ownerTerminals.isEmpty())
        assertTrue(siblingTerminals.isEmpty())
        assertTrue(coordinator.finishSessionClear(plan, restartRequired = false))
        assertEquals(listOf(AuthOperationTerminal.Completed(Unit)), ownerTerminals)
        assertEquals(listOf(AuthOperationTerminal.Cancelled), siblingTerminals)
        assertEquals(ServiceAuthSessionPhase.IDLE, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
    }

    @Test
    fun `post-commit cleanup failure keeps SELECT completed and requires restart`() {
        val coordinator = authorizedCoordinator(72)
        val ownerKey = AuthOperationKey(72, 2)
        val ownerTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        coordinator.admit(
            ownerKey,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ownerTerminals::add,
        )
        assertTrue(coordinator.markSelectValidating(ownerKey))

        val plan = coordinator.planSessionClear(
            ownerKey,
            AuthOperationKind.SELECT,
            AuthOperationTerminal.Completed(Unit),
        )!!
        assertTrue(ownerTerminals.isEmpty())
        assertTrue(coordinator.finishSessionClear(plan, restartRequired = true))
        assertEquals(listOf(AuthOperationTerminal.Completed(Unit)), ownerTerminals)
        assertEquals(ServiceAuthSessionPhase.RESTART_REQUIRED, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
    }

    @Test
    fun `session clear rejects replacement window owners and invalid kinds`() {
        val coordinator = authorizedCoordinator(73)
        val oldSelect = AuthOperationKey(73, 2)
        val oldList = AuthOperationKey(73, 3)
        coordinator.admit(
            oldSelect,
            AuthOperationKind.SELECT,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
        ) {}
        coordinator.admit(
            oldList,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
        ) {}
        val replacement = AuthOperationKey(74, 1)
        assertTrue(coordinator.begin(replacement) {} is ServiceAuthAdmission.Accepted)

        assertNull(
            coordinator.planSessionClear(
                oldSelect,
                AuthOperationKind.SELECT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertNull(
            coordinator.planSessionClear(
                oldList,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )
        assertNull(
            coordinator.planSessionClear(
                replacement,
                AuthOperationKind.BEGIN,
                AuthOperationTerminal.Completed(waitingLoginState()),
            ),
        )
        assertEquals(ServiceAuthSessionPhase.REPLACING, coordinator.snapshot().phase)
        assertEquals(73L, coordinator.snapshot().authGeneration)
        assertTrue(coordinator.finishBeginBarrier(replacement))
        assertEquals(ServiceAuthSessionPhase.WAITING, coordinator.snapshot().phase)
        assertEquals(74L, coordinator.snapshot().authGeneration)
    }

    @Test
    fun `WAITING admits CANCEL but still rejects LIST and SELECT`() {
        val coordinator = ServiceAuthCoordinator()
        val beginKey = AuthOperationKey(75, 1)
        val beginTerminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        assertTrue(coordinator.begin(beginKey, beginTerminals::add) is ServiceAuthAdmission.Accepted)
        assertTrue(coordinator.finishBeginBarrier(beginKey))

        var rejectedCallbacks = 0
        assertTrue(
            coordinator.admit(
                AuthOperationKey(75, 2),
                AuthOperationKind.LIST,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ) { rejectedCallbacks++ } is ServiceAuthAdmission.Rejected,
        )
        assertTrue(
            coordinator.admit(
                AuthOperationKey(75, 3),
                AuthOperationKind.SELECT,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ) { rejectedCallbacks++ } is ServiceAuthAdmission.Rejected,
        )
        assertEquals(0, rejectedCallbacks)

        val cancelKey = AuthOperationKey(75, 4)
        val cancelTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        assertTrue(
            coordinator.admit(
                cancelKey,
                AuthOperationKind.CANCEL,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
                cancelTerminals::add,
            ) is ServiceAuthAdmission.Accepted,
        )
        val plan = coordinator.planSessionClear(
            cancelKey,
            AuthOperationKind.CANCEL,
            AuthOperationTerminal.Completed(Unit),
        )!!
        assertTrue(cancelTerminals.isEmpty())
        assertTrue(beginTerminals.isEmpty())
        assertTrue(coordinator.finishSessionClear(plan, restartRequired = false))
        assertEquals(listOf(AuthOperationTerminal.Completed(Unit)), cancelTerminals)
        assertEquals(listOf(AuthOperationTerminal.Cancelled), beginTerminals)
        assertEquals(ServiceAuthSessionPhase.IDLE, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
    }

    @Test
    fun `ownerless teardown freezes generation until native cleanup finishes`() {
        val coordinator = ServiceAuthCoordinator()
        val beginKey = AuthOperationKey(76, 1)
        val beginTerminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        assertTrue(coordinator.begin(beginKey, beginTerminals::add) is ServiceAuthAdmission.Accepted)
        assertTrue(coordinator.finishBeginBarrier(beginKey))
        assertTrue(coordinator.markAuthorized(76))
        val listKey = AuthOperationKey(76, 2)
        val listTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        assertTrue(
            coordinator.admit(
                listKey,
                AuthOperationKind.LIST,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
                listTerminals::add,
            ) is ServiceAuthAdmission.Accepted,
        )

        val plan = coordinator.planSessionTeardown(76)!!
        assertEquals(ServiceAuthSessionPhase.CANCELLING, coordinator.snapshot().phase)
        assertEquals(76L, coordinator.snapshot().authGeneration)
        assertTrue(beginTerminals.isEmpty())
        assertTrue(listTerminals.isEmpty())
        assertFalse(
            coordinator.complete(
                listKey,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )
        var rejectedCallbacks = 0
        val replacement = coordinator.begin(AuthOperationKey(77, 1)) { rejectedCallbacks++ }
        assertEquals(
            ServiceAuthRejectReason.SESSION_UNAVAILABLE,
            (replacement as ServiceAuthAdmission.Rejected).reason,
        )
        assertEquals(0, rejectedCallbacks)

        assertTrue(coordinator.finishSessionTeardown(plan, restartRequired = true))
        assertEquals(listOf(AuthOperationTerminal.Cancelled), beginTerminals)
        assertEquals(listOf(AuthOperationTerminal.Cancelled), listTerminals)
        assertEquals(ServiceAuthSessionPhase.RESTART_REQUIRED, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
        assertFalse(coordinator.finishSessionTeardown(plan, restartRequired = true))
    }

    @Test
    fun `broken BEGIN fails only after auth cleanup finishes`() {
        val coordinator = ServiceAuthCoordinator()
        val key = AuthOperationKey(78, 1)
        val terminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        assertTrue(coordinator.begin(key, terminals::add) is ServiceAuthAdmission.Accepted)
        assertTrue(coordinator.finishBeginBarrier(key))

        val plan = coordinator.planFailure(
            key,
            ServiceAuthFailureEvent.BeginRejected,
        )!!
        assertEquals(
            ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED,
            plan.action,
        )
        assertTrue(terminals.isEmpty())
        assertFalse(
            coordinator.complete(
                key,
                AuthOperationKind.BEGIN,
                AuthOperationTerminal.Completed(waitingLoginState()),
            ),
        )
        assertTrue(coordinator.finishFailure(plan))
        assertEquals(
            listOf(AuthOperationTerminal.Failed("BROWSER_CREDENTIAL_INVALID")),
            terminals,
        )
        assertEquals(ServiceAuthSessionPhase.RESTART_REQUIRED, coordinator.snapshot().phase)
        assertEquals(0L, coordinator.snapshot().authGeneration)
    }

    @Test
    fun `rejected admission does not deliver a terminal before negative ACK`() {
        val coordinator = authorizedCoordinator(60)
        var callbackCount = 0

        val stale = coordinator.begin(AuthOperationKey(60, 3)) { callbackCount++ }
        assertEquals(
            ServiceAuthRejectReason.STALE_GENERATION,
            (stale as ServiceAuthAdmission.Rejected).reason,
        )
        assertEquals(0, callbackCount)

        val duplicateKey = AuthOperationKey(60, 2)
        assertTrue(
            coordinator.admit(
                duplicateKey,
                AuthOperationKind.LIST,
                ServiceAuthOperationPhase.NATIVE_BLOCKING,
            ) {} is ServiceAuthAdmission.Accepted,
        )
        val duplicate = coordinator.admit(
            duplicateKey,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
        ) { callbackCount++ }
        assertEquals(
            ServiceAuthRejectReason.DUPLICATE_REQUEST,
            (duplicate as ServiceAuthAdmission.Rejected).reason,
        )
        assertEquals(0, callbackCount)
    }

    @Test
    fun `auth snapshot bounds reject excessive counts or dynamic UTF8 text`() {
        val login = waitingLoginState()
        assertTrue(AuthSnapshotBounds.isValid(login, "account_1", tunnels(100, 0), null))
        assertFalse(AuthSnapshotBounds.isValid(login, "account_1", tunnels(101, 0), null))
        assertTrue(AuthSnapshotBounds.isValid(login, "account_1", tunnels(6, 512), null))
        assertFalse(AuthSnapshotBounds.isValid(login, "account_1", tunnels(6, 513), null))
        assertFalse(
            AuthSnapshotBounds.isValid(
                login,
                "account_1",
                tunnels(100, 512, longHostnames = true),
                null,
            ),
        )
    }

    private fun authorizedCoordinator(generation: Long): ServiceAuthCoordinator {
        val coordinator = ServiceAuthCoordinator()
        val begin = AuthOperationKey(generation, 1)
        assertTrue(coordinator.begin(begin) {} is ServiceAuthAdmission.Accepted)
        assertTrue(coordinator.finishBeginBarrier(begin))
        assertTrue(coordinator.markAuthorized(generation))
        return coordinator
    }

    private fun waitingLoginState() = CloudflareLoginState(
        authorizationUrl =
            "https://dash.cloudflare.com/argotunnel?callback=" +
                "https%3A%2F%2Flogin.cloudflareaccess.org%2F" + "a".repeat(43) + "%3D",
        state = ReadReceiptsTunnelState.STARTING,
        error = null,
    )

    private fun tunnels(
        tunnelCount: Int,
        totalHostnames: Int,
        longHostnames: Boolean = false,
    ): List<ExistingTunnel> {
        var hostnameIndex = 0
        return List(tunnelCount) { tunnelIndex ->
            val count = minOf(100, totalHostnames - hostnameIndex).coerceAtLeast(0)
            ExistingTunnel.create(
                id = "550e8400-e29b-41d4-a716-${tunnelIndex.toString().padStart(12, '0')}",
                name = "tunnel-$tunnelIndex",
                hostnames = List(count) {
                    val index = hostnameIndex++
                    if (longHostnames) longHostname(index) else "host-$index.example.com"
                },
            )!!
        }
    }

    private fun longHostname(index: Int): String =
        "h${index.toString().padStart(3, '0')}${"a".repeat(59)}." +
            "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
}
