import java.io.File;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dedicated Minecraft Forge 1.16.5 (Forge 36.x / ModLauncher 8.x / EventBus 4.x / Java 8+)
 * runtime DLL injector.
 */
public class ForgeInjector extends Thread implements Supplier<Object>, InvocationHandler {
    private static final String[] SHARED_LIBRARY_PREFIXES = new String[] {
            "java.", "javax.", "sun.", "com.sun.", "jdk.",
            "net.minecraft.", "net.minecraftforge.", "cpw.mods.",
            "org.spongepowered.", "org.lwjgl.", "io.netty.",
            "com.mojang.", "org.apache.logging.", "org.slf4j."
    };

    private final byte[][] classes;
    private volatile Object activeJavaModLoadingContext;
    private volatile Object activeModContainer;
    private volatile Object activeDeferredWorkQueue;
    private volatile Object realModEventBus;
    private volatile Object proxyModEventBus;
    private final List<Object[]> capturedModBusListeners = new ArrayList<Object[]>();
    private final ConcurrentLinkedQueue<Runnable> deferredWorkTasks = new ConcurrentLinkedQueue<Runnable>();
    private volatile int executionPhase = 0;
    private volatile ClassLoader targetClassLoader;
    private volatile PrintWriter activeWriter;
    private final Object renderThreadLock = new Object();
    private volatile boolean renderThreadDone = false;

    private static Object theUnsafe;
    private static Method unsafeAllocateInstance;
    private static Method unsafeObjectFieldOffset;
    private static Method unsafeStaticFieldOffset;
    private static Method unsafeStaticFieldBase;
    private static Method unsafePutObject;
    private static Method unsafeGetObject;
    private static Method unsafePutBoolean;
    private static long overrideFieldOffset = -1;
    private static MethodHandles.Lookup trustedLookup;

    private ForgeInjector(byte[][] classes) {
        super("ForgeInjector-1.16.5");
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        new ForgeInjector(classes).start();
    }

    private static void log(PrintWriter writer, String message) {
        System.out.println("[JarToDLL-1.16.5] " + message);
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

    @Override
    public Object get() {
        return this.activeJavaModLoadingContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("equals".equals(name) && args != null && args.length == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "JarToDLL_1165_Proxy";
        }
        if ("get".equals(name) && (args == null || args.length == 0)) {
            return this.activeDeferredWorkQueue;
        }
        if ("addListener".equals(name) && args != null) {
            Class<?> explicitEventType = null;
            Consumer<Object> consumer = null;
            for (Object arg : args) {
                if (arg instanceof Class<?>) {
                    explicitEventType = (Class<?>) arg;
                } else if (arg instanceof Consumer) {
                    consumer = (Consumer<Object>) arg;
                }
            }
            if (consumer != null) {
                synchronized (this.capturedModBusListeners) {
                    boolean duplicate = false;
                    for (Object[] existing : this.capturedModBusListeners) {
                        if (existing[0] == explicitEventType
                                && (existing[1] == consumer || (existing[1] != null && existing[1].equals(consumer)))) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        this.capturedModBusListeners.add(new Object[] { explicitEventType, consumer });
                    }
                }
            }
        }
        if ("addGenericListener".equals(name) && args != null) {
            Class<?> explicitEventType = null;
            Consumer<Object> consumer = null;
            for (Object arg : args) {
                if (arg instanceof Class<?>) {
                    explicitEventType = (Class<?>) arg;
                } else if (arg instanceof Consumer) {
                    consumer = (Consumer<Object>) arg;
                }
            }
            if (consumer != null) {
                synchronized (this.capturedModBusListeners) {
                    boolean duplicate = false;
                    for (Object[] existing : this.capturedModBusListeners) {
                        if (existing[0] == explicitEventType
                                && (existing[1] == consumer || (existing[1] != null && existing[1].equals(consumer)))) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        this.capturedModBusListeners.add(new Object[] { explicitEventType, consumer });
                    }
                }
            }
        }
        if (this.realModEventBus != null) {
            try {
                forceAccessible(method);
                return method.invoke(this.realModEventBus, args);
            } catch (Throwable ignored) {
                // Keep mod constructor running even if EventBus TypeResolver rejects a synthetic lambda
            }
        }
        Class<?> retType = method.getReturnType();
        if (retType == boolean.class) {
            return Boolean.FALSE;
        }
        if (retType == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static void initUnsafe(PrintWriter writer) {
        if (theUnsafe != null) {
            return;
        }
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            theUnsafe = f.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            unsafeStaticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            unsafeStaticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            unsafePutObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            unsafeGetObject = unsafeClass.getMethod("getObject", Object.class, long.class);
            unsafePutBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);

            try {
                Field overrideField = AccessibleObject.class.getDeclaredField("override");
                overrideFieldOffset = (Long) unsafeObjectFieldOffset.invoke(theUnsafe, overrideField);
            } catch (Throwable ignored) {
                overrideFieldOffset = 12L;
            }

            try {
                Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                Object base = unsafeStaticFieldBase.invoke(theUnsafe, implLookupField);
                long offset = (Long) unsafeStaticFieldOffset.invoke(theUnsafe, implLookupField);
                trustedLookup = (MethodHandles.Lookup) unsafeGetObject.invoke(theUnsafe, base, offset);
            } catch (Throwable ignored) {
            }
            log(writer, "Initialized Unsafe successfully (Java " + System.getProperty("java.version") + ")");
        } catch (Throwable t) {
            log(writer, "Warning: Failed to initialize Unsafe: " + t);
        }
    }

    private static void forceAccessible(AccessibleObject ao) {
        if (ao == null) {
            return;
        }
        try {
            ao.setAccessible(true);
            return;
        } catch (Throwable ignored) {
        }
        try {
            if (theUnsafe != null && unsafePutBoolean != null && overrideFieldOffset > 0) {
                unsafePutBoolean.invoke(theUnsafe, ao, overrideFieldOffset, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void unlockModulesIfPresent(PrintWriter writer, ClassLoader cl) {
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
            Method bootMethod = moduleLayerClass.getMethod("boot");
            Method modulesMethod = moduleLayerClass.getMethod("modules");
            Method getPackagesMethod = moduleClass.getMethod("getPackages");

            Method implAddOpensToAllUnnamed = null;
            Method implAddExportsToAllUnnamed = null;
            for (Method m : moduleClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if ("implAddOpensToAllUnnamed".equals(m.getName()) && params.length == 1 && params[0] == String.class) {
                    forceAccessible(m);
                    implAddOpensToAllUnnamed = m;
                } else if ("implAddExportsToAllUnnamed".equals(m.getName()) && params.length == 1 && params[0] == String.class) {
                    forceAccessible(m);
                    implAddExportsToAllUnnamed = m;
                }
            }
            Object bootLayer = bootMethod.invoke(null);
            if (bootLayer != null) {
                Set<?> mods = (Set<?>) modulesMethod.invoke(bootLayer);
                for (Object mod : mods) {
                    try {
                        Set<?> pkgs = (Set<?>) getPackagesMethod.invoke(mod);
                        if (pkgs != null) {
                            for (Object pkg : pkgs) {
                                if (implAddOpensToAllUnnamed != null) {
                                    implAddOpensToAllUnnamed.invoke(mod, pkg);
                                }
                                if (implAddExportsToAllUnnamed != null) {
                                    implAddExportsToAllUnnamed.invoke(mod, pkg);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            log(writer, "Unlocked JPMS modules for non-Java-8 runtime");
        } catch (ClassNotFoundException ignored) {
            // Expected on Java 8 (standard Minecraft 1.16.5 runtime)
        } catch (Throwable t) {
            log(writer, "Notice during unlockModulesIfPresent: " + t);
        }
    }

    private static boolean canLoadForgeOrMc(ClassLoader cl) {
        if (cl == null) {
            return false;
        }
        String[] probes = new String[] {
                "net.minecraftforge.fml.common.Mod",
                "net.minecraft.client.Minecraft",
                "cpw.mods.fml.common.Mod"
        };
        for (String p : probes) {
            try {
                Class.forName(p, false, cl);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static ClassLoader findForge1165ClassLoader(PrintWriter writer) {
        ClassLoader transformingCandidate = null;
        ClassLoader fallbackCandidate = null;
        Set<Thread> threads = Thread.getAllStackTraces().keySet();

        for (Thread thread : threads) {
            if (thread == null || thread.getContextClassLoader() == null) {
                continue;
            }
            String tName = thread.getName() != null ? thread.getName() : "";
            ClassLoader cl = thread.getContextClassLoader();
            String loaderName = cl.getClass().getName();

            if (tName.equals("Render thread") || tName.equals("Client thread") || tName.equals("main")) {
                if (canLoadForgeOrMc(cl)) {
                    // Confirm actual Minecraft class loader in case TransformingClassLoader delegates
                    try {
                        Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, cl);
                        ClassLoader mcLoader = mcCls.getClassLoader();
                        if (mcLoader != null && canLoadForgeOrMc(mcLoader)) {
                            log(writer, "Selected Minecraft ClassLoader from thread '" + tName + "': "
                                    + mcLoader.getClass().getName());
                            return mcLoader;
                        }
                    } catch (Throwable ignored) {
                    }
                    log(writer, "Selected priority ClassLoader from thread '" + tName + "': " + loaderName);
                    return cl;
                }
            }
            if (loaderName.contains("TransformingClassLoader")) {
                if (canLoadForgeOrMc(cl)) {
                    transformingCandidate = cl;
                }
            } else if (loaderName.contains("LaunchClassLoader") || loaderName.contains("ModuleClassLoader")) {
                if (canLoadForgeOrMc(cl)) {
                    fallbackCandidate = cl;
                }
            }
        }

        if (transformingCandidate != null) {
            log(writer, "Selected TransformingClassLoader: " + transformingCandidate.getClass().getName());
            return transformingCandidate;
        }
        if (fallbackCandidate != null) {
            log(writer, "Selected fallback ClassLoader: " + fallbackCandidate.getClass().getName());
            return fallbackCandidate;
        }
        for (Thread thread : threads) {
            if (thread != null && canLoadForgeOrMc(thread.getContextClassLoader())) {
                return thread.getContextClassLoader();
            }
        }
        return null;
    }

    private static Field findFieldByName(Class<?> ownerClass, String name) {
        Class<?> curr = ownerClass;
        while (curr != null && curr != Object.class) {
            try {
                Field f = curr.getDeclaredField(name);
                forceAccessible(f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    private static Field findFieldByType(Class<?> ownerClass, Class<?> fieldType) {
        Class<?> curr = ownerClass;
        while (curr != null && curr != Object.class) {
            for (Field f : curr.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && fieldType.isAssignableFrom(f.getType())) {
                    forceAccessible(f);
                    return f;
                }
            }
            curr = curr.getSuperclass();
        }
        return null;
    }

    private static void setFieldValue(Object target, Field field, Object value) throws Exception {
        forceAccessible(field);
        if (!Modifier.isFinal(field.getModifiers())) {
            field.set(target, value);
            return;
        }
        if (!field.getType().isPrimitive() && theUnsafe != null) {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    Object base = unsafeStaticFieldBase.invoke(theUnsafe, field);
                    long offset = (Long) unsafeStaticFieldOffset.invoke(theUnsafe, field);
                    unsafePutObject.invoke(theUnsafe, base, offset, value);
                    return;
                } else {
                    long offset = (Long) unsafeObjectFieldOffset.invoke(theUnsafe, field);
                    unsafePutObject.invoke(theUnsafe, target, offset, value);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // Java 8 fallback: clear final modifier bit
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            forceAccessible(modifiersField);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {
        }
        field.set(target, value);
    }

    private static boolean isSharedLibraryClass(String binaryName) {
        for (String prefix : SHARED_LIBRARY_PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String[][] parseClassHeader(byte[] classData) {
        ByteBuffer buf = ByteBuffer.wrap(classData).order(ByteOrder.BIG_ENDIAN);
        if (buf.getInt() != 0xCAFEBABE) {
            throw new RuntimeException("Invalid class magic");
        }
        buf.getChar(); // minor
        buf.getChar(); // major
        int cpSize = buf.getChar();
        String[] cpStrings = new String[cpSize];
        int[] cpClasses = new int[cpSize];

        for (int i = 1; i < cpSize; i++) {
            byte tag = buf.get();
            switch (tag) {
                case 1: {
                    int len = buf.getChar();
                    byte[] strBytes = new byte[len];
                    buf.get(strBytes);
                    cpStrings[i] = new String(strBytes, java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
                case 3:
                case 4:
                    buf.getInt();
                    break;
                case 5:
                case 6:
                    buf.getLong();
                    i++;
                    break;
                case 7:
                    cpClasses[i] = buf.getChar();
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    buf.getChar();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    buf.getChar();
                    buf.getChar();
                    break;
                case 15:
                    buf.get();
                    buf.getChar();
                    break;
                default:
                    throw new RuntimeException("Unknown constant pool tag: " + tag);
            }
        }

        buf.getChar(); // access_flags
        String thisName = cpStrings[cpClasses[buf.getChar()]];
        int superIdx = buf.getChar();
        String superName = superIdx == 0 ? null : cpStrings[cpClasses[superIdx]];
        int ifaceCount = buf.getChar();
        String[] ifaces = new String[ifaceCount];
        for (int i = 0; i < ifaceCount; i++) {
            ifaces[i] = cpStrings[cpClasses[buf.getChar()]];
        }
        return new String[][] { new String[] { thisName, superName }, ifaces };
    }

    private static boolean isDuplicateClassError(Throwable cause) {
        if (cause instanceof LinkageError && cause.getMessage() != null) {
            return cause.getMessage().toLowerCase().contains("duplicate");
        }
        return false;
    }

    private static byte[] tryTransformBytes(Object classTransformer, Method transformMethod, String binaryName,
            byte[] classData) {
        if (classTransformer != null && transformMethod != null) {
            try {
                byte[] transformed = (byte[]) transformMethod.invoke(classTransformer, classData, binaryName,
                        "classloading");
                if (transformed != null && transformed.length > 0) {
                    return transformed;
                }
            } catch (Throwable ignored) {
            }
        }
        return classData;
    }

    private static Class<?> defineSingleClass(
            PrintWriter writer,
            ClassLoader cl,
            Method findLoadedClassMethod,
            Method defineClassMethod,
            Object classTransformer,
            Method transformMethod,
            String binaryName,
            byte[] classData,
            ProtectionDomain pd) throws Exception {
        if (findLoadedClassMethod != null) {
            try {
                Class<?> alreadyLoaded = (Class<?>) findLoadedClassMethod.invoke(cl, binaryName);
                if (alreadyLoaded != null) {
                    return alreadyLoaded;
                }
            } catch (Throwable ignored) {
            }
        }

        if (isSharedLibraryClass(binaryName)) {
            try {
                return Class.forName(binaryName, false, cl);
            } catch (ClassNotFoundException ignored) {
                // Library not present in MC/Forge; fall through and define bundled copy
            }
        }

        byte[] bytesToDefine = tryTransformBytes(classTransformer, transformMethod, binaryName, classData);
        try {
            return (Class<?>) defineClassMethod.invoke(cl, binaryName, bytesToDefine, 0, bytesToDefine.length, pd);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (isDuplicateClassError(cause)) {
                return Class.forName(binaryName, false, cl);
            }
            if (bytesToDefine != classData) {
                try {
                    return (Class<?>) defineClassMethod.invoke(cl, binaryName, classData, 0, classData.length, pd);
                } catch (InvocationTargetException ite2) {
                    Throwable cause2 = ite2.getCause() != null ? ite2.getCause() : ite2;
                    if (isDuplicateClassError(cause2)) {
                        return Class.forName(binaryName, false, cl);
                    }
                    if (cause2 instanceof Exception) {
                        throw (Exception) cause2;
                    }
                    throw new RuntimeException(cause2);
                }
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static Class<?> defineClassRecursive(
            PrintWriter writer,
            ClassLoader cl,
            Method findLoadedClassMethod,
            Method defineClassMethod,
            Object classTransformer,
            Method transformMethod,
            ProtectionDomain pd,
            String internalName,
            Map<String, byte[]> pendingBytes,
            Map<String, String[][]> parsedHeaders,
            Map<String, Class<?>> definedClasses,
            Set<String> visiting) throws Exception {
        if (definedClasses.containsKey(internalName)) {
            return definedClasses.get(internalName);
        }
        byte[] data = pendingBytes.get(internalName);
        if (data == null) {
            return null;
        }
        if (!visiting.add(internalName)) {
            return null;
        }

        String[][] header = parsedHeaders.get(internalName);
        if (header != null) {
            int dollarIdx = internalName.lastIndexOf('$');
            if (dollarIdx > 0) {
                String outerName = internalName.substring(0, dollarIdx);
                if (pendingBytes.containsKey(outerName) && !definedClasses.containsKey(outerName)) {
                    defineClassRecursive(writer, cl, findLoadedClassMethod, defineClassMethod, classTransformer,
                            transformMethod, pd, outerName, pendingBytes, parsedHeaders, definedClasses, visiting);
                }
            }
            String superName = header[0][1];
            if (superName != null && pendingBytes.containsKey(superName) && !definedClasses.containsKey(superName)) {
                defineClassRecursive(writer, cl, findLoadedClassMethod, defineClassMethod, classTransformer,
                        transformMethod, pd, superName, pendingBytes, parsedHeaders, definedClasses, visiting);
            }
            for (String iface : header[1]) {
                if (iface != null && pendingBytes.containsKey(iface) && !definedClasses.containsKey(iface)) {
                    defineClassRecursive(writer, cl, findLoadedClassMethod, defineClassMethod, classTransformer,
                            transformMethod, pd, iface, pendingBytes, parsedHeaders, definedClasses, visiting);
                }
            }
        }

        String binaryName = internalName.replace('/', '.');
        Class<?> defined = defineSingleClass(writer, cl, findLoadedClassMethod, defineClassMethod, classTransformer,
                transformMethod, binaryName, data, pd);
        definedClasses.put(internalName, defined);
        return defined;
    }

    /**
     * Bridges Forge 1.16.5 EventBus 4.x (ASMEventHandler.LOADER) so dynamically generated
     * @SubscribeEvent invokers can resolve classes defined in TransformingClassLoader.
     */
    private static void ensureForge1165EventBusWorks(PrintWriter writer, ClassLoader cl,
            List<Class<?>> allDefinedClasses) {
        try {
            Class<?> asmHandlerCls = Class.forName("net.minecraftforge.eventbus.ASMEventHandler", false, cl);
            Field loaderField = findFieldByName(asmHandlerCls, "LOADER");
            if (loaderField != null) {
                Object loaderObj = loaderField.get(null);
                if (loaderObj instanceof ClassLoader) {
                    ClassLoader asmLoader = (ClassLoader) loaderObj;
                    Class<?> probeCls = null;
                    for (Class<?> c : allDefinedClasses) {
                        if (c != null && !isSharedLibraryClass(c.getName())) {
                            probeCls = c;
                            break;
                        }
                    }
                    boolean canResolve = false;
                    if (probeCls != null) {
                        try {
                            Class.forName(probeCls.getName(), false, asmLoader);
                            canResolve = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!canResolve && asmLoader != cl) {
                        Field parentField = findFieldByName(ClassLoader.class, "parent");
                        if (parentField != null) {
                            Object oldParent = parentField.get(asmLoader);
                            if (oldParent != cl) {
                                setFieldValue(asmLoader, parentField, cl);
                                log(writer, "Reparented EventBus 4.x ASMEventHandler.LOADER -> "
                                        + cl.getClass().getName() + " for 1.16.5 @SubscribeEvent compatibility");
                            }
                        }
                    } else {
                        log(writer, "Verified EventBus 4.x ASMEventHandler.LOADER can resolve injected classes");
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Notice checking EventBus 4.x ASMEventHandler.LOADER: " + t);
        }

        // Also check EventBus 5+/6+ factory fallback if running a custom 1.16.5 fork
        try {
            Class<?> minecraftForgeCls = Class.forName("net.minecraftforge.common.MinecraftForge", false, cl);
            Field eventBusField = minecraftForgeCls.getDeclaredField("EVENT_BUS");
            forceAccessible(eventBusField);
            Object forgeBus = eventBusField.get(null);
            if (forgeBus == null) {
                return;
            }
            Field factoryField = findFieldByName(forgeBus.getClass(), "factory");
            if (factoryField == null) {
                factoryField = findFieldByName(forgeBus.getClass(), "listenerFactory");
            }
            if (factoryField != null) {
                Object currentFactory = factoryField.get(forgeBus);
                if (currentFactory != null) {
                    String factoryName = currentFactory.getClass().getName();
                    if (factoryName.endsWith(".ModLauncherFactory")) {
                        Class<?> clfClass = Class.forName("net.minecraftforge.eventbus.ClassLoaderFactory", false, cl);
                        Constructor<?> clfCtor = clfClass.getDeclaredConstructor();
                        forceAccessible(clfCtor);
                        setFieldValue(forgeBus, factoryField, clfCtor.newInstance());
                        log(writer, "Switched MinecraftForge.EVENT_BUS listenerFactory from ModLauncherFactory to ClassLoaderFactory");
                    } else if (!factoryName.endsWith(".ClassLoaderFactory")) {
                        Class<?> subAnno = Class.forName("net.minecraftforge.eventbus.api.SubscribeEvent", false, cl);
                        Method sampleMethod = null;
                        Class<?> sampleClass = null;
                        for (Class<?> cls : allDefinedClasses) {
                            if (cls == null || cls.isInterface() || Modifier.isAbstract(cls.getModifiers())) {
                                continue;
                            }
                            for (Method m : cls.getDeclaredMethods()) {
                                if (m.getAnnotation((Class) subAnno) != null && !Modifier.isStatic(m.getModifiers())) {
                                    sampleMethod = m;
                                    sampleClass = cls;
                                    break;
                                }
                            }
                            if (sampleMethod != null) {
                                break;
                            }
                        }
                        if (sampleMethod != null && sampleClass != null) {
                            try {
                                Object dummyTarget = unsafeAllocateInstance.invoke(theUnsafe, sampleClass);
                                Method createMethod = currentFactory.getClass().getMethod("create", Method.class, Object.class);
                                forceAccessible(createMethod);
                                createMethod.invoke(currentFactory, sampleMethod, dummyTarget);
                            } catch (Throwable t) {
                                Class<?> clfClass = Class.forName("net.minecraftforge.eventbus.ClassLoaderFactory", false, cl);
                                Constructor<?> clfCtor = clfClass.getDeclaredConstructor();
                                forceAccessible(clfCtor);
                                setFieldValue(forgeBus, factoryField, clfCtor.newInstance());
                                log(writer, "Switched MinecraftForge.EVENT_BUS listenerFactory to ClassLoaderFactory");
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private Object setupForge1165ModLoadingContext(PrintWriter writer, ClassLoader cl, String modId) {
        try {
            Class<?> modLoadingContextCls = Class.forName("net.minecraftforge.fml.ModLoadingContext", false, cl);
            Class<?> fmlJavaModLoadingContextCls = Class
                    .forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext", false, cl);
            Class<?> fmlModContainerCls = Class.forName("net.minecraftforge.fml.javafmlmod.FMLModContainer", false, cl);
            Class<?> modContainerCls = Class.forName("net.minecraftforge.fml.ModContainer", false, cl);
            Class<?> busBuilderCls = Class.forName("net.minecraftforge.eventbus.api.BusBuilder", false, cl);
            Class<?> iEventBusCls = Class.forName("net.minecraftforge.eventbus.api.IEventBus", false, cl);

            Method builderMethod = busBuilderCls.getMethod("builder");
            Object busBuilder = builderMethod.invoke(null);
            Method buildMethod = busBuilderCls.getMethod("build");
            this.realModEventBus = buildMethod.invoke(busBuilder);
            this.proxyModEventBus = Proxy.newProxyInstance(cl, new Class<?>[] { iEventBusCls }, this);

            Object dummyContainer = unsafeAllocateInstance.invoke(theUnsafe, fmlModContainerCls);
            Field eventBusField = findFieldByName(fmlModContainerCls, "eventBus");
            if (eventBusField != null) {
                setFieldValue(dummyContainer, eventBusField, this.proxyModEventBus);
            }
            Field contextExtField = findFieldByName(modContainerCls, "contextExtension");
            if (contextExtField != null) {
                setFieldValue(dummyContainer, contextExtField, this);
            }
            Field modIdField = findFieldByName(modContainerCls, "modId");
            if (modIdField != null && modId != null) {
                setFieldValue(dummyContainer, modIdField, modId);
            }
            Field namespaceField = findFieldByName(modContainerCls, "namespace");
            if (namespaceField != null && modId != null) {
                setFieldValue(dummyContainer, namespaceField, modId);
            }
            this.activeModContainer = dummyContainer;

            Object javaModCtx = unsafeAllocateInstance.invoke(theUnsafe, fmlJavaModLoadingContextCls);
            Field containerField = findFieldByName(fmlJavaModLoadingContextCls, "container");
            if (containerField != null) {
                setFieldValue(javaModCtx, containerField, dummyContainer);
            }
            this.activeJavaModLoadingContext = javaModCtx;

            Method getModLoadingCtx = modLoadingContextCls.getMethod("get");
            Object modLoadingCtx = getModLoadingCtx.invoke(null);
            Field activeContainerField = findFieldByName(modLoadingContextCls, "activeContainer");
            if (activeContainerField != null) {
                setFieldValue(modLoadingCtx, activeContainerField, dummyContainer);
            }
            Field languageExtensionField = findFieldByName(modLoadingContextCls, "languageExtension");
            if (languageExtensionField != null) {
                if (Supplier.class.isAssignableFrom(languageExtensionField.getType())) {
                    setFieldValue(modLoadingCtx, languageExtensionField, this);
                } else {
                    setFieldValue(modLoadingCtx, languageExtensionField, javaModCtx);
                }
            }

            Method fmlGet = fmlJavaModLoadingContextCls.getMethod("get");
            Object verifiedCtx = fmlGet.invoke(null);
            Method getModEventBus = fmlJavaModLoadingContextCls.getMethod("getModEventBus");
            Object verifiedBus = getModEventBus.invoke(verifiedCtx);
            log(writer, "Configured 1.16.5 FMLJavaModLoadingContext (modId=" + modId + ") -> ModEventBus: "
                    + verifiedBus);
            return verifiedBus;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            log(writer, "Notice: Could not configure 1.16.5 FMLJavaModLoadingContext: " + t);
            t.printStackTrace(writer);
            writer.flush();
            return null;
        }
    }

    private Object createForge1165LifecycleEvent(PrintWriter writer, ClassLoader cl, Class<?> eventCls)
            throws Exception {
        Object modContainer = this.activeModContainer;
        // Try standard Forge 1.16.5 constructor: (ModContainer, ModLifecycleEvent/ParallelDispatchEvent) or (ModContainer)
        for (Constructor<?> ctor : eventCls.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && modContainer != null && params[0].isInstance(modContainer)) {
                try {
                    forceAccessible(ctor);
                    Object evt = ctor.newInstance(modContainer);
                    wireEventWorkQueue(cl, evt);
                    return evt;
                } catch (Throwable ignored) {
                }
            }
        }
        // Fallback: Unsafe allocate + wire container & work queue fields so event.enqueueWork() never NPEs
        Object evt = unsafeAllocateInstance.invoke(theUnsafe, eventCls);
        if (modContainer != null) {
            Field containerField = findFieldByName(eventCls, "container");
            if (containerField != null) {
                setFieldValue(evt, containerField, modContainer);
            }
        }
        wireEventWorkQueue(cl, evt);
        return evt;
    }

    private void wireEventWorkQueue(ClassLoader cl, Object eventObj) {
        if (eventObj == null) {
            return;
        }
        try {
            Class<?> dwqCls = Class.forName("net.minecraftforge.fml.DeferredWorkQueue", false, cl);
            Object dwqInstance = null;
            for (Constructor<?> ctor : dwqCls.getDeclaredConstructors()) {
                if (ctor.getParameterTypes().length == 0) {
                    forceAccessible(ctor);
                    dwqInstance = ctor.newInstance();
                    break;
                }
            }
            if (dwqInstance == null) {
                dwqInstance = unsafeAllocateInstance.invoke(theUnsafe, dwqCls);
                Field tasksField = findFieldByType(dwqCls, java.util.Queue.class);
                if (tasksField != null) {
                    setFieldValue(dwqInstance, tasksField, new ConcurrentLinkedQueue<Object>());
                }
            }
            this.activeDeferredWorkQueue = dwqInstance;
            Object dwqSupplierProxy = Proxy.newProxyInstance(cl, new Class<?>[] { Supplier.class }, this);
            Class<?> curr = eventObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    forceAccessible(f);
                    if (f.get(eventObj) != null) {
                        continue;
                    }
                    if (dwqCls.isAssignableFrom(f.getType())) {
                        setFieldValue(eventObj, f, dwqInstance);
                    } else if (Supplier.class.isAssignableFrom(f.getType()) && f.getName().toLowerCase().contains("queue")) {
                        setFieldValue(eventObj, f, dwqSupplierProxy);
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainEventWorkQueue(PrintWriter writer, Object eventObj) {
        if (eventObj == null) {
            return;
        }
        try {
            Class<?> curr = eventObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    forceAccessible(f);
                    Object val = f.get(eventObj);
                    if (val instanceof Supplier) {
                        val = ((Supplier<?>) val).get();
                    }
                    if (val != null && val.getClass().getName().endsWith("DeferredWorkQueue")) {
                        try {
                            Method runTasks = val.getClass().getDeclaredMethod("runTasks");
                            forceAccessible(runTasks);
                            runTasks.invoke(val);
                            log(writer, "Drained DeferredWorkQueue tasks for " + eventObj.getClass().getSimpleName());
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            log(writer, "Notice draining DeferredWorkQueue: " + t);
        }
    }

    private void fireForge1165LifecycleEvent(PrintWriter writer, ClassLoader cl, List<Class<?>> allClasses,
            String eventClassName) {
        try {
            Class<?> eventCls = Class.forName(eventClassName, false, cl);
            Object eventInstance = createForge1165LifecycleEvent(writer, cl, eventCls);
            int invokedCount = 0;

            // 1. Invoke captured modEventBus.addListener(this::setup / this::doClientStuff) Consumers
            List<Object[]> snapshot;
            synchronized (this.capturedModBusListeners) {
                snapshot = new ArrayList<Object[]>(this.capturedModBusListeners);
            }
            for (Object[] entry : snapshot) {
                Class<?> listenerEventType = (Class<?>) entry[0];
                @SuppressWarnings("unchecked")
                Consumer<Object> listenerConsumer = (Consumer<Object>) entry[1];
                if (listenerEventType != null && !listenerEventType.isAssignableFrom(eventCls)) {
                    continue;
                }
                try {
                    listenerConsumer.accept(eventInstance);
                    invokedCount++;
                    log(writer, "Dispatched " + eventCls.getSimpleName() + " to captured ModEventBus Consumer");
                } catch (ClassCastException ignored) {
                    // Lambda expects a different lifecycle event type
                } catch (Throwable t) {
                    log(writer, "Warning in ModEventBus Consumer for " + eventCls.getSimpleName() + ": " + t);
                }
            }

            // 2. Invoke static lifecycle methods declared in mod classes
            for (Class<?> cls : allClasses) {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == eventCls && Modifier.isStatic(m.getModifiers())) {
                        forceAccessible(m);
                        log(writer, "Invoking static lifecycle handler " + cls.getName() + "." + m.getName() + "("
                                + eventCls.getSimpleName() + ")");
                        m.invoke(null, eventInstance);
                        invokedCount++;
                    }
                }
            }

            // 3. Fallback: post to realModEventBus if nothing handled it directly
            if (invokedCount == 0 && this.realModEventBus != null) {
                try {
                    Method postMethod = this.realModEventBus.getClass().getMethod("post",
                            Class.forName("net.minecraftforge.eventbus.api.Event", false, cl));
                    forceAccessible(postMethod);
                    postMethod.invoke(this.realModEventBus, eventInstance);
                    log(writer, "Posted " + eventCls.getSimpleName() + " to realModEventBus");
                } catch (Throwable t) {
                    log(writer, "realModEventBus.post(" + eventCls.getSimpleName() + ") notice: " + t);
                }
            }

            // 4. Execute any enqueued DeferredWorkQueue tasks on Render thread
            drainEventWorkQueue(writer, eventInstance);
            while (!this.deferredWorkTasks.isEmpty()) {
                Runnable task = this.deferredWorkTasks.poll();
                if (task != null) {
                    task.run();
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Warning firing " + eventClassName + ": " + t);
            t.printStackTrace(writer);
            writer.flush();
        }
    }

    private Object instantiateMod(PrintWriter writer, Class<?> modClass, Object modEventBus) throws Exception {
        Constructor<?>[] ctors = modClass.getDeclaredConstructors();
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterTypes().length == 0) {
                forceAccessible(ctor);
                log(writer, "Instantiating " + modClass.getName() + " via no-arg constructor");
                return ctor.newInstance();
            }
        }
        if (this.activeJavaModLoadingContext != null) {
            for (Constructor<?> ctor : ctors) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(this.activeJavaModLoadingContext)) {
                    forceAccessible(ctor);
                    log(writer, "Instantiating " + modClass.getName() + " via (FMLJavaModLoadingContext) constructor");
                    return ctor.newInstance(this.activeJavaModLoadingContext);
                }
            }
        }
        if (modEventBus != null) {
            for (Constructor<?> ctor : ctors) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(modEventBus)) {
                    forceAccessible(ctor);
                    log(writer, "Instantiating " + modClass.getName() + " via (IEventBus) constructor");
                    return ctor.newInstance(modEventBus);
                }
            }
        }
        return modClass.newInstance();
    }

    private static void registerEventBusSubscribers(PrintWriter writer, ClassLoader cl, Object modEventBus,
            List<Class<?>> allClasses) {
        try {
            Class<?> subscriberAnno = Class.forName("net.minecraftforge.fml.common.Mod$EventBusSubscriber", false, cl);
            Class<?> minecraftForgeCls = Class.forName("net.minecraftforge.common.MinecraftForge", false, cl);
            Field forgeBusField = minecraftForgeCls.getDeclaredField("EVENT_BUS");
            forceAccessible(forgeBusField);
            Object forgeBus = forgeBusField.get(null);
            Method busMethod = subscriberAnno.getMethod("bus");
            Method valueMethod = null;
            try {
                valueMethod = subscriberAnno.getMethod("value");
            } catch (NoSuchMethodException ignored) {
            }

            for (Class<?> cls : allClasses) {
                Object anno = cls.getAnnotation((Class) subscriberAnno);
                if (anno == null) {
                    continue;
                }
                // Check Dist[] filter: only skip if strictly DEDICATED_SERVER
                if (valueMethod != null) {
                    try {
                        Object distArrObj = valueMethod.invoke(anno);
                        if (distArrObj instanceof Object[]) {
                            Object[] dists = (Object[]) distArrObj;
                            if (dists.length > 0) {
                                boolean supportsClient = false;
                                for (Object d : dists) {
                                    if (d != null && "CLIENT".equals(d.toString())) {
                                        supportsClient = true;
                                        break;
                                    }
                                }
                                if (!supportsClient) {
                                    log(writer, "Skipping server-only @EventBusSubscriber: " + cls.getName());
                                    continue;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                Object busEnum = busMethod.invoke(anno);
                String busName = busEnum != null ? busEnum.toString() : "FORGE";
                Object targetBus = "MOD".equals(busName) && modEventBus != null ? modEventBus : forgeBus;
                if (targetBus != null) {
                    Method regMethod = targetBus.getClass().getMethod("register", Object.class);
                    forceAccessible(regMethod);
                    regMethod.invoke(targetBus, cls);
                    log(writer, "Registered @EventBusSubscriber " + cls.getName() + " on " + busName + " bus");
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Notice during @EventBusSubscriber scan: " + t);
        }
    }

    private void executeModInitializationOnTargetThread() {
        PrintWriter writer = this.activeWriter;
        ClassLoader cl = this.targetClassLoader;
        try {
            Thread.currentThread().setContextClassLoader(cl);

            Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
            forceAccessible(defineClassMethod);

            Method findLoadedClassMethod = null;
            try {
                findLoadedClassMethod = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
                forceAccessible(findLoadedClassMethod);
            } catch (Throwable ignored) {
            }

            Object classTransformer = null;
            Method transformMethod = null;
            try {
                Field ctField = findFieldByName(cl.getClass(), "classTransformer");
                if (ctField != null) {
                    classTransformer = ctField.get(cl);
                    if (classTransformer != null) {
                        transformMethod = classTransformer.getClass().getDeclaredMethod(
                                "transform", byte[].class, String.class, String.class);
                        forceAccessible(transformMethod);
                        log(writer, "Hooked 1.16.5 ModLauncher ClassTransformer");
                    }
                }
            } catch (Throwable ignored) {
            }

            ProtectionDomain pd = cl.getClass().getProtectionDomain();
            Map<String, byte[]> pendingBytes = new LinkedHashMap<String, byte[]>();
            Map<String, String[][]> parsedHeaders = new HashMap<String, String[][]>();
            Map<String, Class<?>> definedClasses = new LinkedHashMap<String, Class<?>>();
            Set<String> visiting = new HashSet<String>();

            log(writer, "Parsing and defining " + classes.length + " classes for Forge 1.16.5...");

            for (byte[] classData : classes) {
                if (classData == null || classData.length < 16) {
                    continue;
                }
                String[][] header = parseClassHeader(classData);
                String internalName = header[0][0];
                pendingBytes.put(internalName, classData);
                parsedHeaders.put(internalName, header);
            }

            for (String internalName : pendingBytes.keySet()) {
                defineClassRecursive(writer, cl, findLoadedClassMethod, defineClassMethod, classTransformer,
                        transformMethod, pd, internalName, pendingBytes, parsedHeaders, definedClasses, visiting);
            }

            List<Class<?>> allDefinedClasses = new ArrayList<Class<?>>(definedClasses.values());
            log(writer, "Successfully defined " + allDefinedClasses.size() + " classes!");

            ensureForge1165EventBusWorks(writer, cl, allDefinedClasses);

            Class<?> modAnnotation = Class.forName("net.minecraftforge.fml.common.Mod", false, cl);
            List<Class<?>> modClasses = new ArrayList<Class<?>>();
            String detectedModId = "injectedmod";
            Method modValueMethod = null;
            try {
                modValueMethod = modAnnotation.getMethod("value");
            } catch (NoSuchMethodException ignored) {
            }

            for (Class<?> tClass : allDefinedClasses) {
                if (tClass == null) {
                    continue;
                }
                Object anno = tClass.getAnnotation((Class) modAnnotation);
                if (anno != null) {
                    modClasses.add(tClass);
                    if (modValueMethod != null) {
                        try {
                            Object val = modValueMethod.invoke(anno);
                            if (val instanceof String && ((String) val).length() > 0) {
                                detectedModId = (String) val;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            Object modEventBus = setupForge1165ModLoadingContext(writer, cl, detectedModId);

            for (Class<?> modClass : modClasses) {
                Object modInstance = instantiateMod(writer, modClass, modEventBus);
                if (this.activeModContainer != null) {
                    try {
                        Field modClassField = findFieldByName(this.activeModContainer.getClass(), "modClass");
                        if (modClassField != null) {
                            setFieldValue(this.activeModContainer, modClassField, modClass);
                        }
                        Field modInstanceField = findFieldByName(this.activeModContainer.getClass(), "modInstance");
                        if (modInstanceField != null) {
                            setFieldValue(this.activeModContainer, modInstanceField, modInstance);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            registerEventBusSubscribers(writer, cl, modEventBus, allDefinedClasses);

            fireForge1165LifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent");
            fireForge1165LifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent");
            fireForge1165LifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent");
            fireForge1165LifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.InterModProcessEvent");
            fireForge1165LifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent");

            log(writer, "Successfully injected 1.16.5 Forge mod!");
        } catch (Throwable t) {
            log(writer, "Error during 1.16.5 mod initialization: " + t);
            t.printStackTrace(System.out);
            if (writer != null) {
                t.printStackTrace(writer);
                writer.flush();
            }
        } finally {
            synchronized (this.renderThreadLock) {
                this.renderThreadDone = true;
                this.renderThreadLock.notifyAll();
            }
        }
    }

    @Override
    public void run() {
        if (this.executionPhase == 1) {
            executeModInitializationOnTargetThread();
            return;
        }

        try (PrintWriter writer = new PrintWriter(
                System.getProperty("user.home") + File.separator + "jar-to-dll-log.txt", "UTF-8")) {
            this.activeWriter = writer;
            log(writer, "Starting JarToDLL 1.16.5 Forge Injector!");
            try {
                initUnsafe(writer);
                unlockModulesIfPresent(writer, null);

                ClassLoader cl = findForge1165ClassLoader(writer);
                if (cl == null) {
                    throw new Exception("Could not locate Minecraft Forge 1.16.5 ClassLoader");
                }
                this.targetClassLoader = cl;
                this.setContextClassLoader(cl);
                unlockModulesIfPresent(writer, cl);

                this.executionPhase = 1;
                boolean scheduledOnRenderThread = false;
                try {
                    Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, cl);
                    if (Executor.class.isAssignableFrom(mcCls)) {
                        Object mc = null;
                        for (Method m : mcCls.getDeclaredMethods()) {
                            if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                                    && m.getReturnType() == mcCls) {
                                forceAccessible(m);
                                mc = m.invoke(null);
                                if (mc != null) {
                                    break;
                                }
                            }
                        }
                        if (mc instanceof Executor) {
                            log(writer, "Scheduling 1.16.5 mod initialization on Minecraft Render thread...");
                            ((Executor) mc).execute(this);
                            scheduledOnRenderThread = true;
                            synchronized (this.renderThreadLock) {
                                if (!this.renderThreadDone) {
                                    this.renderThreadLock.wait(5000L);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                if (!scheduledOnRenderThread || !this.renderThreadDone) {
                    log(writer, "Running 1.16.5 mod initialization directly on injector thread...");
                    executeModInitializationOnTargetThread();
                }
            } catch (Throwable e) {
                e.printStackTrace(System.out);
                e.printStackTrace(writer);
                writer.flush();
            }
        } catch (Throwable e) {
            e.printStackTrace(System.out);
        }
    }
}
