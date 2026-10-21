package com.ridex.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The service boundaries of RideX, as rules that fail the build.
 *
 * Separate Maven modules already stop one service importing another at compile time, so why test it? Because the build can be
 * "fixed" by adding a dependency, and because the interesting rules are the ones a compiler does not check:
 * the shared library stays free of business logic, only the outbox publishes to Kafka, only the saga package consumes in the
 * core, and the ride code never knows it is talking over a network.
 */
class ServiceBoundaryTest {

    private static final String CORE = "com.ridex.ridex_app..";
    private static final String NOTIFICATION = "com.ridex.notification..";
    private static final String PAYMENT = "com.ridex.payment..";
    private static final String PRICING = "com.ridex.pricing..";
    private static final String ANALYTICS = "com.ridex.analytics..";
    private static final String PLATFORM = "com.ridex.platform..";

    private static JavaClasses core, notification, payment, pricing, analytics, platform;

    private static JavaClasses classesOf(String module) {
        Path target = Path.of("..", module, "target", "classes");
        assumeThat(Files.isDirectory(target)).as(module + " must be compiled before the architecture tests run").isTrue();
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPath(target);
    }

    @BeforeAll
    static void load() {
        core = classesOf("ridex-app");
        notification = classesOf("notification-service");
        payment = classesOf("payment-service");
        pricing = classesOf("pricing-service");
        analytics = classesOf("analytics-service");
        platform = classesOf("platform");
        // A rule that checks zero classes passes for ever; make sure every module was really read
        for (JavaClasses imported : List.of(core, notification, payment, pricing, analytics, platform)) {
            org.assertj.core.api.Assertions.assertThat(imported.size()).isGreaterThan(5);
        }
    }

    // ------------------------------------------------------------------ services are independent

    private static void independent(JavaClasses service, String own, String... others) {
        noClasses().that().resideInAPackage(own).should().dependOnClassesThat().resideInAnyPackage(others)
                .because("a service may share contracts and infrastructure (the platform library), never another service's code")
                .check(service);
    }

    @Test
    void theCoreKnowsNoOtherService() {
        independent(core, CORE, NOTIFICATION, PAYMENT, PRICING, ANALYTICS);
    }

    @Test
    void theNotificationServiceKnowsNoOtherService() {
        independent(notification, NOTIFICATION, CORE, PAYMENT, PRICING, ANALYTICS);
    }

    @Test
    void thePaymentServiceKnowsNoOtherService() {
        independent(payment, PAYMENT, CORE, NOTIFICATION, PRICING, ANALYTICS);
    }

    @Test
    void thePricingServiceKnowsNoOtherService() {
        independent(pricing, PRICING, CORE, NOTIFICATION, PAYMENT, ANALYTICS);
    }

    @Test
    void theAnalyticsServiceKnowsNoOtherService() {
        independent(analytics, ANALYTICS, CORE, NOTIFICATION, PAYMENT, PRICING);
    }

    // ------------------------------------------------------------------ the shared library stays infrastructure

    @Test
    void thePlatformLibraryContainsNoBusinessLogic() {
        noClasses().that().resideInAPackage(PLATFORM)
                .should().dependOnClassesThat().resideInAnyPackage(CORE, NOTIFICATION, PAYMENT, PRICING, ANALYTICS)
                .because("a shared library that knows about rides or payments turns separate services into a distributed monolith")
                .check(platform);
    }

    @Test
    void eventContractsArePlainDataWithNoFrameworkDependencies() {
        classes().that().resideInAPackage("com.ridex.platform.contracts..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "tools.jackson..", "com.ridex.platform.contracts..")
                .because("contracts are the published language between services: data only, no Spring, no database, no behaviour")
                .check(platform);
    }

    // ------------------------------------------------------------------ how events leave and enter the core

    @Test
    void theCorePublishesEventsOnlyThroughTheOutbox() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .because("sending to Kafka inside a business transaction can lose or invent events; the outbox does it safely")
                .check(core);
    }

    @Test
    void kafkaConsumersInTheCoreLiveOnlyInTheSagaPackage() {
        methods().that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                .should().beDeclaredInClassesThat().resideInAPackage("com.ridex.ridex_app.saga..")
                .because("the core reacts to other services in one visible place")
                .check(core);
    }

    @Test
    void everyServiceConsumerGoesThroughTheIdempotencyHelper() {
        for (JavaClasses service : List.of(notification, payment, analytics)) {
            classes().that().containAnyMethodsThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                            "are Kafka listeners", m -> m.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")))
                    .and().haveSimpleNameNotEndingWith("DeadLetterConsumer")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.ridex.platform.kafka.ProcessedEvents")
                    .because("Kafka delivers at least once: a consumer that is not idempotent will one day do its work twice")
                    .allowEmptyShould(true)
                    .check(service);
        }
        classes().that().containAnyMethodsThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "are Kafka listeners", m -> m.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")))
                .should().dependOnClassesThat().haveFullyQualifiedName("com.ridex.platform.kafka.ProcessedEvents")
                .allowEmptyShould(true)
                .check(core);
    }

    // ------------------------------------------------------------------ the ride code does not know about the network

    @Test
    void theRideCodeDependsOnThePricingInterfaceNeverOnHttp() {
        noClasses().that().resideInAPackage("com.ridex.ridex_app.ride..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..", "java.net.http..", "io.github.resilience4j..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.ridex.ridex_app.pricing.HttpPricingClient")
                .because("how pricing is reached (HTTP today, anything tomorrow) is not the ride code's business")
                .check(core);
    }

    @Test
    void controllersNeverTouchRepositoriesDirectly() {
        noClasses().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().dependOnClassesThat().areAssignableTo("org.springframework.data.repository.Repository")
                .because("controllers translate HTTP; rules and transactions belong in services")
                .allowEmptyShould(true)
                .check(core);
    }

    // ------------------------------------------------------------------ resilience is applied where the network is crossed

    @Test
    void remoteCallsAreProtectedByResilience4j() {
        // every class that makes an HTTP call to another service must also use a circuit breaker
        for (JavaClasses service : List.of(core, payment)) {
            classes().that(com.tngtech.archunit.base.DescribedPredicate.describe("call another service with RestClient",
                            (com.tngtech.archunit.core.domain.JavaClass c) -> c.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(d -> d.getTargetClass().getFullName().equals("org.springframework.web.client.RestClient"))))
                    .should().dependOnClassesThat().haveFullyQualifiedName("io.github.resilience4j.circuitbreaker.CircuitBreaker")
                    .because("a call over a network without a timeout and a circuit breaker is how one slow service takes down the rest")
                    .allowEmptyShould(true)
                    .check(service);
        }
    }
}
