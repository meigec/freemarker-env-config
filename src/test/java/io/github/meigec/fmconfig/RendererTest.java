package io.github.meigec.fmconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class RendererTest {

    private static final Path DATA = Path.of("data");
    private static final Path TEMPLATES = Path.of("templates");

    @TempDir
    Path out;

    private Renderer renderer;

    @BeforeEach
    void setUp() throws IOException {
        renderer = new Renderer(DATA, TEMPLATES);
    }

    private String read(String client, String env, String file) throws IOException {
        return Files.readString(out.resolve(client).resolve(env).resolve(file));
    }

    @Test
    void rendersEveryTemplateForEveryClientAndEnvironment() throws Exception {
        List<Path> files = renderer.renderAll(out);

        // 2 clients x 3 environments x 2 templates
        assertEquals(12, files.size());
        assertTrue(Files.exists(out.resolve("acme-bank/prod/application.properties")));
        assertTrue(Files.exists(out.resolve("globex-capital/dev/configmap.yaml")));
    }

    @Test
    void productionGetsThreeReplicasAndNoDebugEndpoints() throws Exception {
        renderer.renderAll(out);
        String prod = read("acme-bank", "prod", "application.properties");

        assertTrue(prod.contains("app.replicas=3"));
        assertTrue(prod.contains("app.debug.endpoints=false"));
        assertTrue(prod.contains("app.log.level=WARN"));
    }

    @Test
    void devUsesDebugLoggingAndStagingFallsBackToTheDefault() throws Exception {
        renderer.renderAll(out);

        assertTrue(read("acme-bank", "dev", "application.properties").contains("app.log.level=DEBUG"));
        // staging defines no log level: the template default applies
        assertTrue(read("acme-bank", "staging", "application.properties").contains("app.log.level=INFO"));
    }

    @Test
    void clientEnvironmentOverrideAppliesOnlyWhereDefined() throws Exception {
        renderer.renderAll(out);

        assertTrue(read("globex-capital", "prod", "application.properties").contains("feature.realtimeRisk=false"));
        assertTrue(read("globex-capital", "staging", "application.properties").contains("feature.realtimeRisk=true"));
        assertTrue(read("acme-bank", "prod", "application.properties").contains("feature.realtimeRisk=true"));
    }

    @Test
    void layersMergeInOrderDefaultsClientEnvironment() throws Exception {
        renderer.renderAll(out);

        // default 10, Globex sets 20, prod sets 50 for everyone
        assertTrue(read("acme-bank", "dev", "application.properties").contains("db.pool.size=10"));
        assertTrue(read("globex-capital", "dev", "application.properties").contains("db.pool.size=20"));
        assertTrue(read("globex-capital", "prod", "application.properties").contains("db.pool.size=50"));
    }

    @Test
    void secretsAreRenderedAsReferencesNeverAsValues() throws Exception {
        List<Path> files = renderer.renderAll(out);
        Pattern literalSecret = Pattern.compile("(?im)^\\s*[^\\s=:]*password[^\\s=:]*\\s*[=:]\\s*(?!\"?sm://)\\S+");

        for (Path file : files) {
            String content = Files.readString(file);
            assertTrue(content.contains("sm://"), "missing secret reference in " + file);
            assertTrue(!literalSecret.matcher(content).find(), "literal secret value in " + file);
        }
    }

    @Test
    void configMapIsValidKubernetesYaml() throws Exception {
        renderer.renderAll(out);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        try (Stream<Path> configMaps = Files.walk(out).filter(p -> p.endsWith("configmap.yaml"))) {
            for (Path file : configMaps.toList()) {
                Map<String, Object> doc = yaml.load(Files.readString(file));
                assertEquals("ConfigMap", doc.get("kind"), file.toString());
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
                String expectedNamespace = file.getParent().getParent().getFileName() + "-" + file.getParent().getFileName();
                assertEquals(expectedNamespace, metadata.get("namespace"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsFastWhenARequiredValueIsMissing() throws Exception {
        Map<String, Object> model = renderer.buildModel("acme-bank", "dev");
        ((Map<String, Object>) model.get("db")).remove("host");

        assertThrows(Exception.class, () -> renderer.render("application.properties.ftl", model));
    }
}
