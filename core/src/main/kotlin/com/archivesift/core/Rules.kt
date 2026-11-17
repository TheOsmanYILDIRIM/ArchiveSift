package com.archivesift.core

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

const val MAX_REGEX_LENGTH = 32_768
const val MAX_GLOB_LENGTH = 1_024

enum class Mode { Glob, Regex }

fun patternLimit(mode: Mode): Int = if (mode == Mode.Regex) MAX_REGEX_LENGTH else MAX_GLOB_LENGTH
data class Rule(val exclude: Boolean, val mode: Mode, val text: String)
data class Rules(val errors: Map<Int, String>, private val matchers: List<Pair<Rule, Pattern>>) {
    fun matches(path: String): Boolean {
        if (errors.isNotEmpty()) return false
        return matchers.none { (rule, p) -> rule.exclude && p.matcher(path).find() } &&
            (matchers.none { !it.first.exclude } || matchers.any { (rule, p) -> !rule.exclude && p.matcher(path).find() })
    }
}

fun compileRules(rules: List<Rule>): Rules {
    val errors = mutableMapOf<Int, String>()
    val matchers = rules.mapIndexedNotNull { i, r ->
        try {
            val limit = patternLimit(r.mode)
            require(r.text.isNotEmpty() && r.text.length <= limit) { "Enter a pattern of 1–$limit characters." }
            r to Pattern.compile(if (r.mode == Mode.Regex) r.text else glob(r.text))
        } catch (e: PatternSyntaxException) { errors[i] = e.description; null } catch (e: IllegalArgumentException) { errors[i] = e.message ?: "Invalid pattern"; null }
    }
    return Rules(errors, matchers)
}

private fun glob(value: String): String = buildString {
    append("\\A(?:")
    val parts = value.split('/')
    require(parts.none { it.isEmpty() }) { "Use a relative glob with nonempty path segments." }
    parts.forEachIndexed { i, part ->
        if (part == "**") append(if (i == parts.lastIndex) ".*" else "(?:[^/]+/)*")
        else {
            require("**" !in part) { "Use ** only as an entire path segment." }
            part.forEach { c -> when (c) {
                '*' -> append("[^/]*")
                '?' -> append("[^/]")
                else -> { if (c in "\\.^$|()[]{}+") append('\\'); append(c) }
            } }
            if (i < parts.lastIndex) append('/')
        }
    }
    append(")\\z")
}
