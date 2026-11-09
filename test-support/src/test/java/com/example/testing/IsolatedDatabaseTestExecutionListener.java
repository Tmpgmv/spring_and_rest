package com.example.testing;

import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/** Восстанавливает данные до открытия тестовой транзакции и закрывает контекст после класса. */
public final class IsolatedDatabaseTestExecutionListener extends AbstractTestExecutionListener {
  @Override
  public int getOrder() {
    return 3500;
  }

  @Override
  public void beforeTestMethod(TestContext context) throws Exception {
    source(context).beforeMethod();
  }

  @Override
  public void afterTestMethod(TestContext context) {
    source(context).afterMethod();
  }

  @Override
  public void afterTestClass(TestContext context) {
    context.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
  }

  private GuardedTestDataSource source(TestContext context) {
    return context.getApplicationContext().getBean("dataSource", GuardedTestDataSource.class);
  }
}
