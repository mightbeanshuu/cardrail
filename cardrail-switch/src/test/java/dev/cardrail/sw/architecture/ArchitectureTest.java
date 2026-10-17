package dev.cardrail.sw.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "dev.cardrail.sw", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = classes().that().resideInAPackage("dev.cardrail.sw.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("dev.cardrail.sw.domain..", "java..")
            .because("the domain must not know about Spring, the database or any adapter");

    @ArchTest
    static final ArchRule portsDependOnlyOnTheDomain = classes().that().resideInAPackage("dev.cardrail.sw.port..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("dev.cardrail.sw.port..", "dev.cardrail.sw.domain..", "java..");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses()
            .that().resideInAPackage("dev.cardrail.sw.application..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.cardrail.sw.adapter..", "dev.cardrail.sw.config..");

    @ArchTest
    static final ArchRule adaptersAreIndependentOfEachOther = slices()
            .matching("dev.cardrail.sw.adapter.(*)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule noStandardStreamsOrPrintStackTrace = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
