#!/usr/bin/env python3
from pathlib import Path
import runpy

# Apply the main source transformation first.
runpy.run_path('.github/scripts/address_observer_review_v2.py', run_name='__main__')

# The generator deliberately lives as a temporary review helper. Correct the
# generated Kotlin escaping here so the production source contains Regex("\\s+").
observer = Path('app/src/main/java/dev/busung/s25uroot/ExploitObserver.kt')
text = observer.read_text()
bad = r'Regex("\s+")'
good = r'Regex("\\s+")'
if text.count(bad) != 1:
    raise SystemExit('expected generated single-backslash whitespace regex exactly once')
observer.write_text(text.replace(bad, good, 1))

# Add deterministic unit coverage for the child-list parsing/delta logic. These
# tests do not touch /proc and therefore remain ordinary host unit tests.
test_path = Path('app/src/test/java/dev/busung/s25uroot/ExploitObserverTest.kt')
test = test_path.read_text()
needle = '''    @Test
    fun markerParserCanConsumeTerminalLineWithoutNewline() {
        val log = "[*] exploit completed attempt=1"
        val batch = ExploitObserverMarkerParser.parseNewLines(
            log,
            0,
            includeTrailingLine = true,
        )
        assertEquals(listOf(log), batch.lines)
        assertTrue(batch.consumedCharacters == log.length)
    }
'''
addition = needle + '''

    @Test
    fun childLocatorParsesWhitespaceAndFindsOnlyOneNewChild() {
        assertEquals(
            setOf(12L, 34L, 56L),
            ExploitObserverChildLocator.parseChildren("12  34\\t56\\n"),
        )
        assertEquals(
            34L,
            ExploitObserverChildLocator.singleNewChild(setOf(12L), setOf(12L, 34L)),
        )
        assertEquals(
            null,
            ExploitObserverChildLocator.singleNewChild(
                setOf(12L),
                setOf(12L, 34L, 56L),
            ),
        )
        assertEquals(
            null,
            ExploitObserverChildLocator.singleNewChild(setOf(12L), setOf(12L)),
        )
    }
'''
if test.count(needle) != 1:
    raise SystemExit('expected observer test insertion point exactly once')
test_path.write_text(test.replace(needle, addition, 1))
