package com.example.build

import groovy.json.JsonOutput
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.ExecutionListener
import org.apache.maven.plugin.PluginParameterExpressionEvaluator
import org.codehaus.plexus.util.xml.Xpp3Dom

/** Сохраняет стандартный слушатель Maven и добавляет итог после завершения реактора. */
class TestSummaryHook implements InvocationHandler {
    static final List<String> SELECTORS = ['test', 'surefire.includes', 'surefire.excludes', 'groups',
        'excludedGroups', 'surefire.suiteXmlFiles', 'surefire.includesFile', 'surefire.excludesFile',
        'includeJUnit5Engines', 'excludeJUnit5Engines',
        'surefire.includeJUnit5Engines', 'surefire.excludeJUnit5Engines']
    final def session
    final def log
    final def delegate
    final Path root
    final String run = UUID.randomUUID().toString()
    final Set<String> reasons = ConcurrentHashMap.newKeySet()
    final Map<Object, Map> executions = new ConcurrentHashMap<>()
    final AtomicInteger sequence = new AtomicInteger()
    final Set<Path> verified = ConcurrentHashMap.newKeySet()
    final Set<Path> expected

    TestSummaryHook(session, log, Path root) {
        this.session = session
        this.log = log
        this.root = root.toRealPath()
        this.delegate = session.request.executionListener
        def parent = session.allProjects.find { it.basedir.toPath().toRealPath() == this.root }
        if (parent == null) throw new IllegalStateException('Cannot locate the root reactor project')
        expected = parent.modules.collect { this.root.resolve(it).toRealPath() }.toSet()
        if (session.request.selectedProjects || session.request.excludedProjects || session.request.resumeFrom ||
                !session.projects.collect { it.basedir.toPath().toRealPath() }.containsAll(expected)) {
            reasons.add('partial reactor')
        }
        SELECTORS.each { if (session.userProperties.getProperty(it)) reasons.add('selected tests') }
        if (wantsSummary()) {
            writeManifest(false, ['reactor running'], TestReportTotals.COUNTERS.collectEntries { [(it): 0L] }, [])
        }
    }

    static void install(session, log, Path root) {
        def handler = new TestSummaryHook(session, log, root)
        session.request.executionListener = Proxy.newProxyInstance(ExecutionListener.class.classLoader,
            [ExecutionListener] as Class[], handler) as ExecutionListener
    }

    @Override
    Object invoke(Object proxy, Method method, Object[] arguments) {
        if (method.name == 'toString') return 'ReactorTestSummary'
        if (method.name == 'hashCode') return System.identityHashCode(proxy)
        if (method.name == 'equals') return proxy.is(arguments[0])
        def event = arguments ? arguments[0] : null
        if (method.name == 'mojoStarted' && isSurefire(event)) beginExecution(event)
        if (method.name == 'mojoSucceeded') {
            if (isSurefire(event)) executions[event.mojoExecution].completed = true
            if (event.mojoExecution.lifecyclePhase == 'verify') verified.add(event.project.basedir.toPath().toRealPath())
        }
        if (method.name == 'sessionEnded') finishSafely()
        if (delegate == null) return null
        try {
            method.invoke(delegate, arguments)
        } catch (InvocationTargetException failure) {
            throw failure.cause
        }
    }

    private static boolean isSurefire(event) {
        event?.mojoExecution?.groupId == 'org.apache.maven.plugins' &&
            event.mojoExecution.artifactId == 'maven-surefire-plugin' && event.mojoExecution.goal == 'test'
    }

    private void beginExecution(event) {
        def evaluator = new PluginParameterExpressionEvaluator(event.session, event.mojoExecution)
        def config = event.mojoExecution.configuration
        boolean skipped = false
        ['skip', 'skipTests', 'skipExec'].each { name ->
            def value = config.getChild(name)?.value
            if (value && evaluator.evaluate(value)?.toString()?.equalsIgnoreCase('true')) {
                skipped = true
                reasons.add('tests skipped')
            }
        }
        ['test', 'groups', 'excludedGroups', 'suiteXmlFiles', 'includesFile', 'excludesFile',
            'includeJUnit5Engines', 'excludeJUnit5Engines'].each { name ->
            def value = config.getChild(name)?.value
            if (value && evaluator.evaluate(value)) reasons.add('selected tests')
        }
        ['includes', 'excludes', 'suiteXmlFiles', 'includeJUnit5Engines', 'excludeJUnit5Engines'].each { name ->
            if (config.getChild(name)?.children?.length) reasons.add('selected tests')
        }
        Path directory = new File(event.project.build.directory).toPath()
            .resolve('surefire-reports').resolve(run + '-' + sequence.incrementAndGet())
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        def reportDirectory = config.getChild('reportsDirectory')
        if (reportDirectory == null) {
            reportDirectory = new Xpp3Dom('reportsDirectory')
            config.addChild(reportDirectory)
        }
        reportDirectory.value = directory.toAbsolutePath().toString()
        executions[event.mojoExecution] = [project: event.project.basedir.toPath().toRealPath(),
            directory: directory, completed: false, skipped: skipped]
    }

    private boolean wantsSummary() {
        session.request.goals.any { it in ['verify', 'install', 'deploy'] }
    }

    private void writeManifest(boolean complete, List<String> why, Map totals, List reports) {
        def previous = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = this.class.classLoader
            Path output = root.resolve('target/test-summary.json')
            Files.createDirectories(output.parent)
            Files.writeString(output, JsonOutput.prettyPrint(JsonOutput.toJson(
                [run: run, complete: complete, reasons: why, totals: totals, reports: reports])))
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    private void finishSafely() {
        def previous = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = this.class.classLoader
            finish()
        } catch (Exception failure) {
            session.result.addException(new MavenExecutionException('Test summary failed: ' + failure.message,
                root.resolve('pom.xml').toFile()))
            log.error('INCOMPLETE: test summary failed: ' + failure.message)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    private void finish() {
        if (!wantsSummary()) return
        boolean failed = session.result.hasExceptions()
        if (failed) reasons.add('build failed')
        if (!verified.containsAll(expected)) reasons.add('verification unfinished')
        def totals = TestReportTotals.COUNTERS.collectEntries { [(it): 0L] }
        def reports = []
        boolean reportError = false
        executions.values().sort { it.directory.toString() }.each { execution ->
            try {
                if (!execution.skipped) {
                    TestReportTotals.add(totals, TestReportTotals.readExecution(execution.directory))
                    reports.add(execution.directory.toString())
                }
            } catch (Exception error) {
                reportError = true
                log.error(error.message)
            }
        }
        if (!executions.values().collect { it.project }.containsAll(expected)) reportError = true
        if (totals.failures || totals.errors) {
            reasons.add('test failures')
            if (!failed) session.result.addException(new MavenExecutionException('Tests failed', root.resolve('pom.xml').toFile()))
        }
        if (reportError && reasons.empty) {
            session.result.addException(new MavenExecutionException('Invalid or missing current-run Surefire reports', root.resolve('pom.xml').toFile()))
        }
        if (reportError) reasons.add('invalid or missing reports')
        String line = TestReportTotals.format(totals)
        writeManifest(reasons.empty, reasons.sort(), totals, reports)
        if (reasons.empty) log.info(line)
        else log.warn('INCOMPLETE (' + reasons.sort().join(', ') + '): ' + line)
    }
}
