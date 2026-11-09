import java.lang.reflect.Proxy

// Один обработчик на запрос Maven, в том числе при параллельной сборке модулей.
synchronized (session.request) {
    def previous = session.request.executionListener
    if (previous != null && Proxy.isProxyClass(previous.class) &&
            Proxy.getInvocationHandler(previous).class.name == 'com.example.build.TestSummaryHook') {
        return
    }
    def loader = new GroovyClassLoader(this.class.classLoader)
    def directory = new File(summaryRoot.toString(), 'build-tools/test-summary')
    loader.parseClass(new File(directory, 'TestReportTotals.groovy'))
    def hook = loader.parseClass(new File(directory, 'TestSummaryHook.groovy'))
    hook.install(session, log, new File(summaryRoot.toString()).toPath())
}
