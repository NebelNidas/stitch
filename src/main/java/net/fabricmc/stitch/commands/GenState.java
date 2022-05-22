/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.commands;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.MatcherUtil;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

class GenState {
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<AbstractJarEntry, Integer> values = new IdentityHashMap<>();
    // The following GenMaps are generated from the mapping file supplied via
    //   the `<old-mapping-file>` parameter from the `updateIntermediary` command 
    private GenMap suppliedOldMappings, matchesBetweenNewAndOldOfficialNames;
    // The following GenMap is generated from the mappings potentially already
    //   present in the supplied target file to write to
    private GenMap outputFileOldMappings;
    private boolean interactive = true;
    private boolean writeAll = false;
    private Scanner scanner = new Scanner(System.in);

    // Forced top-level package for intermediaries
    private String targetNamespace = "";

    public GenState() {
    }

    public void setWriteAll(boolean writeAll) {
        this.writeAll = writeAll;
    }

    public void disableInteractive() {
        interactive = false;
    }

    public String next(AbstractJarEntry entry, String name) {
        return name + "_" + values.computeIfAbsent(entry, (e) -> {
            int v = counters.getOrDefault(name, 1);
            counters.put(name, v + 1);
            return v;
        });
    }

    public void setTargetNamespace(final String namespace) {
        if (namespace.lastIndexOf("/") != (namespace.length() - 1))
            this.targetNamespace = namespace + "/";
        else
            this.targetNamespace = namespace;
    }

    public void setCounter(String key, int value) {
        counters.put(key, value);
    }

    public Map<String, Integer> getCounters() {
        return Collections.unmodifiableMap(counters);
    }

    public void generate(File file, JarRootEntry jarEntry, JarRootEntry jarOld) throws IOException {
        if (file.exists()) {
            System.err.println("Target file exists - loading...");
            outputFileOldMappings = new GenMap();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                outputFileOldMappings.load(
                        MappingsProvider.readTinyMappings(inputStream),
                        "official",
                        "intermediary"
                );
            }
        }

        try (FileWriter fileWriter = new FileWriter(file)) {
            try (BufferedWriter writer = new BufferedWriter(fileWriter)) {
                writer.write("v1\tofficial\tintermediary\n");

                for (JarClassEntry c : jarEntry.getClasses()) {
                    addClass(writer, c, jarOld, jarEntry, this.targetNamespace, false);
                }

                writeCounters(writer);
            }
        }
    }

    public static boolean isMappedField(ClassStorage storage, JarClassEntry c, JarFieldEntry f) {
        return isUnmappedFieldName(f.getName());
    }

    public static boolean isUnmappedFieldName(String name) {
        return name.length() <= 2 || (name.length() == 3 && name.charAt(2) == '_');
    }

    public static boolean isMappedMethod(ClassStorage storage, JarClassEntry c, JarMethodEntry m) {
        return isObfuscatedMethodName(m.getName(), c) && m.isSource(storage, c);
    }

    public static boolean isObfuscatedMethodName(String methodName, JarClassEntry parentClass) {
        if (
            methodName.charAt(0) != '<'
                && (
                    methodName.length() <= 2 
                        || (methodName.length() == 8 && parentClass.getName().equals("J/N"))
                )
        ) {
            return true;
        }
        return false;
    }

    public static boolean hasObfuscatedPackage(JarClassEntry classEntry) {
        // JarClassEntry.getFullyQualifiedName() always returns the full class path.
        // JarClassEntry.getName() sometimes returns just the actual (sub)class name,
        //   but sometimes the full path (except when having subclasses) as well

        String classPath = classEntry.getFullyQualifiedName();

        if (!classPath.contains("/")) {
            return true;
        }
        return false;
    }

    public static boolean isObfuscatedClassName(JarClassEntry classEntry) {
        String className = Arrays.asList(classEntry.getName().split("/")).stream().reduce((first, second) -> second).get();
        List<Character> classNameChars = new ArrayList<>(4);

        if (
            className.length() <= 4
                && className.matches("^[a-z]+$")
                // TODO: Obfuscated class names' chars are always alphabetically sorted
        ) {
            return true;
        }
        return false;
    }

    @Nullable
    private String getFieldName(ClassStorage storage, JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(storage, c, f)) {
            return null;
        }

        if (outputFileOldMappings != null) {
            EntryTriple findEntry = outputFileOldMappings.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                if (findEntry.getName().contains("field_")) {
                    return findEntry.getName();
                } else {
                    String newName = next(f, "field");
                    System.out.println(findEntry.getName() + " is now " + newName);
                    return newName;
                }
            }
        }

        if (matchesBetweenNewAndOldOfficialNames != null) {
            EntryTriple findEntry = matchesBetweenNewAndOldOfficialNames.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                findEntry = suppliedOldMappings.getField(findEntry);
                if (findEntry != null) {
                    if (findEntry.getName().contains("field_")) {
                        return findEntry.getName();
                    } else {
                        String newName = next(f, "field");
                        System.out.println(findEntry.getName() + " is now " + newName);
                        return newName;
                    }
                }
            }
        }

        return next(f, "field");
    }

    private final Map<JarMethodEntry, String> methodNames = new IdentityHashMap<>();

    private String getPropagation(ClassStorage storage, JarClassEntry classEntry) {
        if (classEntry == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(classEntry.getFullyQualifiedName());
        List<String> strings = new ArrayList<>();
        String scs = getPropagation(storage, classEntry.getSuperClass(storage));
        if (!scs.isEmpty()) {
            strings.add(scs);
        }

        for (JarClassEntry ce : classEntry.getInterfaces(storage)) {
            scs = getPropagation(storage, ce);
            if (!scs.isEmpty()) {
                strings.add(scs);
            }
        }

        if (!strings.isEmpty()) {
            builder.append("<-");
            if (strings.size() == 1) {
                builder.append(strings.get(0));
            } else {
                builder.append("[");
                builder.append(StitchUtil.join(",", strings));
                builder.append("]");
            }
        }

        return builder.toString();
    }

    private String getNamesListEntry(ClassStorage storage, JarClassEntry classEntry) {
        StringBuilder builder = new StringBuilder(getPropagation(storage, classEntry));
        if (classEntry.isInterface()) {
            builder.append("(itf)");
        }

        return builder.toString();
    }

    private Set<JarMethodEntry> findNames(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storageOld, storageNew, c, m, names, allEntries);
        return allEntries;
    }

    private void findNames(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
        if (!usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if ((m.getAccess() & Opcodes.ACC_BRIDGE) != 0) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> ccList = m.getMatchingEntries(storageNew, c);

        for (JarClassEntry cc : ccList) {
            EntryTriple findEntry = null;
            if (outputFileOldMappings != null) {
                findEntry = outputFileOldMappings.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                }
            }

            if (findEntry == null && matchesBetweenNewAndOldOfficialNames != null) {
                findEntry = matchesBetweenNewAndOldOfficialNames.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    EntryTriple newToOldEntry = findEntry;
                    findEntry = suppliedOldMappings.getMethod(newToOldEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                    } else {
                        // more involved...
                        JarClassEntry oldBase = storageOld.getClass(newToOldEntry.getOwner(), false);
                        if (oldBase != null) {
                            JarMethodEntry oldM = oldBase.getMethod(newToOldEntry.getName() + newToOldEntry.getDesc());
                            List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldBase);

                            for (JarClassEntry ccc : cccList) {
                                findEntry = suppliedOldMappings.getMethod(ccc.getFullyQualifiedName(), oldM.getName(), oldM.getDescriptor());
                                if (findEntry != null) {
                                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageOld, ccc) + suffix);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storageOld, storageNew, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods);
            }
        }
    }

    @Nullable
    private String getMethodName(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storageNew, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (matchesBetweenNewAndOldOfficialNames != null || outputFileOldMappings != null) {
            Map<String, Set<String>> names = new HashMap<>();
            Set<JarMethodEntry> allEntries = findNames(storageOld, storageNew, c, m, names);
            for (JarMethodEntry mm : allEntries) {
                if (methodNames.containsKey(mm)) {
                    return methodNames.get(mm);
                }
            }

            if (names.size() > 1) {
                System.out.println("Conflict detected - matched same target name!");
                List<String> nameList = new ArrayList<>(names.keySet());
                Collections.sort(nameList);

                for (int i = 0; i < nameList.size(); i++) {
                    String s = nameList.get(i);
                    System.out.println((i+1) + ") " + s + " <- " + StitchUtil.join(", ", names.get(s)));
                }

                if (!interactive) {
                    throw new RuntimeException("Conflict detected!");
                }

                while (true) {
                    String cmd = scanner.nextLine();
                    int i;
                    try {
                        i = Integer.parseInt(cmd);
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        continue;
                    }

                    if (i >= 1 && i <= nameList.size()) {
                        for (JarMethodEntry mm : allEntries) {
                            methodNames.put(mm, nameList.get(i - 1));
                        }
                        System.out.println("OK!");
                        return nameList.get(i - 1);
                    }
                }
            } else if (names.size() == 1) {
                String s = names.keySet().iterator().next();
                for (JarMethodEntry mm : allEntries) {
                    methodNames.put(mm, s);
                }
                if (s.contains("method_")) {
                    return s;
                } else {
                    String newName = next(m, "method");
                    System.out.println(s + " is now " + newName);
                    return newName;
                }
            }
        }

        return next(m, "method");
    }

    private void addClass(BufferedWriter writer, JarClassEntry c, ClassStorage storageOld, ClassStorage storage, String packagePrefix, boolean isSubclass) throws IOException {
        String className = null;

        // Only classes without a package should be looked at,
        //   all other ones are already mapped or have mappings
        //   openly available on the Internet
        if (c.getFullyQualifiedName().contains("/")) {
            return;
        }

        // Is it a subclass (even if not specified so)?
        if (!isSubclass && c.getFullyQualifiedName().contains("$")) {
            isSubclass = true;
        }


        // Set package and class names
        if (!hasObfuscatedPackage(c) && !isSubclass) {
            // Package name is not obfuscated, so we use that
            //   instead of the passed in one, but only if we're
            //   not looking at a subclass
            String classPath = c.getFullyQualifiedName();
            packagePrefix = classPath.substring(0, classPath.lastIndexOf("/") + 1);
        }
        if (c.isAnonymous()) {
            // Anonymous classes don't need intermediary names
            className = c.getName();
        }
        else if (!isObfuscatedClassName(c)) {
            // If the class name is not obfuscated, use it
            className = c.getName();
        }
        else {
            // Class name is obfuscated. We don't generate a new
            //   intermediary name instantly though, but check first
            //   if we can reuse an old one.
            // This can be combined with the rename check later though,
            //   so as an indicator that `className` still has to be set
            //   we set it to null.
            className = null;
        }

        // Has this class been mapped before on a previous file?
        String oldClassPath = null;

        if (matchesBetweenNewAndOldOfficialNames != null) {
            String foundClassPath = matchesBetweenNewAndOldOfficialNames.getClass(c.getFullyQualifiedName());
            if (foundClassPath != null) {
                oldClassPath = suppliedOldMappings.getClass(foundClassPath);
                if (className == null) {
                    // Reuse old intermediary name instead of generating
                    //   a new one (see the comment a few lines above)
                    String[] r = foundClassPath.split("\\$");
                    String oldClassName = r[r.length - 1];

                    // Only reuse the old name if it's actually an intermediary
                    if (oldClassName.contains("class_")) {
                        className = oldClassName;
                    }
                }
            }

            if (oldClassPath == null && outputFileOldMappings != null) {
                oldClassPath = outputFileOldMappings.getClass(c.getFullyQualifiedName());
            }
        }

        // If class is obfuscated but no old name could be reused,
        //   generate a new intermediary name
        if (className == null) {
            className = next(c, "class");
        }

        // If it was mapped differently previously, log the change
        if (oldClassPath != null && oldClassPath != className) {
            System.out.println(oldClassPath + " is now " + packagePrefix + className);
        }

        writer.write("CLASS\t" + c.getFullyQualifiedName() + "\t" + packagePrefix + className + "\n");

        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(storage, c, f);
            if (fName == null) {
                fName = f.getName();
            }

            if (fName != null) {
                if (fName.equals(f.getName())) {
                    continue;
                }
                writer.write("FIELD\t" + c.getFullyQualifiedName()
                        + "\t" + f.getDescriptor()
                        + "\t" + f.getName()
                        + "\t" + fName + "\n");
            }
        }

        for (JarMethodEntry m : c.getMethods()) {
            String mName = getMethodName(storageOld, storage, c, m);
            if (mName == null) {
                if (!m.getName().startsWith("<") && m.isSource(storage, c)) {
                   mName = m.getName();
                }
            }

            if (mName != null) {
                if (mName.equals(m.getName())) {
                    continue;
                }
                writer.write("METHOD\t" + c.getFullyQualifiedName()
                        + "\t" + m.getDescriptor()
                        + "\t" + m.getName()
                        + "\t" + mName + "\n");
            }
        }

        for (JarClassEntry cc : c.getInnerClasses()) {
            addClass(writer, cc, storageOld, storage, packagePrefix + className + "$", true);
        }
    }

    public void prepareRewrite(File oldMappings) throws IOException {
        suppliedOldMappings = new GenMap();
        matchesBetweenNewAndOldOfficialNames = new GenMap.Dummy();

        // TODO: only read once
        readCounters(oldMappings);

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            suppliedOldMappings.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    "intermediary"
            );
        }
    }

    public void prepareUpdate(File oldMappings, File matches) throws IOException {
        suppliedOldMappings = new GenMap();
        matchesBetweenNewAndOldOfficialNames = new GenMap();

        // TODO: only read once
        readCounters(oldMappings);

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            suppliedOldMappings.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    "intermediary"
            );
        }

        try (FileReader fileReader = new FileReader(matches)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                MatcherUtil.read(reader, true, matchesBetweenNewAndOldOfficialNames::addClass, matchesBetweenNewAndOldOfficialNames::addField, matchesBetweenNewAndOldOfficialNames::addMethod);
            }
        }
    }

    private void readCounters(File counterFile) throws IOException {
        Path counterPath = getExternalCounterFile();

        if (counterPath != null && Files.exists(counterPath)) {
            counterFile = counterPath.toFile();
        }

        try (FileReader fileReader = new FileReader(counterFile)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("# INTERMEDIARY-COUNTER")) {
                        String[] parts = line.split(" ");
                        counters.put(parts[2], Integer.parseInt(parts[3]));
                    }
                }
            }
        }
    }

    private void writeCounters(BufferedWriter writer) throws IOException {
        StringJoiner counterLines = new StringJoiner("\n");

        for (Map.Entry<String, Integer> counter : counters.entrySet()) {
            counterLines.add("# INTERMEDIARY-COUNTER " + counter.getKey() + " " + counter.getValue());
        }

        writer.write(counterLines.toString());
        Path counterPath = getExternalCounterFile();

        if (counterPath != null) {
            Files.write(counterPath, counterLines.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private Path getExternalCounterFile() {
        if (System.getProperty("stitch.counter") != null) {
            return Paths.get(System.getProperty("stitch.counter"));
        }
        return null;
    }
}
