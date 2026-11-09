package com.example.testing;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/** Автоматически включает изоляцию для Spring-тестов бизнес-сервисов, включая запуск из IDE. */
public final class IsolatedDatabaseContextCustomizerFactory implements ContextCustomizerFactory {
  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
    return new DatabaseCustomizer(testClass);
  }

  // Класс теста входит в ключ кеша: разные классы не получают общий контекст и общую БД.
  private record DatabaseCustomizer(Class<?> testClass) implements ContextCustomizer {
    @Override
    public void customizeContext(
        ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
      GuardedTestDataSource source = GuardedTestDataSource.create();
      var definition = new RootBeanDefinition(GuardedTestDataSource.class, () -> source);
      ((BeanDefinitionRegistry) context.getBeanFactory())
          .registerBeanDefinition("dataSource", definition);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "isolatedTestDatabase", Map.of("spring.jpa.hibernate.ddl-auto", "create-drop")));
      context.addBeanFactoryPostProcessor(
          factory -> {
            validateSources(factory, definition);
            rejectAlternativeJpaConnection(context);
            // Планировщик не должен изменять БД между восстановлением снимка и тестовым методом.
            String scheduled =
                "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
            if (factory instanceof BeanDefinitionRegistry registry
                && registry.containsBeanDefinition(scheduled)) {
              registry.removeBeanDefinition(scheduled);
            }
          });
    }
  }

  private static void validateSources(
      ConfigurableListableBeanFactory factory, RootBeanDefinition expected) {
    if (factory.getBeanDefinition("dataSource") != expected) {
      throw new IllegalStateException("Replacing the isolated test data source is prohibited");
    }
    for (String name : factory.getBeanNamesForType(DataSource.class, true, false)) {
      if (!name.equals("dataSource")) {
        throw new IllegalStateException("Additional test data sources are prohibited: " + name);
      }
    }
  }

  private static void rejectAlternativeJpaConnection(ConfigurableApplicationContext context) {
    for (String suffix :
        List.of(
            "hibernate.connection.url", "hibernate.connection.datasource",
            "hibernate.connection.provider_class", "hibernate.multi_tenant_connection_provider",
            "jakarta.persistence.jdbc.url", "javax.persistence.jdbc.url",
            "jakarta.persistence.jtaDataSource", "jakarta.persistence.nonJtaDataSource",
            "javax.persistence.jtaDataSource", "javax.persistence.nonJtaDataSource")) {
      if (context.getEnvironment().getProperty("spring.jpa.properties." + suffix) != null) {
        throw new IllegalStateException("Alternative JPA connections are prohibited in tests");
      }
    }
  }
}
