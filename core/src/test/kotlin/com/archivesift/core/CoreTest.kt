package com.archivesift.core

import org.junit.Test

class CoreTest {
    @Test fun rules() {
        val compiled = compileRules(listOf(Rule(false, Mode.Glob, "**/*.properties"), Rule(false, Mode.Regex, "(^|/)(stone|grass_block)\\.json$"), Rule(true, Mode.Glob, "private/**")))
        check(compiled.errors.isEmpty())
        check(listOf("a.properties", "a/深/b.properties", "a/stone.json", "grass_block.json").all(compiled::matches))
        check(listOf("private/a.properties", "private/stone.json", "a/Stone.json", "a/notstone.json", "a.properties.bak").none(compiled::matches))
        val exclude = compileRules(listOf(Rule(true, Mode.Regex, "temp")))
        check(exclude.matches("a/b.bin") && !exclude.matches("a/attempt.bin"))
        check(compileRules(emptyList()).matches("日本語/é.txt"))
        check(!compileRules(listOf(Rule(false, Mode.Glob, "a/*.txt"))).matches("a/b/c.txt"))
    }

    @Test fun invalidRegex() {
        for (bad in listOf("(", "(?<=x)y", "(x)\\1", "x(?=y)")) {
            val compiled = compileRules(listOf(Rule(false, Mode.Regex, bad)))
            check(compiled.errors.containsKey(0) && !compiled.matches("x"))
        }
    }

    @Test fun paths() = CoreChecks.paths()
    @Test fun unsafeArchives() = CoreChecks.unsafeArchives()
    @Test fun corruptAndUnsupported() = CoreChecks.corruptAndUnsupported()
    @Test fun actualByteLimitsAndCancellation() = CoreChecks.actualByteLimitsAndCancellation()
    @Test fun fileListDoesNotOpenContents() = CoreChecks.fileListDoesNotOpenContents()
    @Test fun folderPlanning() = CoreChecks.folderPlanning()
    @Test fun entryCountLimit() = CoreChecks.entryCountLimit()
    @Test fun inputParity() = CoreChecks.inputParity { path ->
        compileOnce.matches(path)
    }
    private val compileOnce by lazy { compileRules(listOf(Rule(false, Mode.Glob, "**/*.txt"), Rule(true, Mode.Glob, "ignored/**"))) }
}
