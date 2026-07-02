# Rewrite data lineage

This repository builds OpenRewrite/Moderne recipes that extract **HTTP data lineage** from Spring Boot
services — cataloging where external data enters and leaves a service, and tracing request payloads
from an inbound endpoint to an outbound call across method boundaries.

## `FindHttpDataLineage` recipe

`com.snowfort.recipe.lineage.FindHttpDataLineage` is a non-destructive `ScanningRecipe` that emits two
OpenRewrite data tables and never modifies source (constitution Principle IV):

| Data table | One row per | Key columns |
|------------|-------------|-------------|
| `HttpDataNodes` | inbound endpoint (SOURCE) or outbound call (SINK) | `direction`, `framework`, `httpMethod`, `routeTemplate`, `routeResolution`, `targetAuthority`, `payloadType`, location |
| `DataFlowChains` | edge on a source&rarr;sink path | `sourceNodeId`, `sinkNodeId`, `edgeIndex`, `fromMethodFqn`, `toMethodFqn`, `taintedArgPositions` |

What it detects:

- **Inbound (SOURCE)** — Spring MVC controller handlers (`@Controller`/`@RestController` +
  `@RequestMapping`/`@GetMapping`/…, including meta-annotations); `@RequestBody`/`@RequestParam`/
  `@PathVariable`/`@RequestHeader` parameters seed the taint analysis.
- **Outbound (SINK)** — `RestTemplate` HTTP methods, the `WebClient` fluent chain
  (`…uri(…)…bodyValue(…)…retrieve()`), and Spring Cloud OpenFeign `@FeignClient` interface methods
  (the outbound contract is declared on the interface method; its `@FeignClient` `name` becomes the
  target service authority).
- **Chains** — request data traced inbound&rarr;outbound across methods within the repository, emitted
  as an ordered per-hop edge sequence, for RestTemplate/WebClient call expressions and Feign call sites
  alike (a Feign chain terminates at the `@FeignClient` declaration node). A sink fed only by
  local/constant data yields **no** chain.

Cross-repo-joinable identifiers: inbound and outbound routes are normalized identically to a path-only
template (`/orders/{id}`) with the outbound scheme+host kept separately in `targetAuthority`, so a
future cross-repo phase can join `SINK`→`SOURCE` by `(httpMethod, routeTemplate)`. Dynamic URLs are
marked `PARTIAL`/`UNKNOWN` rather than guessed.

This implements design Phases 1–3 + 5 from [`docs/DESIGN.md`](docs/DESIGN.md): the schema
(`DataFlowNode`/`CallChainEdge`/`ExternalIdentifier`), the HTTP source/sink catalog, intra- and
inter-procedural taint into a raw call chain, and join-ready external identifiers. Transform
classification, library summaries, and the cross-repo join itself are later phases. The feature spec
and task breakdown live under [`specs/001-spring-http-lineage/`](specs/001-spring-http-lineage/).

---

The sections below are the OpenRewrite recipe-module starter documentation (build, test, publish).

To begin, fork this repository and customize it by:

1. Changing the root project name in `settings.gradle.kts`.
2. Changing the `group` in `build.gradle.kts`.
3. Changing the package structure from `com.snowfort.recipe` to whatever you want.

## Getting started

Familiarize yourself with the [OpenRewrite documentation](https://docs.openrewrite.org/), in particular the [concepts & explanations](https://docs.openrewrite.org/concepts-explanations) op topics like the [lossless semantic trees](https://docs.openrewrite.org/concepts-and-explanations/lossless-semantic-trees), [recipes](https://docs.openrewrite.org/concepts-and-explanations/recipes), [traits](https://docs.openrewrite.org/concepts-and-explanations/traits) and [visitors](https://docs.openrewrite.org/concepts-and-explanations/visitors).

You might be interested to watch some of the [videos available on OpenRewrite and Moderne](https://www.youtube.com/@moderne-and-openrewrite).

Once you want to dive into the code there is a [comprehensive getting started guide](https://docs.openrewrite.org/authoring-recipes/recipe-development-environment)
available in the OpenRewrite docs that provides more details than the below README.

## Local Publishing for Testing

Before you publish your recipe module to an artifact repository, you may want to try it out locally.
To do this on the command line, using `gradle`, run:

```bash
./gradlew publishToMavenLocal
# or ./gradlew pTML
# or mvn install
```

To publish using maven, run:

```bash
./mvnw install
```

This will publish to your local maven repository, typically under `~/.m2/repository`.

Replace the groupId, artifactId, recipe name, and version in the below snippets with the ones that correspond to your recipe.

In the pom.xml of a different project you wish to test your recipe out in, make your recipe module a plugin dependency of rewrite-maven-plugin:

```xml
<project>
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>RELEASE</version>
                <configuration>
                    <activeRecipes>
                        <recipe>com.snowfort.recipe.NoGuavaListsNewArrayList</recipe>
                    </activeRecipes>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>com.snowfort.recipe</groupId>
                        <artifactId>rewrite-data-lineage</artifactId>
                        <version>0.1.0-SNAPSHOT</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </build>
</project>
```

Unlike Maven, Gradle must be explicitly configured to resolve dependencies from Maven local.
The root project of your Gradle build, make your recipe module a dependency of the `rewrite` configuration:

```groovy
plugins {
    id("java")
    id("org.openrewrite.rewrite") version("latest.release")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    rewrite("com.snowfort.recipe:rewrite-data-lineage:latest.integration")
}

rewrite {
    activeRecipe("com.snowfort.recipe.NoGuavaListsNewArrayList")
}
```

Now you can run `mvn rewrite:run` or `gradlew rewriteRun` to run your recipe.

## Publishing to Artifact Repositories

This project is configured to publish to Moderne's open artifact repository (via the `publishing` task at the bottom of
the `build.gradle.kts` file). If you want to publish elsewhere, you'll want to update that task.
[app.moderne.io](https://app.moderne.io) can draw recipes from the provided repository, as well as from [Maven Central](https://search.maven.org).

Note:
Running the publish task _will not_ update [app.moderne.io](https://app.moderne.io), as only Moderne employees can
add new recipes. If you want to add your recipe to [app.moderne.io](https://app.moderne.io), please ask the
team in [Slack](https://join.slack.com/t/rewriteoss/shared_invite/zt-nj42n3ea-b~62rIHzb3Vo0E1APKCXEA) or in [Discord](https://discord.gg/xk3ZKrhWAb).

These other docs might also be useful for you depending on where you want to publish the recipe:

* Sonatype's instructions for [publishing to Maven Central](https://maven.apache.org/repository/guide-central-repository-upload.html)
* Gradle's instructions on the [Gradle Publishing Plugin](https://docs.gradle.org/current/userguide/publishing\_maven.html).

### From Github Actions

The `.github` directory contains a Github action that will push a snapshot on every successful build.

Run the release action to publish a release version of a recipe.

### From the command line

To build a snapshot, run `./gradlew snapshot publish` to build a snapshot and publish it to Moderne's open artifact repository for inclusion at [app.moderne.io](https://app.moderne.io).

To build a release, run `./gradlew final publish` to tag a release and publish it to Moderne's open artifact repository for inclusion at [app.moderne.io](https://app.moderne.io).

## Applying OpenRewrite recipe development best practices

We maintain a collection of [best practices for writing OpenRewrite recipes](https://docs.openrewrite.org/recipes/java/recipes).
You can apply these recommendations to your recipes by running the following command:

```bash
./gradlew --init-script init.gradle rewriteRun -Drewrite.activeRecipe=org.openrewrite.recipes.rewrite.OpenRewriteRecipeBestPractices
```
or
```bash
./mvnw -U org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-rewrite:RELEASE -Drewrite.activeRecipes=org.openrewrite.recipes.rewrite.OpenRewriteRecipeBestPractices
```
