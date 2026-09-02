#!/usr/bin/env python3
from pathlib import Path

observer = Path('app/src/main/java/dev/busung/s25uroot/ExploitObserver.kt')
text = observer.read_text()
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
    text = text.replace(
        f'''                payloadLog = if ({shizuku_expr}) null else logFile,\n                attachController = !{shizuku_expr},\n''',
        f'''                payloadLog = if ({shizuku_expr}) null else logFile,\n''',
        1,
    )
    if f'attachController = !{shizuku_expr}' in text:
        raise SystemExit(f'attachController remained in {file_name}')

    marker = '''        val logPrefix = mutableState.value.log\n''' if 'InstallViewModel' in file_name else '''\n        val output = ProcessOutputCollector(process)\n'''
    attach_block = f'''        if (externalObserverMode && !{shizuku_expr}) {{\n            val localPid = runCatching {{ process.pid() }}.getOrNull()\n            val attached = localPid?.let {{ observer?.attachPid(it) }} ?: false\n            {logger}(\n                "RMG_OBSERVER_V2|event=controller_attach|attached=$attached|" +\n                    "target_pid=${{if (attached) localPid else -1}}",\n            )\n        }}\n'''
    if text.count(marker) != 1:
        raise SystemExit(f'expected post-spawn marker exactly once in {file_name}')
    text = text.replace(marker, attach_block + marker, 1)

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
for file_name in [
    'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt',
    'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt',
]:
    text = Path(file_name).read_text()
    if 'runCatching { process.pid() }.getOrNull()' not in text:
        raise SystemExit(f'local helper PID attachment missing in {file_name}')
    if 'withContext(NonCancellable)' not in text:
        raise SystemExit(f'non-cancellable teardown missing in {file_name}')
    if 'currentCoroutineContext().ensureActive()' not in text:
        raise SystemExit(f'post-cleanup cancellation gate missing in {file_name}')
