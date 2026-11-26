import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class HeaderConverter {

    private static final String[] PROHIBITED_PREFIXES = new String[] {
            "java/", "javax/", "sun/", "com/sun/", "jdk/"
    };

    private static final String[] FORGE_1165_CORE_PREFIXES = new String[] {
            "net/minecraft/", "net/minecraftforge/", "cpw/mods/"
    };

    private static class ClassFile implements Comparable<ClassFile> {
        private String name;
        private String superClass;
        private String[] interfaces;
        private int majorVersion;
        private int multiReleaseVersion = 8;
        private byte[] classData;
        private String[] cpStrings;
        private short[] cpClasses;
        private int priority = 0;
        private static final int HEAD = -889275714;
        private static final byte CONSTANT_Utf8 = 1;
        private static final byte CONSTANT_Integer = 3;
        private static final byte CONSTANT_Float = 4;
        private static final byte CONSTANT_Long = 5;
        private static final byte CONSTANT_Double = 6;
        private static final byte CONSTANT_Class = 7;
        private static final byte CONSTANT_String = 8;
        private static final byte CONSTANT_FieldRef = 9;
        private static final byte CONSTANT_MethodRef = 10;
        private static final byte CONSTANT_InterfaceMethodRef = 11;
        private static final byte CONSTANT_NameAndType = 12;
        private static final byte CONSTANT_MethodHandle = 15;
        private static final byte CONSTANT_MethodType = 16;
        private static final byte CONSTANT_Dynamic = 17;
        private static final byte CONSTANT_InvokeDynamic = 18;
        private static final byte CONSTANT_Module = 19;
        private static final byte CONSTANT_Package = 20;

        public ClassFile(byte[] classData, int multiReleaseVersion) {
            this.classData = classData;
            this.multiReleaseVersion = multiReleaseVersion;
            this.parse(ByteBuffer.wrap(classData));
        }

        private void parse(ByteBuffer buf) {
            if (buf.order(ByteOrder.BIG_ENDIAN).getInt() != HEAD) {
                throw new RuntimeException("Not a class file");
            }
            buf.getChar(); // minor
            this.majorVersion = buf.getChar();
            int cpSize = buf.getChar();
            this.cpStrings = new String[cpSize];
            this.cpClasses = new short[cpSize];

            for (int i = 1; i < cpSize; i++) {
                byte tag = buf.get();
                switch (tag) {
                    case CONSTANT_Utf8:
                        this.cpStrings[i] = this.decodeString(buf);
                        break;
                    default:
                        throw new RuntimeException("Failed to read constant pool because of type " + tag);
                    case CONSTANT_Integer:
                        buf.getInt();
                        break;
                    case CONSTANT_Float:
                        buf.getFloat();
                        break;
                    case CONSTANT_Long:
                        buf.getLong();
                        ++i;
                        break;
                    case CONSTANT_Double:
                        buf.getDouble();
                        ++i;
                        break;
                    case CONSTANT_Class:
                        this.cpClasses[i] = buf.getShort();
                        break;
                    case CONSTANT_String:
                    case CONSTANT_MethodType:
                    case CONSTANT_Module:
                    case CONSTANT_Package:
                        buf.getChar();
                        break;
                    case CONSTANT_FieldRef:
                    case CONSTANT_MethodRef:
                    case CONSTANT_InterfaceMethodRef:
                    case CONSTANT_NameAndType:
                    case CONSTANT_Dynamic:
                    case CONSTANT_InvokeDynamic:
                        buf.getChar();
                        buf.getChar();
                        break;
                    case CONSTANT_MethodHandle:
                        buf.get();
                        buf.getChar();
                        break;
                }
            }

            buf.getChar();
            this.name = this.cpStrings[this.cpClasses[buf.getChar()]];
            int superIdx = buf.getChar();
            this.superClass = superIdx == 0 ? null : this.cpStrings[this.cpClasses[superIdx]];
            this.interfaces = new String[buf.getChar()];

            for (int i = 0; i < this.interfaces.length; i++) {
                this.interfaces[i] = this.cpStrings[this.cpClasses[buf.getChar()]];
            }
        }

        private String decodeString(ByteBuffer buf) {
            int size = buf.getChar();
            int oldLimit = buf.limit();
            buf.limit(buf.position() + size);
            StringBuilder sb = new StringBuilder(size + (size >> 1) + 16);

            while (buf.hasRemaining()) {
                byte b = buf.get();
                if ((b & 0x80) == 0) {
                    sb.append((char) b);
                } else {
                    int b2 = buf.get();
                    if ((b & 240) != 224) {
                        sb.append((char) ((b & 31) << 6 | b2 & 63));
                    } else {
                        int b3 = buf.get();
                        sb.append((char) ((b & 15) << 12 | (b2 & 63) << 6 | b3 & 63));
                    }
                }
            }

            buf.limit(oldLimit);
            return sb.toString();
        }

        public String getName() {
            return this.name;
        }

        public String getSuperClass() {
            return this.superClass;
        }

        public String[] getInterfaces() {
            return this.interfaces;
        }

        public int getMajorVersion() {
            return this.majorVersion;
        }

        public int getMultiReleaseVersion() {
            return this.multiReleaseVersion;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public byte[] getClassData() {
            return this.classData;
        }

        @Override
        public int compareTo(ClassFile o) {
            return o.priority - this.priority;
        }
    }

    private static String convertByteArrayToCppString(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 5 + 8);
        result.append("{ ");
        for (byte b : data) {
            result.append(b).append(", ");
        }
        return result.append("}").toString();
    }

    private static byte[] readStreamFully(InputStream stream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int read = stream.read(buffer);
            if (read == -1) {
                break;
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static void processInjectorClass(String inputPath, String outputPath) throws IOException {
        byte[] classData;
        try (InputStream stream = Files.newInputStream(Paths.get(inputPath))) {
            classData = readStreamFully(stream);
        }
        String cppDataString = convertByteArrayToCppString(classData);

        StringBuilder resultFile = new StringBuilder();
        resultFile.append("#ifndef CLASSES_INJECTOR_H_\n#define CLASSES_INJECTOR_H_\n\n#include \"../jvm/jni.h\"\n\nconst jbyte injector_class_data[] = ")
            .append(cppDataString)
            .append(";\n\n#endif  //CLASSES_INJECTOR_H_");

        try (OutputStream outputStream = Files.newOutputStream(Paths.get(outputPath))) {
            outputStream.write(resultFile.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int parseMultiReleaseVersion(String entryName) {
        String prefix = "META-INF/versions/";
        if (!entryName.startsWith(prefix)) {
            return 8;
        }
        int slash = entryName.indexOf('/', prefix.length());
        if (slash <= prefix.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(entryName.substring(prefix.length(), slash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean hasPrefix(String name, String[] prefixes) {
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static void loadJarClasses(String jarPath, String targetVersion, Map<String, ClassFile> classes, boolean isLibrary) throws IOException {
        boolean is1165 = "1.16.5".equals(targetVersion);
        int maxJavaVersion = is1165 ? 8 : 21;
        int skippedMr = 0;
        int skippedCore = 0;
        int highBytecodeWarnings = 0;

        try (JarFile jarFile = new JarFile(jarPath)) {
            for (ZipEntry entry : jarFile.stream().collect(Collectors.toList())) {
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || entryName.endsWith("module-info.class") || entryName.endsWith("package-info.class")) {
                    continue;
                }
                int mrVersion = parseMultiReleaseVersion(entryName);
                if (mrVersion < 0 || mrVersion > maxJavaVersion) {
                    skippedMr++;
                    continue;
                }
                try (InputStream stream = jarFile.getInputStream(entry)) {
                    ClassFile classFile = new ClassFile(readStreamFully(stream), mrVersion);
                    String clsName = classFile.getName();
                    if (clsName == null || hasPrefix(clsName, PROHIBITED_PREFIXES)) {
                        skippedCore++;
                        continue;
                    }
                    if (is1165 && hasPrefix(clsName, FORGE_1165_CORE_PREFIXES)) {
                        skippedCore++;
                        continue;
                    }
                    if (is1165 && classFile.getMajorVersion() > 52) {
                        highBytecodeWarnings++;
                    }
                    ClassFile existing = classes.get(clsName);
                    if (existing == null || (!isLibrary && classFile.getMultiReleaseVersion() >= existing.getMultiReleaseVersion())) {
                        classes.put(clsName, classFile);
                    }
                }
            }
        }

        if (skippedMr > 0) {
            System.out.println("Filtered " + skippedMr + " incompatible multi-release class(es) for Java " + maxJavaVersion + " (" + targetVersion + ")");
        }
        if (skippedCore > 0) {
            System.out.println("Skipped " + skippedCore + " protected JDK/Minecraft/Forge core class(es)");
        }
        if (highBytecodeWarnings > 0) {
            System.out.println("WARNING: " + highBytecodeWarnings + " class(es) exceed Java 8 bytecode (major > 52). Ensure this JAR is built for Minecraft 1.16.5.");
        }
    }

    private static void processInputJar(String inputPath, String outputPath, String targetVersion, List<String> extraLibs) throws IOException {
        Map<String, ClassFile> classes = new HashMap<>();

        for (String libPath : extraLibs) {
            System.out.println("Merging library JAR: " + libPath);
            loadJarClasses(libPath, targetVersion, classes, true);
        }

        loadJarClasses(inputPath, targetVersion, classes, false);

        if (classes.isEmpty()) {
            throw new RuntimeException("No valid .class files found in input JAR: " + inputPath);
        }

        System.out.println("Processing " + classes.size() + " classes (target: Forge " + targetVersion + ")");

        boolean changed = true;
        int iterations = 0;
        while (changed && iterations++ < classes.size() + 5) {
            changed = false;
            for (ClassFile class2 : classes.values()) {
                if (class2.getSuperClass() != null && classes.containsKey(class2.getSuperClass())) {
                    ClassFile superCls = classes.get(class2.getSuperClass());
                    int newPrio = class2.getPriority() + 1;
                    if (newPrio > superCls.getPriority()) {
                        superCls.setPriority(newPrio);
                        changed = true;
                    }
                }

                for (String superInterface : class2.getInterfaces()) {
                    if (classes.containsKey(superInterface)) {
                        ClassFile ifaceCls = classes.get(superInterface);
                        int newPrio = class2.getPriority() + 1;
                        if (newPrio > ifaceCls.getPriority()) {
                            ifaceCls.setPriority(newPrio);
                            changed = true;
                        }
                    }
                }

                int dollarIdx = class2.getName().lastIndexOf('$');
                if (dollarIdx > 0) {
                    String outerName = class2.getName().substring(0, dollarIdx);
                    if (classes.containsKey(outerName)) {
                        ClassFile outerCls = classes.get(outerName);
                        int newPrio = class2.getPriority() + 1;
                        if (newPrio > outerCls.getPriority()) {
                            outerCls.setPriority(newPrio);
                            changed = true;
                        }
                    }
                }
            }
        }

        System.out.println("Load priority calculated, sorting...");

        List<ClassFile> resultClasses = new ArrayList<>(classes.values());
        Collections.sort(resultClasses);

        List<String> classDataStrings = new ArrayList<>(resultClasses.size());
        for (ClassFile file : resultClasses) {
            classDataStrings.add(convertByteArrayToCppString(file.getClassData()));
        }

        StringBuilder resultFile = new StringBuilder();
        resultFile.append("#ifndef CLASSES_JAR_H_\n#define CLASSES_JAR_H_\n\n#include \"../jvm/jni.h\"\n\n");
        for (int i = 0; i < classDataStrings.size(); i++) {
            resultFile.append("const jbyte class_data_").append(i).append("[] = ").append(classDataStrings.get(i)).append(";\n");
        }
        resultFile.append("\nconst jbyte* jar_classes_data[] = { ");
        for (int i = 0; i < classDataStrings.size(); i++) {
            resultFile.append("class_data_").append(i).append(", ");
        }
        resultFile.append("};\n\nconst jint jar_classes_sizes[] = {");
        for (ClassFile file : resultClasses) {
            resultFile.append(file.getClassData().length).append(", ");
        }
        resultFile.append("};\n\n#endif  //CLASSES_JAR_H_");

        try (OutputStream outputStream = Files.newOutputStream(Paths.get(outputPath))) {
            outputStream.write(resultFile.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 3) {
            throw new RuntimeException("Wrong number of arguments, usage: <injector/input-jar> <input jar or class file> <output path> [--target=1.16.5|1.21.4] [--lib=<extra.jar>]");
        }

        String type = args[0];
        String input = args[1];
        String output = args[2];
        String targetVersion = "1.21.4";
        List<String> extraLibs = new ArrayList<>();

        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--target=")) {
                targetVersion = args[i].substring("--target=".length());
            } else if (args[i].startsWith("--lib=")) {
                extraLibs.add(args[i].substring("--lib=".length()));
            }
        }

        switch (type) {
            case "injector":
                processInjectorClass(input, output);
                break;
            case "input-jar":
                processInputJar(input, output, targetVersion, extraLibs);
                break;
            default:
                throw new RuntimeException("Unknown conversion type: " + type);
        }

        System.out.println("Ready for " + type);
    }
}
