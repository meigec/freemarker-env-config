package io.github.meigec.fmconfig;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/**
 * Renders every FreeMarker template for every client and environment.
 *
 * <p>Data layers are deep-merged in this order (later wins):
 * {@code defaults.yaml -> clients/<client>.yaml -> environments/<env>.yaml -> overrides/<client>-<env>.yaml}.
 */
public final class Renderer {

    private final Path dataDir;
    private final Path templatesDir;
    private final Configuration freemarker;
    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    public Renderer(Path dataDir, Path templatesDir) throws IOException {
        this.dataDir = dataDir;
        this.templatesDir = templatesDir;
        this.freemarker = new Configuration(Configuration.VERSION_2_3_33);
        freemarker.setDirectoryForTemplateLoading(templatesDir.toFile());
        freemarker.setDefaultEncoding("UTF-8");
        // Fail fast: a missing value breaks the build instead of producing a broken config.
        freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        freemarker.setLogTemplateExceptions(false);
        freemarker.setWrapUncheckedExceptions(true);
        freemarker.setFallbackOnNullLoopVariable(false);
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args.length > 0 ? args[0] : "data");
        Path templates = Path.of(args.length > 1 ? args[1] : "templates");
        Path out = Path.of(args.length > 2 ? args[2] : "out");

        List<Path> files = new Renderer(data, templates).renderAll(out);
        files.forEach(f -> System.out.println("rendered " + f));
        System.out.println(files.size() + " files rendered into " + out);
    }

    /** Renders all templates for all client/environment combinations into {@code outDir/<client>/<env>/}. */
    public List<Path> renderAll(Path outDir) throws IOException, TemplateException {
        List<Path> rendered = new ArrayList<>();
        for (String client : names(dataDir.resolve("clients"))) {
            for (String env : names(dataDir.resolve("environments"))) {
                Map<String, Object> model = buildModel(client, env);
                Path target = outDir.resolve(client).resolve(env);
                Files.createDirectories(target);
                for (String template : templateNames()) {
                    Path file = target.resolve(template.substring(0, template.length() - ".ftl".length()));
                    Files.writeString(file, render(template, model));
                    rendered.add(file);
                }
            }
        }
        return rendered;
    }

    /** Builds the data model for one client in one environment. */
    public Map<String, Object> buildModel(String client, String env) throws IOException {
        Map<String, Object> model = load(dataDir.resolve("defaults.yaml"));
        deepMerge(model, load(dataDir.resolve("clients").resolve(client + ".yaml")));
        deepMerge(model, load(dataDir.resolve("environments").resolve(env + ".yaml")));
        Path override = dataDir.resolve("overrides").resolve(client + "-" + env + ".yaml");
        if (Files.exists(override)) {
            deepMerge(model, load(override));
        }
        return model;
    }

    public String render(String templateName, Map<String, Object> model) throws IOException, TemplateException {
        Template template = freemarker.getTemplate(templateName);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    private List<String> templateNames() throws IOException {
        try (Stream<Path> files = Files.list(templatesDir)) {
            return files.filter(p -> p.toString().endsWith(".ftl"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml"))
                    .map(n -> n.substring(0, n.length() - ".yaml".length()))
                    .sorted()
                    .toList();
        }
    }

    private Map<String, Object> load(Path file) throws IOException {
        Map<String, Object> loaded = yaml.load(Files.readString(file));
        return loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
    }

    /** Recursive merge: nested maps are merged, every other value (including lists) is replaced. */
    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        overlay.forEach((key, value) -> {
            Object existing = base.get(key);
            if (existing instanceof Map && value instanceof Map) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existing);
                deepMerge(merged, (Map<String, Object>) value);
                base.put(key, merged);
            } else {
                base.put(key, value);
            }
        });
    }
}
