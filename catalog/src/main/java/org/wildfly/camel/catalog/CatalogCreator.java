/*
 * #%L
 * Wildfly Camel :: Testsuite
 * %%
 * Copyright (C) 2013 - 2014 RedHat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.wildfly.camel.catalog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class CatalogCreator {

    public static Path basedir() {
        String basedir = System.getProperty("basedir");
        return Paths.get(basedir != null ? basedir : ".");
    }
    
    static final Path resdir = basedir().resolve(Paths.get("src/main/resources"));
    static final Path srcdir = basedir().resolve(Paths.get("target/camel-catalog"));
    static final Path outdir = basedir().resolve(Paths.get("target/classes"));
    
    public static enum Kind {
        component, dataformat, language, other;
    }
    
    public static enum State {
        supported, planned, undecided, rejected
    }
    
    public static class Item {
        final Path path;
        final Kind kind;
        final String name;
        final String artifactId;
        final boolean deprecated;
        State state = State.undecided;
        Item(Path path, Kind kind, String artifactId, boolean deprecated) {
            this.path = path;
            this.kind = kind;
            this.artifactId = artifactId;
            this.deprecated = deprecated;
            String nspec = path.getFileName().toString();
            nspec = nspec.substring(0, nspec.indexOf("."));
            this.name = nspec;
        }
    }
    
    public static class RoadMap {
        final Kind kind;
        final Path outpath;
        final Map<String, Item> items = new HashMap<>();
        RoadMap(Kind kind) {
            this.outpath = resdir.resolve(kind + ".roadmap");
            this.kind = kind;
        }
        void add(Item item) {
            items.put(item.name, item);
        }
        public Kind getKind() {
            return kind;
        }
        public Path getOutpath() {
            return outpath;
        }
        public Item item(String name) {
            return items.get(name);
        }
        public List<String> sortedNames(State state) {
            List<String> result = new ArrayList<>();
            for (Item item : items.values()) {
                if (item.state == state) {
                    result.add(item.name);
                }
            }
            Collections.sort(result);
            return result;
        }
    }
    
    private Map<Kind, RoadMap> ROAD_MAPS = new LinkedHashMap<>();
    
    public CatalogCreator() {
        ROAD_MAPS.put(Kind.component, new RoadMap(Kind.component));
        ROAD_MAPS.put(Kind.dataformat, new RoadMap(Kind.dataformat));
        ROAD_MAPS.put(Kind.language, new RoadMap(Kind.language));
        ROAD_MAPS.put(Kind.other, new RoadMap(Kind.other));
    }

    public static void main(String[] args) throws Exception {
        new CatalogCreator().collect().generate();
    }
    
    public CatalogCreator collect() throws Exception {
        collectAvailable();
        collectSupported();
        return this;
    }

    public CatalogCreator generate() throws Exception {
        generateProperties();
        generateRoadmaps();
        return this;
    }

    public RoadMap getRoadmap(Kind kind) {
        return ROAD_MAPS.get(kind);
    }
    
    public List<RoadMap> getRoadmaps() {
        return new ArrayList<>(ROAD_MAPS.values());
    }
    
    private void collectAvailable() throws IOException {
        
        // Walk the available camel catalog items
        Files.walkFileTree(srcdir, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (path.toString().endsWith(".json")) {
                    Path relpath = srcdir.relativize(path);
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode treeNode = mapper.readTree(path.toFile());
                    JsonNode valnode = treeNode.findValue("kind");
                    String kind = valnode != null ? valnode.textValue() : null;
                    valnode = treeNode.findValue("artifactId");
                    String artifactId = valnode != null ? valnode.textValue() : null;
                    boolean deprecated = Boolean.parseBoolean(treeNode.findValue("deprecated").textValue());
                    if (validKind(kind, treeNode)) {
                        Item item = new Item(relpath, Kind.valueOf(kind), artifactId, deprecated);
                        ROAD_MAPS.get(item.kind).add(item);
                   }
                }
                return FileVisitResult.CONTINUE;
            }
            boolean validKind(String kind, JsonNode node) {
                JsonNode valnode = node.findValue("name");
                String name = valnode != null ? valnode.textValue() : null;
                try {
                    Kind.valueOf(kind);
                    return true;
                } catch (IllegalArgumentException e) {
                    if (name != null && name.contains("opentracing")) {
                        System.out.println(node);
                    }
                    return false;
                }
            }
        });
        
        // Change state when planned or rejected 
        for (RoadMap roadmap : ROAD_MAPS.values()) {
            State state = null;
            File file = roadmap.outpath.toFile();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                while (line != null) {
                    if (line.length() > 0 && !line.startsWith("#")) {
                        if (line.equals("[" + State.planned + "]")) {
                            state = State.planned;
                        } else if (line.equals("[" + State.rejected + "]")) {
                            state = State.rejected;
                        } else if (line.startsWith("[")) {
                            state = null;
                        } else if (state != null) {
                            String name = line.trim().split(" ")[0];
                            Item item = roadmap.item(name);
                            if (item != null) {
                                item.state = state;
                            }
                        }
                    }
                    line = br.readLine();
                }
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private void collectSupported() throws IOException {
        Path rootPath = basedir().resolve(Paths.get("target", "dependency"));
        for (RoadMap roadmap : ROAD_MAPS.values()) {
            for (Item item : roadmap.items.values()) {
                if (rootPath.resolve(item.artifactId + ".jar").toFile().isFile()) {
                    Path subpath = item.path.subpath(2, item.path.getNameCount());
                    Path targetPath = Paths.get("org", "wildfly").resolve(subpath);
                    Path target = outdir.resolve(targetPath);
                    Path source = srcdir.resolve(item.path);
                    target.getParent().toFile().mkdirs();
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    item.state = State.supported;
                }
            }
        }
    }

    private void generateProperties() throws IOException {
        for (RoadMap roadmap : ROAD_MAPS.values()) {
            File outfile = outdir.resolve("org/wildfly/camel/catalog/" + roadmap.kind + "s.properties").toFile();
            try (PrintWriter pw = new PrintWriter(outfile)) {
                for (String name : roadmap.sortedNames(State.supported)) {
                    pw.println(name);
                }
            }
        }
    }

    private void generateRoadmaps() throws IOException {
        for (RoadMap roadmap : ROAD_MAPS.values()) {
            try (PrintWriter pw = new PrintWriter(roadmap.outpath.toFile())) {
                for (State state : State.values()) {
                    pw.println("[" + state + "]");
                    for (String entry : roadmap.sortedNames(state)) {
                        Item item = roadmap.item(entry);
                        pw.println(item.name + (item.deprecated ? " (deprecated)" : ""));
                    }
                    pw.println();
                }
            }
        }
    }
}
