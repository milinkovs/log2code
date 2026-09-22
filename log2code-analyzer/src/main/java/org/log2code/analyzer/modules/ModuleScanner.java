package org.log2code.analyzer.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.log2code.analyzer.CliUserException;
import org.log2code.core.model.ModuleInfo;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Discovers Maven modules of a multi-module project (0.11 step 5): reads the root {@code pom.xml} and
 * recurses into {@code <modules>}, keeping only modules that have {@code src/main/java}, filtered by
 * {@code include-modules}/{@code exclude-modules}. Never descends into {@code src/test}.
 */
public final class ModuleScanner {

    private static final String SRC_MAIN_JAVA = "src/main/java";
    private static final String SRC_MAIN_RESOURCES = "src/main/resources";

    /** Modules with {@code src/main/java}, after include/exclude filtering, in declaration order. */
    public List<ModuleInfo> scan(Path projectRoot, List<String> includeModules, List<String> excludeModules) {
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new CliUserException("no pom.xml found at project.path: " + projectRoot.toAbsolutePath());
        }
        List<Path> moduleDirs = new ArrayList<>();
        collectModuleDirs(projectRoot, moduleDirs);

        List<ModuleInfo> result = new ArrayList<>();
        for (Path moduleDir : moduleDirs) {
            Path srcMainJava = moduleDir.resolve(SRC_MAIN_JAVA);
            if (!Files.isDirectory(srcMainJava)) {
                continue; // "prazno = svi Maven moduli koji imaju src/main/java" (0.11)
            }
            String artifactId = readOwnArtifactId(moduleDir.resolve("pom.xml"));
            if (!isIncluded(artifactId, includeModules, excludeModules)) {
                continue;
            }
            String service = readSpringApplicationName(moduleDir.resolve(SRC_MAIN_RESOURCES));
            result.add(new ModuleInfo(artifactId, service, List.of(SRC_MAIN_JAVA), List.of(), List.of()));
        }
        return result;
    }

    /** Number of {@code .java} files under {@code src/main/java} of one module (for {@code --dry-run} output). */
    public static long countJavaFiles(Path moduleDir) {
        Path srcMainJava = moduleDir.resolve(SRC_MAIN_JAVA);
        if (!Files.isDirectory(srcMainJava)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(srcMainJava)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java")).count();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to count .java files under " + srcMainJava, e);
        }
    }

    private void collectModuleDirs(Path dir, List<Path> out) {
        Path pomFile = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pomFile)) {
            return;
        }
        for (String moduleName : declaredModules(pomFile)) {
            Path moduleDir = dir.resolve(moduleName);
            out.add(moduleDir);
            collectModuleDirs(moduleDir, out);
        }
    }

    private static boolean isIncluded(String artifactId, List<String> includeModules, List<String> excludeModules) {
        if (excludeModules.contains(artifactId)) {
            return false;
        }
        return includeModules.isEmpty() || includeModules.contains(artifactId);
    }

    private static List<String> declaredModules(Path pomFile) {
        Element project = parseXml(pomFile);
        for (Node child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && "modules".equals(child.getNodeName())) {
                List<String> modules = new ArrayList<>();
                NodeList moduleNodes = child.getChildNodes();
                for (int i = 0; i < moduleNodes.getLength(); i++) {
                    Node m = moduleNodes.item(i);
                    if (m.getNodeType() == Node.ELEMENT_NODE && "module".equals(m.getNodeName())) {
                        modules.add(m.getTextContent().trim());
                    }
                }
                return modules;
            }
        }
        return List.of();
    }

    private static String readOwnArtifactId(Path pomFile) {
        if (!Files.isRegularFile(pomFile)) {
            throw new CliUserException("declared module has no pom.xml: " + pomFile.toAbsolutePath());
        }
        Element project = parseXml(pomFile);
        // The module's own <artifactId> is a direct child of <project>; <parent><artifactId> is nested
        // one level deeper, so this never picks up the parent's artifactId.
        for (Node child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && "artifactId".equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        throw new CliUserException("no <artifactId> found in " + pomFile.toAbsolutePath());
    }

    private static Element parseXml(Path xmlFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(xmlFile.toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new CliUserException("failed to parse " + xmlFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String readSpringApplicationName(Path resourcesDir) {
        Path yml = resourcesDir.resolve("application.yml");
        if (Files.isRegularFile(yml)) {
            return readFromYaml(yml);
        }
        Path yaml = resourcesDir.resolve("application.yaml");
        if (Files.isRegularFile(yaml)) {
            return readFromYaml(yaml);
        }
        Path properties = resourcesDir.resolve("application.properties");
        if (Files.isRegularFile(properties)) {
            return readFromProperties(properties);
        }
        return null;
    }

    private static String readFromYaml(Path yamlFile) {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            // YAMLMapper#readTree reads only the first "---"-separated document, which is exactly
            // what 0.11 asks for.
            JsonNode root = new YAMLMapper().readTree(in);
            JsonNode name = root.path("spring").path("application").path("name");
            return name.isMissingNode() || name.isNull() ? null : name.asText();
        } catch (IOException e) {
            throw new CliUserException("failed to read " + yamlFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String readFromProperties(Path propertiesFile) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            properties.load(in);
        } catch (IOException e) {
            throw new CliUserException("failed to read " + propertiesFile.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return properties.getProperty("spring.application.name");
    }
}
