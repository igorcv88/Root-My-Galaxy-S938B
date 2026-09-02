#!/usr/bin/env python3
from pathlib import Path

observer = Path('app/src/main/java/dev/busung/s25uroot/ExploitObserver.kt')
text = observer.read_text()
old = '''internal data class ExploitObserverMarkerBatch(
    val lines: List<String>,
    val consumedCharacters: Int,
)

/** Extracts only complete, useful exploit marker lines from an append-only stream. */
'''
new = '''internal data class ExploitObserverMarkerBatch(
    val lines: List<String>,
    val consumedCharacters: Int,
)

internal data class ExploitObserverChildSnapshot(
    val tid: Int,
    val children: Set<Long>,
)

/** Locates the one child created synchronously by ProcessBuilder on this exact thread. */
internal object ExploitObserverChildLocator {
    fun snapshotCurrentThread(): ExploitObserverChildSnapshot {
        val tid = android.os.Process.myTid()
        return ExploitObserverChildSnapshot(tid, readChildren(tid))
    }

    fun findSingleNewChild(snapshot: ExploitObserverChildSnapshot): Long? {
        if (android.os.Process.myTid() != snapshot.tid) return null
        return singleNewChild(snapshot.children, readChildren(snapshot.tid))
    }

    internal fun parseChildren(raw: String): Set<Long> = raw
        .trim()
        .split(Regex("\\s+"))
        .asSequence()
        .filter(String::isNotBlank)
        .mapNotNull(String::toLongOrNull)
        .filter { it > 0L }
        .toSet()

    internal fun singleNewChild(before: Set<Long>, after: Set<Long>): Long? =
        (after - before).singleOrNull()

    private fun readChildren(tid: Int): Set<Long> = runCatching {
        File("/proc/self/task/$tid/children")
            .readText(Charsets.US_ASCII)
            .let(::parseChildren)
    }.getOrDefault(emptySet())
}

/** Extracts only complete, useful exploit marker lines from an append-only stream. */
'''
if text.count(old) != 1:
    raise SystemExit('expected child locator insertion point exactly once')
text = text.replace(old, new, 1)

old = '''    fun signalMarker(line: String) {
'''
new = '''    fun attachPid(pid: Long): Boolean {
        if (!available || stopped || pid <= 0L) return false
        val attached = runCatching {
            context.startService(
                Intent(context, ExploitObserverService::class.java)
                    .setAction(ExploitObserverService.ACTION_ATTACH)
                    .putExtra(ExploitObserverService.EXTRA_PID, pid),
            )
        }.isSuccess
        if (attached) attachedPid = pid
        return attached
    }

    fun signalMarker(line: String) {
'''
if text.count(old) != 1:
    raise SystemExit('expected signalMarker insertion point exactly once')
text = text.replace(old, new, 1)

old = '''            payloadLog: File?,
            attachController: Boolean,
        ): ExploitObserverSession {
'''
new = '''            payloadLog: File?,
        ): ExploitObserverSession {
'''
if text.count(old) != 1:
    raise SystemExit('expected attachController parameter exactly once')
text = text.replace(old, new, 1)

old = '''                val available = started && ready.readTextIfPresent() == "ready"
                val controllerPid = if (available && attachController) {
                    android.os.Process.myPid().toLong()
                } else {
                    null
                }
                if (controllerPid != null) {
                    runCatching {
                        context.startService(
                            Intent(context, ExploitObserverService::class.java)
                                .setAction(ExploitObserverService.ACTION_ATTACH)
                                .putExtra(ExploitObserverService.EXTRA_PID, controllerPid),
                        )
                    }
                }
                if (!available && started) requestStop(context)
                return ExploitObserverSession(
                    context = context,
                    outputFile = output,
                    readyFile = ready,
                    doneFile = done,
                    available = available,
                    attachedPid = controllerPid,
                )
'''
new = '''                val available = started && ready.readTextIfPresent() == "ready"
                if (!available && started) requestStop(context)
                return ExploitObserverSession(
                    context = context,
                    outputFile = output,
                    readyFile = ready,
                    doneFile = done,
                    available = available,
                    attachedPid = null,
                )
'''
if text.count(old) != 1:
    raise SystemExit('expected controller attachment block exactly once')
observer.write_text(text.replace(old, new, 1))

for file_name, shizuku_expr, logger in [
    ('app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt', 'useShizuku', 'onLog'),
    ('app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt', 'shizuku', 'appendLog'),
]:
    path = Path(file_name)
    text = path.read_text()
    old_call = f'''                payloadLog = if ({shizuku_expr}) null else logFile,\n                attachController = !{shizuku_expr},\n'''
    new_call = f'''                payloadLog = if ({shizuku_expr}) null else logFile,\n'''
    if text.count(old_call) != 1:
        raise SystemExit(f'expected attachController call exactly once in {file_name}')
    text = text.replace(old_call, new_call, 1)

    process_marker = '''        val process = try {\n'''
    snapshot_block = f'''        val observerChildSnapshot = if (externalObserverMode && !{shizuku_expr}) {{\n            ExploitObserverChildLocator.snapshotCurrentThread()\n        }} else {{\n            null\n        }}\n'''
    if text.count(process_marker) != 1:
        raise SystemExit(f'expected process marker exactly once in {file_name}')
    text = text.replace(process_marker, snapshot_block + process_marker, 1)

    post_marker = '''        val logPrefix = mutableState.value.log\n''' if 'InstallViewModel' in file_name else '''\n        val output = ProcessOutputCollector(process)\n'''
    attach_block = f'''        if (observerChildSnapshot != null) {{\n            val localPid = ExploitObserverChildLocator.findSingleNewChild(observerChildSnapshot)\n            val attached = localPid?.let {{ observer?.attachPid(it) }} ?: false\n            {logger}(\n                "RMG_OBSERVER_V2|event=controller_attach|attached=$attached|" +\n                    "target_pid=${{localPid ?: -1}}",\n            )\n        }}\n'''
    if text.count(post_marker) != 1:
        raise SystemExit(f'expected post-spawn marker exactly once in {file_name}')
    text = text.replace(post_marker, attach_block + post_marker, 1)

    if 'InstallViewModel' in file_name:
        old_finally = '''        } finally {\n            incrementalLog?.close()\n            if (process.isAlive) {\n                process.destroy()\n                delay(500.milliseconds)\n                if (process.isAlive) process.destroyForcibly()\n            }\n            stopObserver(observer)\n        }\n        appendLog(app.getString(R.string.log_bootstrap_root))\n'''
        new_finally = '''        } finally {\n            withContext(NonCancellable) {\n                incrementalLog?.close()\n                if (process.isAlive) {\n                    process.destroy()\n                    delay(500.milliseconds)\n                    if (process.isAlive) process.destroyForcibly()\n                }\n                stopObserver(observer)\n            }\n        }\n        currentCoroutineContext().ensureActive()\n        appendLog(app.getString(R.string.log_bootstrap_root))\n'''
    else:
        old_finally = '''        } finally {\n            incrementalLog?.close()\n            if (process.isAlive) {\n                process.destroy()\n                delay(500.milliseconds)\n                if (process.isAlive) process.destroyForcibly()\n            }\n            output.awaitCompletion()\n            stopObserver(observer)\n        }\n        onLog(context.getString(R.string.log_bootstrap_root))\n'''
        new_finally = '''        } finally {\n            withContext(NonCancellable) {\n                incrementalLog?.close()\n                if (process.isAlive) {\n                    process.destroy()\n                    delay(500.milliseconds)\n                    if (process.isAlive) process.destroyForcibly()\n                }\n                output.awaitCompletion()\n                stopObserver(observer)\n            }\n        }\n        currentCoroutineContext().ensureActive()\n        onLog(context.getString(R.string.log_bootstrap_root))\n'''
    if text.count(old_finally) != 1:
        raise SystemExit(f'expected finalizer exactly once in {file_name}')
    text = text.replace(old_finally, new_finally, 1)
    path.write_text(text)

# Source-level safety assertions.
observer_text = observer.read_text()
if 'android.os.Process.myPid()' in observer_text:
    raise SystemExit('observer still attaches to the app controller PID')
if 'fun attachPid(pid: Long): Boolean' not in observer_text:
    raise SystemExit('direct PID attachment API missing')
if '/proc/self/task/$tid/children' not in observer_text:
    raise SystemExit('thread-specific child locator missing')
for file_name in [
    'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt',
    'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt',
]:
    text = Path(file_name).read_text()
    if 'process.pid()' in text:
        raise SystemExit(f'unsupported Process.pid remained in {file_name}')
    if 'ExploitObserverChildLocator.snapshotCurrentThread()' not in text:
        raise SystemExit(f'pre-spawn child snapshot missing in {file_name}')
    if 'ExploitObserverChildLocator.findSingleNewChild(observerChildSnapshot)' not in text:
        raise SystemExit(f'post-spawn child resolution missing in {file_name}')
    if 'withContext(NonCancellable)' not in text:
        raise SystemExit(f'non-cancellable teardown missing in {file_name}')
    if text.count('currentCoroutineContext().ensureActive()') < 2:
        raise SystemExit(f'post-cleanup cancellation gate missing in {file_name}')
