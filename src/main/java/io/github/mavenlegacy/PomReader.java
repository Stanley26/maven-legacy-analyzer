package io.github.mavenlegacy;

import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;
import javax.xml.parsers.SAXParserFactory;
import java.nio.file.*;
import java.util.*;
import static io.github.mavenlegacy.Model.*;

/** Reads declarations and Maven verbose origin comments; never resolves Maven precedence itself. */
public final class PomReader {
    private static final class Node {
        final String name;
        final int line;
        final List<Node> children = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        String origin = "";
        Node(String name, int line) { this.name = name; this.line = line; }
        Node child(String name) { return children.stream().filter(n -> n.name.equals(name)).findFirst().orElse(null); }
        String value(String name) { var node = child(name); return node == null ? "" : node.text.toString().trim(); }
        List<Node> all(String name) { return children.stream().filter(n -> n.name.equals(name)).toList(); }
    }

    public Pom read(Path path) throws Exception {
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var reader = factory.newSAXParser().getXMLReader();
        var stack = new ArrayDeque<Node>();
        var document = new Node("document", 0);
        stack.push(document);
        var handler = new DefaultHandler2() {
            Locator locator;
            Node lastClosed;
            public void setDocumentLocator(Locator value) { locator = value; }
            public void startElement(String uri, String local, String qName, Attributes attrs) {
                var node = new Node(local.isEmpty() ? qName : local, locator.getLineNumber());
                stack.peek().children.add(node);
                stack.push(node);
                lastClosed = null;
            }
            public void characters(char[] ch, int start, int length) { stack.peek().text.append(ch, start, length); }
            public void endElement(String uri, String local, String qName) { lastClosed = stack.pop(); }
            public void comment(char[] ch, int start, int length) {
                if (lastClosed != null) lastClosed.origin = new String(ch, start, length).trim();
            }
            public void fatalError(SAXParseException ex) throws SAXException { throw ex; }
            public void error(SAXParseException ex) throws SAXException { throw ex; }
        };
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        try (var input = Files.newInputStream(path)) { reader.parse(new InputSource(input)); }
        var project = document.child("project");
        if (project == null) throw new IllegalArgumentException("Expected a single <project> document");
        var parentNode = project.child("parent");
        Parent parent = parentNode == null ? null : new Parent(coordinate(parentNode),
                parentNode.child("relativePath") == null ? "../pom.xml" : parentNode.value("relativePath"));
        var properties = new ArrayList<Property>();
        var dependencies = new ArrayList<Declaration>();
        var managed = new ArrayList<Declaration>();
        var profiles = new ArrayList<String>();
        collect(project, "", properties, dependencies, managed);
        if (project.child("profiles") != null) for (var profile : project.child("profiles").all("profile")) {
            var id = profile.value("id");
            profiles.add(id);
            collect(profile, id, properties, dependencies, managed);
        }
        var modules = project.child("modules") == null ? List.<String>of()
                : project.child("modules").all("module").stream().map(n -> n.text.toString().trim()).toList();
        return new Pom(coordinate(project), fallback(project.value("packaging"), "jar"), parent,
                modules, properties, dependencies, managed, profiles);
    }

    private static void collect(Node node, String profile, List<Property> properties,
                                List<Declaration> dependencies, List<Declaration> managed) {
        var props = node.child("properties");
        if (props != null) for (var prop : props.children)
            properties.add(new Property(prop.name, prop.text.toString().trim(), profile, prop.line, prop.origin));
        declarations(node.child("dependencies"), profile, dependencies);
        var management = node.child("dependencyManagement");
        if (management != null) declarations(management.child("dependencies"), profile, managed);
    }
    private static void declarations(Node node, String profile, List<Declaration> target) {
        if (node == null) return;
        for (var dep : node.all("dependency")) {
            var exclusions = dep.child("exclusions") == null ? List.<String>of()
                    : dep.child("exclusions").all("exclusion").stream().map(e -> e.value("groupId") + ":" + e.value("artifactId")).toList();
            var version = dep.child("version");
            String origin = version == null ? "" : version.origin;
            var artifact = dep.child("artifactId");
            target.add(new Declaration(coordinate(dep), fallback(dep.value("type"), "jar"), dep.value("classifier"),
                    fallback(dep.value("scope"), "compile"), Boolean.parseBoolean(dep.value("optional")), exclusions, profile, dep.line, origin,
                    version == null ? 0 : version.line, artifact == null ? "" : artifact.origin));
        }
    }
    private static Coordinate coordinate(Node node) { return new Coordinate(node.value("groupId"), node.value("artifactId"), node.value("version")); }
    private static String fallback(String value, String other) { return value.isBlank() ? other : value; }
}
