package com.example.build

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Проверяет счётчики и суммирует только отчёты, переданные обработчиком текущего запуска. */
class TestReportTotals {
    static final List<String> COUNTERS = ['tests', 'failures', 'errors', 'skipped'].asImmutable()

    static Map<String, Long> readExecution(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Missing report directory: ${directory}")
        }
        def totals = COUNTERS.collectEntries { [(it): 0L] }
        def suites = [:]
        def reports
        Files.newDirectoryStream(directory, 'TEST-*.xml').withCloseable { reports = it.toList() }
        if (reports.empty) throw new IllegalStateException("Missing Surefire XML reports: ${directory}")
        reports.sort().each { report ->
            def parsed = readReport(report)
            def previous = suites.putIfAbsent(parsed.name, parsed)
            if (previous != null && previous != parsed) {
                throw new IllegalStateException("Conflicting duplicate test suite: ${parsed.name}")
            }
            if (previous == null) add(totals, parsed.counters)
        }
        totals
    }

    static Map readReport(Path report) {
        if (Files.isSymbolicLink(report)) {
            throw new IllegalStateException("Symbolic report links are prohibited: ${report}")
        }
        try {
            def factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
            factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
            factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '')
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, '')
            factory.setXIncludeAware(false)
            factory.setExpandEntityReferences(false)
            def root = Files.newInputStream(report).withCloseable { factory.newDocumentBuilder().parse(it).documentElement }
            if (root.tagName != 'testsuite' || !root.getAttribute('name')) {
                throw new IllegalStateException('Expected a named testsuite')
            }
            def counters = COUNTERS.collectEntries { name ->
                String value = root.getAttribute(name)
                if (!(value ==~ /[0-9]+/)) throw new IllegalStateException("Invalid ${name} counter")
                [(name): Long.parseLong(value)]
            }
            def cases = []
            def observed = [tests: 0L, failures: 0L, errors: 0L, skipped: 0L]
            children(root).each { node ->
                if (node.nodeName != 'testcase') return
                def outcomes = ['failure', 'error', 'skipped'].findAll { tag ->
                    children(node).any { it.nodeName == tag }
                }
                if (outcomes.size() > 1) throw new IllegalStateException('Conflicting testcase outcomes')
                observed.tests++
                if (outcomes) observed[[failure: 'failures', error: 'errors', skipped: 'skipped'][outcomes[0]]]++
                cases.add([node.getAttribute('classname'), node.getAttribute('name'), outcomes])
            }
            if (observed != counters) throw new IllegalStateException('Counters do not match testcase results')
            [name: root.getAttribute('name'), counters: counters, cases: cases]
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid test report ${report}: ${failure.message}", failure)
        }
    }

    private static List children(node) {
        (0..<node.childNodes.length).collect { node.childNodes.item(it) }
    }

    static void add(Map<String, Long> target, Map<String, Long> source) {
        COUNTERS.each { target[it] = Math.addExact(target[it], source[it]) }
    }

    static String format(Map<String, Long> totals) {
        "Total tests: ${totals.tests} | Failures: ${totals.failures} | Errors: ${totals.errors} | Skipped: ${totals.skipped}"
    }
}
