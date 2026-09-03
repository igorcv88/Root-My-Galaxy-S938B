from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/dev/busung/s25uroot/MainActivity.kt")
text = path.read_text()
text = replace_once(
    text,
    "import android.os.Bundle\nimport android.view.HapticFeedbackConstants\n",
    "import android.os.Bundle\nimport android.provider.DocumentsContract\nimport android.view.HapticFeedbackConstants\n",
    "DocumentsContract import",
)

page_start = text.index("@Composable\nprivate fun HistoryPage(")
list_start = text.index("\n@Composable\nprivate fun HistoryList(", page_start)
page = text[page_start:list_start]
page = replace_once(
    page,
    "    val view = LocalView.current\n",
    "    val context = LocalContext.current\n    val view = LocalView.current\n",
    "HistoryPage context",
)
page = replace_once(
    page,
    "    val selecting = selectionIds.isNotEmpty()\n    BackHandler(enabled = selectedEntry != null || selecting) {\n",
    '''    val selecting = selectionIds.isNotEmpty()
    val selectedEntries = history.filter { entry ->
        entry.id in selectionIds && entry.result != InstallRunResult.Running
    }
    val exportSelectedLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null && selectedEntries.isNotEmpty()) {
            saveRunLogs(context, treeUri, selectedEntries)
        }
    }
    BackHandler(enabled = selectedEntry != null || selecting) {
''',
    "HistoryPage launcher",
)
page = replace_once(
    page,
    '''                onClearSelection = { selectionIds = emptySet() },
                onEntryClick = { selectedHistoryId = it.id },
                onDeleteSelected = { pendingDeleteIds = selectionIds },
''',
    '''                onClearSelection = { selectionIds = emptySet() },
                onEntryClick = { selectedHistoryId = it.id },
                onSaveSelected = { exportSelectedLogsLauncher.launch(null) },
                onDeleteSelected = { pendingDeleteIds = selectionIds },
''',
    "HistoryList call",
)
text = text[:page_start] + page + text[list_start:]

list_start = text.index("@Composable\nprivate fun HistoryList(")
list_end = text.index("\n@Composable\nprivate fun EmptyHistoryCard(", list_start)
history_list = text[list_start:list_end]
history_list = replace_once(
    history_list,
    '''    onClearSelection: () -> Unit,
    onEntryClick: (InstallHistoryEntry) -> Unit,
    onDeleteSelected: () -> Unit,
''',
    '''    onClearSelection: () -> Unit,
    onEntryClick: (InstallHistoryEntry) -> Unit,
    onSaveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
''',
    "HistoryList signature",
)
history_list = replace_once(
    history_list,
    '''                        Row {
                            IconButton(onClick = {
                                clickHaptic(view)
                                onSelectAll()
                            }) {
''',
    '''                        Row {
                            IconButton(onClick = {
                                clickHaptic(view)
                                onSaveSelected()
                            }) {
                                Icon(
                                    Icons.Rounded.Save,
                                    contentDescription = stringResource(R.string.history_save_selected),
                                )
                            }
                            IconButton(onClick = {
                                clickHaptic(view)
                                onSelectAll()
                            }) {
''',
    "selection save button",
)
text = text[:list_start] + history_list + text[list_end:]

helper_marker = "\n\n@Composable\nprivate fun DiagnosticStatisticsCard("
helper_pos = text.index(helper_marker)
batch_helper = '''

private fun saveRunLogs(
    context: Context,
    treeUri: Uri,
    entries: List<InstallHistoryEntry>,
) {
    if (entries.isEmpty()) return
    val resolver = context.contentResolver
    val parentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
    }.getOrNull()
    var saved = 0
    if (parentUri != null) {
        entries.forEach { entry ->
            val documentUri = runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    "text/plain",
                    runLogFileName(entry),
                )
            }.getOrNull()
            if (documentUri != null) {
                val wrote = runCatching {
                    resolver.openOutputStream(documentUri, "w")?.use { output ->
                        output.write(diagnosticReport(entry).toByteArray(Charsets.UTF_8))
                    } ?: error("open failed")
                }.isSuccess
                if (wrote) saved++
            }
        }
    }
    Toast.makeText(
        context,
        if (saved == entries.size) {
            context.getString(R.string.history_logs_saved, saved)
        } else {
            context.getString(R.string.history_logs_saved_partial, saved, entries.size)
        },
        Toast.LENGTH_LONG,
    ).show()
}'''
text = text[:helper_pos] + batch_helper + text[helper_pos:]
path.write_text(text)

additions = {
    Path("app/src/main/res/values/strings.xml"): '''    <string name="history_save_selected">Save selected logs</string>\n    <string name="history_logs_saved">Saved %1$d logs</string>\n    <string name="history_logs_saved_partial">Saved %1$d of %2$d logs</string>\n''',
    Path("app/src/main/res/values-pt-rBR/strings.xml"): '''    <string name="history_save_selected">Salvar logs selecionados</string>\n    <string name="history_logs_saved">%1$d logs salvos</string>\n    <string name="history_logs_saved_partial">%1$d de %2$d logs salvos</string>\n''',
}
for strings, addition in additions.items():
    current = strings.read_text()
    if 'name="history_save_selected"' in current:
        raise SystemExit(f"{strings}: selected export strings already exist")
    if current.count("</resources>") != 1:
        raise SystemExit(f"{strings}: bad resources terminator")
    strings.write_text(current.replace("</resources>", addition + "</resources>", 1))
