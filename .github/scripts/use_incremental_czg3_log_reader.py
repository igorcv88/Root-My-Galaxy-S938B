#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


Path("app/src/main/java/dev/busung/s25uroot/IncrementalFileLogReader.kt").write_text(
'''package dev.busung.s25uroot

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Reads only newly appended bytes from a stable exploit log path. */
internal class IncrementalFileLogReader(private val file: File) : Closeable {
    private var reader: RandomAccessFile? = null
    private var offset = 0L
    private val accumulated = StringBuilder()
    private val scratch = ByteArray(DEFAULT_BUFFER_SIZE)

    fun snapshot(): String {
        val input = reader ?: runCatching { RandomAccessFile(file, "r") }
            .getOrNull()
            ?.also { reader = it }
            ?: return accumulated.toString()
        val length = runCatching { input.length() }.getOrDefault(offset)
        if (length < offset) {
            offset = 0L
            accumulated.clear()
        }
        runCatching { input.seek(offset) }.getOrElse { return accumulated.toString() }
        while (true) {
            val count = runCatching { input.read(scratch) }.getOrDefault(-1)
            if (count <= 0) break
            accumulated.append(String(scratch, 0, count, Charsets.UTF_8))
            offset += count
        }
        return accumulated.toString()
    }

    override fun close() {
        runCatching { reader?.close() }
        reader = null
    }
}
''')

manual = "app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"
replace_once(
    manual,
    '''        val logPrefix = mutableState.value.log
        val captured = StringBuilder()
        val readLog: () -> String = if (shizuku) {
            { drainProcessOutput(process, captured) }
        } else {
            // Keep draining stdout while polling: if the helper fills the OS
            // pipe buffer it blocks on write and stops making log progress,
            // which would trip the stall detector spuriously.
            { drainProcessOutput(process, captured); logFile.readTextIfPresent() }
        }''',
    '''        val logPrefix = mutableState.value.log
        val captured = StringBuilder()
        val incrementalLog = if (externalObserverMode && !shizuku) {
            IncrementalFileLogReader(logFile)
        } else {
            null
        }
        val readLog: () -> String = if (shizuku) {
            { drainProcessOutput(process, captured) }
        } else {
            // Keep draining stdout while polling: if the helper fills the OS
            // pipe buffer it blocks on write and stops making log progress.
            // CZG3 reads only appended log bytes to avoid repeatedly scanning
            // the whole file from the controller process.
            {
                drainProcessOutput(process, captured)
                incrementalLog?.snapshot() ?: logFile.readTextIfPresent()
            }
        }''',
)
replace_once(
    manual,
    '''                delay(if (shizuku) SHIZUKU_LOG_POLL_INTERVAL else LOG_POLL_INTERVAL)
            }''',
    '''                delay(
                    if (shizuku || externalObserverMode) {
                        SHIZUKU_LOG_POLL_INTERVAL
                    } else {
                        LOG_POLL_INTERVAL
                    },
                )
            }''',
)
replace_once(
    manual,
    '''            validateTerminalExploit(
                diagnosticSnapshot,
                exitCode,
                rawLog,
                profile.profileId == CZG3_PROFILE_ID,
            )
        } finally {
            if (process.isAlive) {''',
    '''            validateTerminalExploit(
                diagnosticSnapshot,
                exitCode,
                rawLog,
                profile.profileId == CZG3_PROFILE_ID,
            )
        } finally {
            incrementalLog?.close()
            if (process.isAlive) {''',
)

auto = "app/src/main/java/dev/busung/s25uroot/AutoRootRunner.kt"
replace_once(
    auto,
    '''        val output = ProcessOutputCollector(process)
        val readLog: () -> String = if (useShizuku) {
            { output.snapshot() + readShizukuLog() }
        } else {
            { output.snapshot(); logFile.readTextIfPresent() }
        }''',
    '''        val output = ProcessOutputCollector(process)
        val incrementalLog = if (externalObserverMode && !useShizuku) {
            IncrementalFileLogReader(logFile)
        } else {
            null
        }
        val readLog: () -> String = if (useShizuku) {
            { output.snapshot() + readShizukuLog() }
        } else {
            { output.snapshot(); incrementalLog?.snapshot() ?: logFile.readTextIfPresent() }
        }''',
)
replace_once(
    auto,
    '''                delay(if (useShizuku) SHIZUKU_LOG_POLL_INTERVAL else LOG_POLL_INTERVAL)
            }''',
    '''                delay(
                    if (useShizuku || externalObserverMode) {
                        SHIZUKU_LOG_POLL_INTERVAL
                    } else {
                        LOG_POLL_INTERVAL
                    },
                )
            }''',
)
replace_once(
    auto,
    '''            validateTerminalExploit(
                diagnosticSnapshot,
                exitCode,
                rawLog,
                payloads.profile.profileId == CZG3_PROFILE_ID,
            )
        } finally {
            if (process.isAlive) {''',
    '''            validateTerminalExploit(
                diagnosticSnapshot,
                exitCode,
                rawLog,
                payloads.profile.profileId == CZG3_PROFILE_ID,
            )
        } finally {
            incrementalLog?.close()
            if (process.isAlive) {''',
)
