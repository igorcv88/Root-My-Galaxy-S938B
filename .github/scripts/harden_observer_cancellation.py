#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))

manual = 'app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt'
replace_once(
    manual,
    '''import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay''',
    '''import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive''',
)
replace_once(
    manual,
    '''        observer?.let {
            appendLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport|scope=${if (shizuku) "system_remote_markers" else "process_tree_system"}",
            )
        }
        val process = try {
            ExploitRunControl.start(''',
    '''        val process = try {
            observer?.let {
                appendLog(
                    "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                        "transport=$transport|scope=${if (shizuku) "system_remote_markers" else "process_tree_system"}",
                )
            }
            ExploitRunControl.start(''',
)
replace_once(
    manual,
    '''        appendLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )''',
    '''        currentCoroutineContext().ensureActive()
        appendLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )''',
)

auto = 'app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt'
replace_once(
    auto,
    '''import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay''',
    '''import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive''',
)
replace_once(
    auto,
    '''        observer?.let {
            onLog(
                "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                    "transport=$transport|scope=${if (useShizuku) "system_remote_markers" else "process_tree_system"}",
            )
        }
        val process = try {
            ExploitRunControl.start(''',
    '''        val process = try {
            observer?.let {
                onLog(
                    "RMG_OBSERVER_V2|event=controller_start|available=${it.available}|" +
                        "transport=$transport|scope=${if (useShizuku) "system_remote_markers" else "process_tree_system"}",
                )
            }
            ExploitRunControl.start(''',
)
replace_once(
    auto,
    '''        onLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )''',
    '''        currentCoroutineContext().ensureActive()
        onLog(
            "RMG_OBSERVER_V2|event=controller_stop|available=${report.available}|" +
                "target_pid=${report.targetPid ?: -1}",
        )''',
)
