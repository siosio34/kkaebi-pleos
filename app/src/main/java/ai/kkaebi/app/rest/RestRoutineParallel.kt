package ai.kkaebi.app.rest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Optional narration runs alongside hardware; a voice-service failure cannot authorize or fail it. */
object RestRoutineParallel {
    suspend fun run(voice: (suspend () -> Unit)?, voiceFailed: () -> Unit, hardware: suspend () -> Unit) = coroutineScope {
        if (voice != null) launch {
            try { voice() } catch (e: CancellationException) { throw e } catch (_: Exception) { voiceFailed() }
        }
        hardware()
    }
}
