import org.junit.platform.console.ConsoleLauncher

// Проверки сборочного инструмента выполняются только в родительском модуле и не входят в итог сервисов.
if (['skipTests', 'maven.test.skip', 'maven.test.skip.exec'].any {
    session.userProperties.getProperty(it, session.systemProperties.getProperty(it,
        project.properties.getProperty(it, 'false'))).equalsIgnoreCase('true')
}) return

def loader = new GroovyClassLoader(this.class.classLoader)
def directory = new File(summaryRoot.toString(), 'build-tools/test-summary')
loader.parseClass(new File(directory, 'TestReportTotals.groovy'))
loader.parseClass(new File(directory, 'TestReportTotalsTest.groovy'))
def previous = Thread.currentThread().contextClassLoader
try {
    Thread.currentThread().contextClassLoader = loader
    def result = ConsoleLauncher.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true),
        'execute', '--select-class', 'com.example.build.TestReportTotalsTest', '--disable-banner', '--details=summary')
    if (result.exitCode != 0) throw new IllegalStateException('Test report parser checks failed')
} finally {
    Thread.currentThread().contextClassLoader = previous
}
