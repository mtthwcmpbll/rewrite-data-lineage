package com.snowfort.recipe.lineage.fixture;

/**
 * Minimal two-service Spring fixture used as the canonical end-to-end example for the
 * data-lineage pipeline. Each service is ~30 lines and demonstrates the cross-service
 * shape the analysis needs to recover: Service A exposes {@code POST /orders} and calls
 * {@code GET /customers/{id}} on Service B; Service B exposes {@code GET /customers/{id}}.
 */
public final class SampleServices {

    private SampleServices() {
    }

    public static final String SERVICE_A_PATH =
            "service-a/src/main/java/com/example/servicea/OrderController.java";

    public static final String SERVICE_A_SRC = """
            package com.example.servicea;

            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.client.RestTemplate;

            @RestController
            public class OrderController {

                private final RestTemplate restTemplate = new RestTemplate();

                @PostMapping("/orders")
                public Order create(@RequestBody Order order) {
                    Customer customer = restTemplate.getForObject(
                            "http://service-b/customers/{id}", Customer.class, order.customerId());
                    return order;
                }

                public static class Order {
                    private String customerId;
                    private double total;
                    public String customerId() { return customerId; }
                    public double total() { return total; }
                }

                public static class Customer {
                    private String id;
                    private String name;
                    public String id() { return id; }
                    public String name() { return name; }
                }
            }
            """;

    public static final String SERVICE_B_PATH =
            "service-b/src/main/java/com/example/serviceb/CustomerController.java";

    public static final String SERVICE_B_SRC = """
            package com.example.serviceb;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class CustomerController {

                @GetMapping("/customers/{id}")
                public Customer get(@PathVariable String id) {
                    return new Customer(id, "Alice");
                }

                public static class Customer {
                    private final String id;
                    private final String name;
                    public Customer(String id, String name) {
                        this.id = id;
                        this.name = name;
                    }
                    public String id() { return id; }
                    public String name() { return name; }
                }
            }
            """;
}
