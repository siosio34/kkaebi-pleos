package ai.kkaebi.app

import ai.kkaebi.app.rest.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestParallelExecutorTest {
    private val full = linkedMapOf<String, Double?>("seatRecline" to 50.0, "blind" to 5.0,
        "brightness" to 10.0, "seatVentilation" to 1.0, "hvacPower" to 1.0,
        "temperature" to 22.0, "fan" to 1.0)

    @Test fun independentLanesHaveThreeInflightItemsAndMotorsRemainSequential() = runTest {
        val started = mutableListOf<String>(); val ended = mutableListOf<String>()
        var current = 0; var max = 0
        RestParallelExecutor().execute(RestParallelPlan.create(full), {}, { error("selected power is explicit") }, { _, _, _ -> fail() }) { key ->
            started += key; current++; max = maxOf(max, current)
            delay(100); current--; ended += key; true
        }
        assertEquals(3, max)
        assertEquals(setOf("seatRecline", "brightness", "hvacPower"), started.take(3).toSet())
        assertTrue(started.indexOf("blind") > started.indexOf("seatRecline"))
        assertTrue(started.indexOf("temperature") > started.indexOf("hvacPower"))
        assertEquals(300, testScheduler.currentTime)
        assertEquals(full.keys, ended.toSet())
    }
    @Test fun powerFailureBlocksBothDependentsButIndependentLanesContinue() = runTest {
        val writes = mutableListOf<String>(); val blocked = mutableListOf<String>()
        RestParallelExecutor().execute(RestParallelPlan.create(full), {}, { true }, { key, deps, _ ->
            assertEquals(listOf("hvacPower"), deps); blocked += key
        }) { key -> writes += key; delay(1); key != "hvacPower" }
        assertEquals(listOf("temperature", "fan"), blocked)
        assertFalse(writes.contains("temperature")); assertFalse(writes.contains("fan"))
        assertTrue(writes.containsAll(listOf("seatRecline", "blind", "brightness", "seatVentilation")))
    }
    @Test fun unrelatedFailureDoesNotBlockFanOrOtherLane() = runTest {
        val calls = mutableListOf<String>()
        RestParallelExecutor().execute(RestParallelPlan.create(full), {}, { true }, { _, _, _ -> fail() }) { key ->
            calls += key; key !in setOf("temperature", "brightness")
        }
        assertTrue(calls.containsAll(full.keys))
    }
    @Test fun unselectedPowerMustBeReadOnBeforeClimateWithoutImplicitPowerWrite() = runTest {
        var reads = 0; val calls = mutableListOf<String>(); val blocked = mutableListOf<String>()
        RestParallelExecutor().execute(RestParallelPlan.create(mapOf("temperature" to 22.0, "fan" to 1.0)), {},
            { reads++; false }, { key, _, _ -> blocked += key }) { key -> calls += key; true }
        assertEquals(1, reads); assertTrue(calls.isEmpty()); assertEquals(listOf("temperature", "fan"), blocked)
    }
    @Test fun powerOffWaitsForEveryLaneIncludingFailedItems() = runTest {
        val end = mutableListOf<String>(); val targets = full + ("hvacPower" to 0.0)
        RestParallelExecutor().execute(RestParallelPlan.create(targets), {}, { true }, { _, _, _ -> fail() }) { key ->
            if(key == "hvacPower") assertEquals(targets.size - 1, end.size)
            delay(if(key == "seatRecline") 150 else 10); end += key; key != "brightness"
        }
        assertEquals("hvacPower", end.last())
    }
    @Test fun interruptionCancelsSiblingReadsAwaitsStopAndNeverRunsLaterMotor() = runTest {
        val safety = RestOperationSafety(); safety.begin(); var safe = true
        val started = mutableListOf<String>(); var stopped = false; var cleanupCompleted = false
        val failure = runCatching {
            RestParallelExecutor().execute(RestParallelPlan.create(full), { safety.requireSafe(safe) }, { true }, { _, _, _ -> fail() }) { key ->
                started += key
                if (key == "seatRecline") try { awaitCancellation() } finally {
                    withContext(NonCancellable) { stopped = true; delay(25); cleanupCompleted = true }
                } else { delay(10); safe = false; safety.observe(false); safe = true; awaitCancellation() }
            }
        }.exceptionOrNull()
        assertNotNull(failure); assertTrue(stopped); assertTrue(cleanupCompleted)
        assertFalse(started.contains("blind")); assertFalse(started.contains("fan"))
        assertTrue(testScheduler.currentTime < 1000)
    }
    @Test fun restoreKeepsOnlyOneMotorAndUsesBlindBeforeSeat() {
        val plan = RestParallelPlan.create(full, restoring = true)
        assertEquals(listOf("blind", "seatRecline"), plan.lanes[0].map { it.key })
        assertEquals(3, plan.lanes.size)
    }
    @Test fun windowRunsAfterBlindAndRestoresBeforeOtherMotors() {
        val targets = full + ("window" to 0.0)
        assertEquals(listOf("seatRecline", "blind", "window"), RestParallelPlan.create(targets).lanes[0].map { it.key })
        assertEquals(listOf("window", "blind", "seatRecline"), RestParallelPlan.create(targets, restoring = true).lanes[0].map { it.key })
    }
    @Test fun blockedMetadataIsTerminalWithoutInventingActualValue() {
        val plan = RestParallelPlan.create(full)
        val s = RestControlOperation().begin("apply", "op").enqueue("op", full.map { it.key to it.value }, plan)
            .progress("op", "temperature", "blocked", message = "power unavailable", blockedBy = listOf("hvacPower"))
        val step = s.steps.first { it.key == "temperature" }
        assertEquals("hvac", step.lane); assertEquals(listOf("hvacPower"), step.dependencies)
        assertEquals(listOf("hvacPower"), step.blockedBy); assertNull(step.actual); assertTrue(step.terminal)
        assertEquals("blocked", s.finish("op", false).steps.first { it.key == "temperature" }.state)
        assertEquals(s, s.progress("op", "temperature", "applied", actual = 22.0))
    }
}
