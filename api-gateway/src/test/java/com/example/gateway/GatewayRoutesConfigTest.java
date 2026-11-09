package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

/**
 * Проверяет привязку spring.cloud.gateway.routes из application.yml к RouteDefinition: ровно три
 * явных маршрута, логические имена Eureka с lb:// без фиксированных адресов, предикаты Path по
 * группам маршрутов и StripPrefix=2 только у bookings.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
class GatewayRoutesConfigTest {

  @Autowired private RouteDefinitionLocator routeDefinitionLocator;

  /*
   * Исходные данные: Контекст шлюза загружает маршруты из application.yml при отключённом клиенте Eureka.
   * Ожидаемый результат: Присутствуют только hotels, rooms и bookings; первые два используют lb://hotel-
   * service, третий — lb://booking-service.
   */
  @Test
  @DisplayName("Конфигурация объявляет ровно три маршрута с логическими адресами сервисов")
  void declaresExactlyThreeExplicitLbRoutes() {
    Map<String, RouteDefinition> routes =
        routeDefinitionLocator.getRouteDefinitions().collectMap(RouteDefinition::getId).block();

    // Ровно три маршрута: автоматическая маршрутизация обнаруженных сервисов отключена.
    assertThat(routes).containsOnlyKeys("hotels", "rooms", "bookings");

    // Проверяем точные логические имена lb://; адреса http(s):// здесь недопустимы.
    assertThat(routes.get("hotels").getUri()).hasToString("lb://hotel-service");
    assertThat(routes.get("rooms").getUri()).hasToString("lb://hotel-service");
    assertThat(routes.get("bookings").getUri()).hasToString("lb://booking-service");
  }

  /*
   * Исходные данные: Из конфигурации шлюза загружены маршруты hotels, rooms и bookings.
   * Ожидаемый результат: Каждый маршрут имеет единственный предикат Path с шаблоном /api/hotels/**,
   * /api/rooms/** или /api/bookings/** соответственно.
   */
  @Test
  @DisplayName("Предикаты маршрутов соответствуют путям отелей, номеров и бронирований")
  void pathPredicatesMatchTheAd10RouteFamilies() {
    Map<String, RouteDefinition> routes = loadRoutes();

    assertThat(pathPatternOf(routes.get("hotels"))).isEqualTo("/api/hotels/**");
    assertThat(pathPatternOf(routes.get("rooms"))).isEqualTo("/api/rooms/**");
    assertThat(pathPatternOf(routes.get("bookings"))).isEqualTo("/api/bookings/**");
  }

  /*
   * Исходные данные: Загружены определения трёх маршрутов шлюза.
   * Ожидаемый результат: У bookings единственный фильтр StripPrefix со значением 2; у hotels и rooms фильтры
   * отсутствуют.
   */
  @Test
  @DisplayName("Только маршрут бронирований удаляет два сегмента префикса")
  void stripPrefixAppliesToBookingsOnly() {
    Map<String, RouteDefinition> routes = loadRoutes();

    assertThat(routes.get("bookings").getFilters())
        .singleElement()
        .satisfies(
            filter -> {
              assertThat(filter.getName()).isEqualTo("StripPrefix");
              assertThat(filter.getArgs()).containsValue("2");
            });

    // Для групп с передачей исходного пути фильтры не объявлены.
    assertThat(routes.get("hotels").getFilters()).isEmpty();
    assertThat(routes.get("rooms").getFilters()).isEmpty();
  }

  private Map<String, RouteDefinition> loadRoutes() {
    return routeDefinitionLocator.getRouteDefinitions().collectMap(RouteDefinition::getId).block();
  }

  private static String pathPatternOf(RouteDefinition route) {
    assertThat(route.getPredicates()).hasSize(1);
    PredicateDefinition predicate = route.getPredicates().get(0);
    assertThat(predicate.getName()).isEqualTo("Path");
    assertThat(predicate.getArgs()).hasSize(1);
    return predicate.getArgs().values().iterator().next();
  }
}
