package com.example.build

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import static org.junit.jupiter.api.Assertions.*

class TestReportTotalsTest {
    @TempDir Path temporary

    private Path report(Path directory, String suite, Map counts = [tests: 4, failures: 1, errors: 1, skipped: 1]) {
        Files.createDirectories(directory)
        def cases = []
        counts.tests.times { index ->
            String outcome = index < counts.failures ? '<failure/>' :
                index < counts.failures + counts.errors ? '<error/>' :
                index < counts.failures + counts.errors + counts.skipped ? '<skipped/>' : ''
            cases.add("<testcase classname='${suite}' name='case[${index}]'>${outcome}</testcase>")
        }
        Path file = directory.resolve("TEST-${suite}.xml")
        Files.writeString(file, "<testsuite name='${suite}' tests='${counts.tests}' failures='${counts.failures}' errors='${counts.errors}' skipped='${counts.skipped}'>${cases.join()}</testsuite>")
        file
    }

    /*
     * Исходные данные: четыре каталога выполнения содержат одинаково названный класс с четырьмя случаями.
     * Ожидаемый результат: каждый модуль учитывается отдельно; итог равен 16/4/4/4.
     */
    @Test
    @DisplayName('Итог суммирует счётчики модулей, включая параметризованные и пропущенные случаи')
    void sumsCurrentExecutions() {
        def total = [tests: 0L, failures: 0L, errors: 0L, skipped: 0L]
        4.times { index ->
            Path directory = temporary.resolve("module-${index}")
            report(directory, 'SharedTest')
            TestReportTotals.add(total, TestReportTotals.readExecution(directory))
        }
        assertEquals([tests: 16L, failures: 4L, errors: 4L, skipped: 4L], total)
        assertEquals('Total tests: 16 | Failures: 4 | Errors: 4 | Skipped: 4', TestReportTotals.format(total))
    }

    /*
     * Исходные данные: одинаковый XML скопирован под другим именем в каталог того же выполнения.
     * Ожидаемый результат: набор случаев учитывается один раз.
     */
    @Test
    @DisplayName('Копия отчёта в одном выполнении не увеличивает число тестов')
    void ignoresReportCopies() {
        Path directory = temporary.resolve('current')
        Path original = report(directory, 'SharedTest')
        Files.copy(original, directory.resolve('TEST-copy.xml'))
        assertEquals(4L, TestReportTotals.readExecution(directory).tests)
    }

    /*
     * Исходные данные: отчёты одного класса в одном выполнении содержат несовместимые счётчики и случаи.
     * Ожидаемый результат: агрегация отклоняется с диагностикой конфликтующего дубликата.
     */
    @Test
    @DisplayName('Противоречивые дубликаты отчётов не дают произвольный итог')
    void rejectsConflictingCopies() {
        Path directory = temporary.resolve('current')
        report(directory, 'SharedTest')
        Files.writeString(directory.resolve('TEST-copy.xml'),
            "<testsuite name='SharedTest' tests='0' failures='0' errors='0' skipped='0'/>")
        def error = assertThrows(IllegalStateException) { TestReportTotals.readExecution(directory) }
        assertTrue(error.message.contains('Conflicting duplicate'))
    }

    /*
     * Исходные данные: параметр задаёт синтаксически повреждённый XML либо неверные счётчики/структуру.
     * Ожидаемый результат: каждый вариант отклоняется без публикации итоговых чисел.
     */
    @ParameterizedTest(name = 'Некорректный XML: {index}')
    @ValueSource(strings = [
        '<testsuite', '<testsuites/>',
        "<testsuite name='x' tests='-1' failures='0' errors='0' skipped='0'/>",
        "<testsuite name='x' tests='NaN' failures='0' errors='0' skipped='0'/>",
        "<testsuite name='x' tests='9223372036854775808' failures='0' errors='0' skipped='0'/>",
        "<testsuite name='x' tests='1' failures='0' errors='0' skipped='0'/>",
        "<testsuite name='x' tests='0' failures='0' errors='0'/>",
        "<testsuite name='x' tests='1' failures='1' errors='1' skipped='0'><testcase name='x'><failure/><error/></testcase></testsuite>"
    ])
    @DisplayName('Парсер отклоняет повреждённые документы и несогласованные счётчики')
    void rejectsInvalidReports(String xml) {
        Path path = temporary.resolve('TEST-invalid.xml')
        Files.writeString(path, xml)
        assertThrows(IllegalStateException) { TestReportTotals.readReport(path) }
    }

    /*
     * Исходные данные: требуемый каталог отсутствует либо существует без XML-отчётов.
     * Ожидаемый результат: оба случая завершаются понятной ошибкой отсутствующих отчётов.
     */
    @Test
    @DisplayName('Отсутствующий каталог или отчёт не интерпретируется как ноль тестов')
    void rejectsMissingReports() {
        Path missing = temporary.resolve('missing')
        assertThrows(IllegalStateException) { TestReportTotals.readExecution(missing) }
        Files.createDirectory(missing)
        assertThrows(IllegalStateException) { TestReportTotals.readExecution(missing) }
    }

    /*
     * Исходные данные: XML содержит объявление внешней сущности с адресом контролируемого локального файла.
     * Ожидаемый результат: DOCTYPE отклоняется до обработки внешней сущности.
     */
    @Test
    @DisplayName('XML-отчёт не может загружать внешние сущности')
    void rejectsExternalEntities() {
        Path file = temporary.resolve('TEST-external.xml')
        Files.writeString(file, "<!DOCTYPE testsuite [<!ENTITY external SYSTEM 'file:///unavailable-summary-fixture'>]><testsuite name='x' tests='0' failures='0' errors='0' skipped='0'>&external;</testsuite>")
        assertThrows(IllegalStateException) { TestReportTotals.readReport(file) }
    }

    /*
     * Исходные данные: старый каталог содержит другой набор случаев, но передан только текущий каталог.
     * Ожидаемый результат: предыдущий запуск и вложенные копии отчётов не учитываются.
     */
    @Test
    @DisplayName('Парсер не сканирует предыдущие запуски и вложенные копии')
    void ignoresOtherDirectories() {
        report(temporary.resolve('old'), 'OldTest')
        Path current = temporary.resolve('current')
        report(current, 'CurrentTest')
        report(current.resolve('copies'), 'CopiedTest')
        assertEquals(4L, TestReportTotals.readExecution(current).tests)
    }

    /*
     * Исходные данные: текущий каталог содержит символьную ссылку на отчёт другого расположения.
     * Ожидаемый результат: ссылка отклоняется вместо чтения неподтверждённого отчёта.
     */
    @Test
    @DisplayName('Символьная ссылка не позволяет подмешать сторонний отчёт')
    void rejectsSymbolicReports() {
        Path original = report(temporary.resolve('old'), 'OldTest')
        Path link = temporary.resolve('TEST-linked.xml')
        Files.createSymbolicLink(link, original)
        assertThrows(IllegalStateException) { TestReportTotals.readReport(link) }
    }

    /*
     * Исходные данные: у контролируемого XML-файла сняты все права чтения на файловой системе POSIX.
     * Ожидаемый результат: парсер сообщает ошибку вместо публикации произвольных счётчиков.
     */
    @Test
    @DisplayName('Нечитаемый отчёт завершает проверку явной ошибкой')
    void rejectsUnreadableReports() {
        Path file = report(temporary.resolve('unreadable'), 'UnreadableTest')
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(file).supportsFileAttributeView('posix'))
        def permissions = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, [] as Set)
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file))
            assertThrows(IllegalStateException) { TestReportTotals.readReport(file) }
        } finally {
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    /*
     * Исходные данные: к максимальному значению счётчика long добавляется ещё один случай.
     * Ожидаемый результат: переполнение отклоняется, а не превращается в отрицательный итог.
     */
    @Test
    @DisplayName('Переполнение итогового счётчика завершается ошибкой')
    void rejectsOverflow() {
        def totals = [tests: Long.MAX_VALUE, failures: 0L, errors: 0L, skipped: 0L]
        assertThrows(ArithmeticException) {
            TestReportTotals.add(totals, [tests: 1L, failures: 0L, errors: 0L, skipped: 0L])
        }
    }
}
