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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ForgeInjector extends Thread implements Supplier<Object>, InvocationHandler {
    private final byte[][] classes;
    private volatile Object activeJavaModLoadingContext;
    private volatile Object realModEventBus;
    private volatile Object proxyModEventBus;
    private final List<Object> capturedModBusListeners = new ArrayList<Object>();
    private volatile int executionPhase = 0;
    private volatile ClassLoader targetClassLoader;
    private volatile PrintWriter activeWriter;
    private final Object renderThreadLock = new Object();
    private volatile boolean renderThreadDone = false;
    private volatile Field bIsUnloadedField;
    private volatile Method mmGetModulesMethod;
    private volatile Method eludomIsToggledMethod;
    private volatile Method eludomOnTickMethod;
    private volatile Object gammaOptionInstance;
    private volatile Field optionInstanceValueField;
    private volatile boolean gammaWasForced = false;

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
        super("ForgeInjector");
        this.classes = classes;
    }

    public static void inject(byte[][] classes) {
        new ForgeInjector(classes).start();
    }

    private static void log(PrintWriter writer, String message) {
        System.out.println("[CleanJarToDLL] " + message);
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

    @Override
    public Object get() {
        return this.activeJavaModLoadingContext;
    }

    private void runModuleTicks() {
        try {
            if (this.bIsUnloadedField != null && Boolean.TRUE.equals(this.bIsUnloadedField.get(null))) {
                if (this.gammaWasForced && this.gammaOptionInstance != null && this.optionInstanceValueField != null) {
                    setFieldValue(this.gammaOptionInstance, this.optionInstanceValueField, Double.valueOf(1.0D));
                    this.gammaWasForced = false;
                }
                return;
            }
            if (this.mmGetModulesMethod != null && this.eludomIsToggledMethod != null && this.eludomOnTickMethod != null) {
                List<?> modules = (List<?>) this.mmGetModulesMethod.invoke(null);
                if (modules != null) {
                    for (int i = 0; i < modules.size(); i++) {
                        Object m = modules.get(i);
                        if (m == null) continue;
                        boolean toggled = Boolean.TRUE.equals(this.eludomIsToggledMethod.invoke(m));
                        if (toggled) {
                            this.eludomOnTickMethod.invoke(m);
                        }
                        if (m.getClass().getName().endsWith(".AmmagEludom")
                                && this.gammaOptionInstance != null
                                && this.optionInstanceValueField != null) {
                            if (toggled) {
                                setFieldValue(this.gammaOptionInstance, this.optionInstanceValueField, Double.valueOf(100.0D));
                                this.gammaWasForced = true;
                            } else if (this.gammaWasForced) {
                                setFieldValue(this.gammaOptionInstance, this.optionInstanceValueField, Double.valueOf(1.0D));
                                this.gammaWasForced = false;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("equals".equals(name) && args != null && args.length == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "CleanJarToDLL_Proxy";
        }
        if (args != null && args.length == 2 && method.getReturnType() == void.class && !"addListener".equals(name)) {
            runModuleTicks();
            return null;
        }
        if ("addListener".equals(name) && args != null) {
            for (Object arg : args) {
                if (arg instanceof Consumer) {
                    synchronized (this.capturedModBusListeners) {
                        this.capturedModBusListeners.add(arg);
                    }
                }
            }
        }
        if (this.realModEventBus != null) {
            try {
                forceAccessible(method);
                return method.invoke(this.realModEventBus, args);
            } catch (Throwable ignored) {
                // Swallow TypeResolver / IModBusEvent errors on lambda registration so
                // <clinit>/init() never aborts
            }
        }
        Class<?> retType = method.getReturnType();
        if (retType == boolean.class) {
            return Boolean.FALSE;
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

            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafeStaticFieldBase.invoke(theUnsafe, implLookupField);
            long offset = (Long) unsafeStaticFieldOffset.invoke(theUnsafe, implLookupField);
            trustedLookup = (MethodHandles.Lookup) unsafeGetObject.invoke(theUnsafe, base, offset);
            log(writer, "Initialized Unsafe and IMPL_LOOKUP successfully");
        } catch (Throwable t) {
            log(writer, "Warning: Failed to initialize Unsafe: " + t);
        }
    }

    private static void forceAccessible(AccessibleObject ao) {
        if (ao == null) {
            return;
        }
        try {
            if (theUnsafe != null && unsafePutBoolean != null && overrideFieldOffset > 0) {
                unsafePutBoolean.invoke(theUnsafe, ao, overrideFieldOffset, true);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            ao.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static void unlockModules(PrintWriter writer, ClassLoader cl) {
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
            Method bootMethod = moduleLayerClass.getMethod("boot");
            Method modulesMethod = moduleLayerClass.getMethod("modules");
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Method getModuleMethod = Class.class.getMethod("getModule");
            Method getLayerMethod = moduleClass.getMethod("getLayer");
            Method getUnnamedModuleMethod = ClassLoader.class.getMethod("getUnnamedModule");

            Method implAddOpensToAllUnnamed = null;
            Method implAddExportsToAllUnnamed = null;
            Method implAddReadsAllUnnamed = null;
            Method implAddReads = null;
            Method implAddOpensToModule = null;
            Method implAddExportsToModule = null;

            for (Method m : moduleClass.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();
                if ("implAddOpensToAllUnnamed".equals(name) && params.length == 1 && params[0] == String.class) {
                    forceAccessible(m);
                    implAddOpensToAllUnnamed = m;
                } else if ("implAddExportsToAllUnnamed".equals(name) && params.length == 1
                        && params[0] == String.class) {
                    forceAccessible(m);
                    implAddExportsToAllUnnamed = m;
                } else if ("implAddReadsAllUnnamed".equals(name) && params.length == 0) {
                    forceAccessible(m);
                    implAddReadsAllUnnamed = m;
                } else if ("implAddReads".equals(name) && params.length == 1 && params[0] == moduleClass) {
                    forceAccessible(m);
                    implAddReads = m;
                } else if ("implAddOpens".equals(name) && params.length == 2 && params[0] == String.class
                        && params[1] == moduleClass) {
                    forceAccessible(m);
                    implAddOpensToModule = m;
                } else if ("implAddExports".equals(name) && params.length == 2 && params[0] == String.class
                        && params[1] == moduleClass) {
                    forceAccessible(m);
                    implAddExportsToModule = m;
                }
            }

            Set<Object> layers = new HashSet<Object>();
            Object bootLayer = bootMethod.invoke(null);
            if (bootLayer != null) {
                layers.add(bootLayer);
            }

            Set<Object> allModules = new HashSet<Object>();
            Set<Object> targetModules = new HashSet<Object>();
            targetModules.add(getUnnamedModuleMethod.invoke(ClassLoader.getSystemClassLoader()));
            if (cl != null) {
                targetModules.add(getUnnamedModuleMethod.invoke(cl));
                String[] probeClasses = new String[] {
                        "net.minecraftforge.fml.common.Mod",
                        "net.minecraftforge.common.MinecraftForge",
                        "net.minecraftforge.eventbus.EventBus",
                        "net.jodah.typetools.TypeResolver",
                        "net.minecraft.client.Minecraft",
                        "cpw.mods.cl.ModuleClassLoader",
                        "cpw.mods.modlauncher.TransformingClassLoader"
                };
                for (String probe : probeClasses) {
                    try {
                        Class<?> c = Class.forName(probe, false, cl);
                        Object mod = getModuleMethod.invoke(c);
                        if (mod != null) {
                            allModules.add(mod);
                            targetModules.add(mod);
                            Object layer = getLayerMethod.invoke(mod);
                            if (layer != null) {
                                layers.add(layer);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t != null && t.getContextClassLoader() != null) {
                    try {
                        targetModules.add(getUnnamedModuleMethod.invoke(t.getContextClassLoader()));
                    } catch (Throwable ignored) {
                    }
                }
            }

            for (Object layer : layers) {
                try {
                    Set<?> layerMods = (Set<?>) modulesMethod.invoke(layer);
                    if (layerMods != null) {
                        allModules.addAll(layerMods);
                    }
                } catch (Throwable ignored) {
                }
            }

            for (Object mod : allModules) {
                if (mod == null)
                    continue;
                try {
                    if (implAddReadsAllUnnamed != null) {
                        implAddReadsAllUnnamed.invoke(mod);
                    }
                    if (implAddReads != null) {
                        for (Object targetMod : targetModules) {
                            if (targetMod != null) {
                                implAddReads.invoke(mod, targetMod);
                            }
                        }
                    }
                    Set<?> pkgs = (Set<?>) getPackagesMethod.invoke(mod);
                    if (pkgs != null) {
                        for (Object pkgObj : pkgs) {
                            String pkg = (String) pkgObj;
                            if (implAddOpensToAllUnnamed != null) {
                                implAddOpensToAllUnnamed.invoke(mod, pkg);
                            }
                            if (implAddExportsToAllUnnamed != null) {
                                implAddExportsToAllUnnamed.invoke(mod, pkg);
                            }
                            for (Object targetMod : targetModules) {
                                if (targetMod != null) {
                                    if (implAddOpensToModule != null) {
                                        implAddOpensToModule.invoke(mod, pkg, targetMod);
                                    }
                                    if (implAddExportsToModule != null) {
                                        implAddExportsToModule.invoke(mod, pkg, targetMod);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            log(writer, "Unlocked " + allModules.size() + " JPMS modules across " + layers.size() + " layers");
        } catch (ClassNotFoundException e) {
            // Java 8 does not have java.lang.Module
        } catch (Throwable t) {
            log(writer, "Warning during unlockModules: " + t);
        }
    }

    private static Class<?> tryGetClass(ClassLoader cl, String... names) throws ClassNotFoundException {
        ClassNotFoundException lastException = null;
        for (String name : names) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException e) {
                lastException = e;
            }
            try {
                return cl.loadClass(name);
            } catch (ClassNotFoundException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new ClassNotFoundException("No class names provided");
    }

    private static boolean canLoadForgeOrMc(ClassLoader cl) {
        if (cl == null)
            return false;
        String[] probes = new String[] {
                "net.minecraftforge.fml.common.Mod",
                "cpw.mods.fml.common.Mod",
                "net.minecraft.client.Minecraft"
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

    private static ClassLoader findForgeClassLoader(PrintWriter writer) {
        ClassLoader fallbackCandidate = null;
        Set<Thread> threads = Thread.getAllStackTraces().keySet();

        for (Thread thread : threads) {
            if (thread == null || thread.getContextClassLoader() == null)
                continue;
            String tName = thread.getName() != null ? thread.getName() : "";
            ClassLoader cl = thread.getContextClassLoader();
            String loaderName = cl.getClass().getName();
            if (tName.equals("Render thread") || tName.equals("Client thread") || tName.equals("main")
                    || tName.equals("Server thread")) {
                if (canLoadForgeOrMc(cl)) {
                    log(writer, "Selected priority thread ClassLoader from thread '" + tName + "': " + loaderName);
                    return cl;
                }
            }
            if (loaderName.contains("TransformingClassLoader") ||
                    loaderName.contains("LaunchClassLoader") ||
                    loaderName.contains("RelaunchClassLoader") ||
                    loaderName.contains("ModuleClassLoader") ||
                    loaderName.contains("SecureModuleClassLoader")) {
                if (canLoadForgeOrMc(cl)) {
                    fallbackCandidate = cl;
                } else if (fallbackCandidate == null) {
                    fallbackCandidate = cl;
                }
            }
        }

        if (fallbackCandidate != null) {
            log(writer, "Selected candidate ClassLoader: " + fallbackCandidate.getClass().getName());
            return fallbackCandidate;
        }

        for (Thread thread : threads) {
            if (thread == null || thread.getContextClassLoader() == null)
                continue;
            if (canLoadForgeOrMc(thread.getContextClassLoader())) {
                return thread.getContextClassLoader();
            }
        }
        return null;
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
            Method defineClassMethod,
            Object classTransformer,
            Method transformMethod,
            String binaryName,
            byte[] classData,
            ProtectionDomain pd) throws Exception {
        byte[] bytesToDefine = tryTransformBytes(classTransformer, transformMethod, binaryName, classData);
        try {
            return (Class<?>) defineClassMethod.invoke(cl, binaryName, bytesToDefine, 0, bytesToDefine.length, pd);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (isDuplicateClassError(cause)) {
                return Class.forName(binaryName, false, cl);
            }
            // Retry with raw untransformed bytes if transformed bytes failed verification
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
                    defineClassRecursive(writer, cl, defineClassMethod, classTransformer, transformMethod, pd,
                            outerName, pendingBytes, parsedHeaders, definedClasses, visiting);
                }
            }
            String superName = header[0][1];
            if (superName != null && pendingBytes.containsKey(superName) && !definedClasses.containsKey(superName)) {
                defineClassRecursive(writer, cl, defineClassMethod, classTransformer, transformMethod, pd, superName,
                        pendingBytes, parsedHeaders, definedClasses, visiting);
            }
            for (String iface : header[1]) {
                if (iface != null && pendingBytes.containsKey(iface) && !definedClasses.containsKey(iface)) {
                    defineClassRecursive(writer, cl, defineClassMethod, classTransformer, transformMethod, pd, iface,
                            pendingBytes, parsedHeaders, definedClasses, visiting);
                }
            }
        }

        String binaryName = internalName.replace('/', '.');
        Class<?> defined = defineSingleClass(writer, cl, defineClassMethod, classTransformer, transformMethod,
                binaryName, data, pd);
        definedClasses.put(internalName, defined);
        return defined;
    }

    private static void registerPackagesInParentModuleClassLoaders(PrintWriter writer, ClassLoader gameClassLoader,
            Set<String> packageNames) {
        try {
            Set<ClassLoader> loaders = new HashSet<ClassLoader>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t != null && t.getContextClassLoader() != null) {
                    loaders.add(t.getContextClassLoader());
                }
            }
            String[] probes = new String[] {
                    "net.minecraftforge.eventbus.EventBus",
                    "net.jodah.typetools.TypeResolver",
                    "net.minecraftforge.fml.common.Mod"
            };
            for (String p : probes) {
                try {
                    Class<?> c = Class.forName(p, false, gameClassLoader);
                    if (c.getClassLoader() != null) {
                        loaders.add(c.getClassLoader());
                    }
                } catch (Throwable ignored) {
                }
            }
            for (ClassLoader loader : loaders) {
                if (loader == null || loader == gameClassLoader)
                    continue;
                Field parentLoadersField = findFieldByName(loader.getClass(), "parentLoaders");
                if (parentLoadersField != null) {
                    Object mapObj = parentLoadersField.get(loader);
                    if (mapObj instanceof Map) {
                        Map<String, ClassLoader> parentLoaders = (Map<String, ClassLoader>) mapObj;
                        for (String pkg : packageNames) {
                            parentLoaders.put(pkg, gameClassLoader);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log(writer, "Notice registering packages in ModuleClassLoader: " + t);
        }
    }

    private static void ensureEventBusFactoryWorks(PrintWriter writer, ClassLoader cl,
            List<Class<?>> allDefinedClasses) {
        try {
            Class<?> minecraftForgeCls = Class.forName("net.minecraftforge.common.MinecraftForge", false, cl);
            Field eventBusField = minecraftForgeCls.getDeclaredField("EVENT_BUS");
            forceAccessible(eventBusField);
            Object forgeBus = eventBusField.get(null);
            if (forgeBus == null)
                return;

            Class<?> subAnno = Class.forName("net.minecraftforge.eventbus.api.SubscribeEvent", false, cl);
            Method sampleMethod = null;
            Class<?> sampleClass = null;
            for (Class<?> cls : allDefinedClasses) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getAnnotation((Class) subAnno) != null && !Modifier.isStatic(m.getModifiers())) {
                        sampleMethod = m;
                        sampleClass = cls;
                        break;
                    }
                }
                if (sampleMethod != null)
                    break;
            }
            if (sampleMethod == null || sampleClass == null)
                return;

            Field factoryField = findFieldByName(forgeBus.getClass(), "factory");
            if (factoryField == null) {
                factoryField = findFieldByName(forgeBus.getClass(), "listenerFactory");
            }
            if (factoryField != null) {
                Object currentFactory = factoryField.get(forgeBus);
                if (currentFactory != null) {
                    Object dummyTarget = unsafeAllocateInstance.invoke(theUnsafe, sampleClass);
                    Method createMethod = currentFactory.getClass().getMethod("create", Method.class, Object.class);
                    forceAccessible(createMethod);
                    try {
                        createMethod.invoke(currentFactory, sampleMethod, dummyTarget);
                        log(writer, "Verified EventBus listenerFactory (" + currentFactory.getClass().getSimpleName()
                                + ") works on injected classes");
                    } catch (Throwable t) {
                        log(writer, "ModLauncherFactory threw " + (t.getCause() != null ? t.getCause() : t)
                                + "; switching EVENT_BUS to ClassLoaderFactory");
                        Class<?> clfClass = Class.forName("net.minecraftforge.eventbus.ClassLoaderFactory", false, cl);
                        Constructor<?> clfCtor = clfClass.getDeclaredConstructor();
                        forceAccessible(clfCtor);
                        Object newFactory = clfCtor.newInstance();
                        setFieldValue(forgeBus, factoryField, newFactory);
                        log(writer, "Switched MinecraftForge.EVENT_BUS factory to ClassLoaderFactory");
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Notice checking EventBus factory: " + t);
        }
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

    private static void setFieldValue(Object target, Field field, Object value) throws Exception {
        forceAccessible(field);
        if (!Modifier.isFinal(field.getModifiers())) {
            field.set(target, value);
            return;
        }
        if (!field.getType().isPrimitive()) {
            try {
                if (theUnsafe != null) {
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
                }
            } catch (Throwable ignored) {
            }
        }
        field.set(target, value);
    }

    private Object setupModernModLoadingContext(PrintWriter writer, ClassLoader cl) {
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
            log(writer, "Configured FMLJavaModLoadingContext -> ModEventBus: " + verifiedBus);
            return verifiedBus;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            log(writer, "Notice: Could not configure FMLJavaModLoadingContext: " + t);
            t.printStackTrace(writer);
            writer.flush();
            return null;
        }
    }

    private Object instantiateMod(PrintWriter writer, Class<?> modClass, Object modEventBus) throws Exception {
        Constructor<?>[] ctors = modClass.getDeclaredConstructors();
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
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterTypes().length == 0) {
                forceAccessible(ctor);
                log(writer, "Instantiating " + modClass.getName() + " via no-arg constructor");
                return ctor.newInstance();
            }
        }
        return modClass.newInstance();
    }

    private void fireLifecycleEvent(PrintWriter writer, ClassLoader cl, List<Class<?>> allClasses,
            String eventClassName) {
        try {
            Class<?> eventCls = Class.forName(eventClassName, false, cl);
            Object eventInstance = unsafeAllocateInstance.invoke(theUnsafe, eventCls);
            int invokedCount = 0;

            for (Class<?> cls : allClasses) {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == eventCls && Modifier.isStatic(m.getModifiers())) {
                        forceAccessible(m);
                        log(writer, "Invoking lifecycle handler " + cls.getName() + "." + m.getName() + "("
                                + eventCls.getSimpleName() + ")");
                        m.invoke(null, eventInstance);
                        invokedCount++;
                    }
                }
            }

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
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Warning firing " + eventClassName + ": " + t);
            t.printStackTrace(writer);
            writer.flush();
        }
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

            for (Class<?> cls : allClasses) {
                Object anno = cls.getAnnotation((Class) subscriberAnno);
                if (anno != null) {
                    Object busEnum = busMethod.invoke(anno);
                    String busName = busEnum != null ? busEnum.toString() : "FORGE";
                    Object targetBus = "MOD".equals(busName) && modEventBus != null ? modEventBus : forgeBus;
                    if (targetBus != null) {
                        Method regMethod = targetBus.getClass().getMethod("register", Object.class);
                        forceAccessible(regMethod);
                        regMethod.invoke(targetBus, cls);
                        log(writer,
                                "Registered @EventBusSubscriber class " + cls.getName() + " on " + busName + " bus");
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Notice during @EventBusSubscriber scan: " + t);
        }
    }

    private void cleanupAndPrepareModState(PrintWriter writer, ClassLoader cl, List<Class<?>> allClasses) {
        try {
            Object forgeBus = null;
            Method unregisterMethod = null;
            try {
                Class<?> minecraftForgeCls = Class.forName("net.minecraftforge.common.MinecraftForge", false, cl);
                Field forgeBusField = minecraftForgeCls.getDeclaredField("EVENT_BUS");
                forceAccessible(forgeBusField);
                forgeBus = forgeBusField.get(null);
                if (forgeBus != null) {
                    unregisterMethod = forgeBus.getClass().getMethod("unregister", Object.class);
                    forceAccessible(unregisterMethod);
                }
            } catch (Throwable ignored) {
            }

            for (Class<?> cls : allClasses) {
                String name = cls.getName();
                if (name.endsWith(".b")) {
                    Field isUnloadedField = findFieldByName(cls, "isUnloaded");
                    if (isUnloadedField != null) {
                        this.bIsUnloadedField = isUnloadedField;
                        setFieldValue(null, isUnloadedField, Boolean.FALSE);
                    }
                    Field initField = findFieldByName(cls, "initialized");
                    if (initField != null) {
                        setFieldValue(null, initField, Boolean.FALSE);
                    }
                    Field khField = findFieldByName(cls, "keyHandler");
                    if (khField != null) {
                        Object oldKh = khField.get(null);
                        if (oldKh != null && forgeBus != null && unregisterMethod != null) {
                            try {
                                unregisterMethod.invoke(forgeBus, oldKh);
                            } catch (Throwable ignored) {
                            }
                        }
                        setFieldValue(null, khField, null);
                    }
                } else if (name.endsWith(".MM")) {
                    try {
                        Method getModules = cls.getDeclaredMethod("getModules");
                        forceAccessible(getModules);
                        this.mmGetModulesMethod = getModules;
                        List<?> existing = (List<?>) getModules.invoke(null);
                        if (existing != null && !existing.isEmpty()) {
                            if (forgeBus != null && unregisterMethod != null) {
                                for (Object modObj : existing) {
                                    if (modObj != null) {
                                        try {
                                            unregisterMethod.invoke(forgeBus, modObj);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                            existing.clear();
                            log(writer, "Cleared previous module instances before re-initialization");
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (name.endsWith(".Eludom")) {
                    try {
                        Method isToggled = cls.getDeclaredMethod("isToggled");
                        forceAccessible(isToggled);
                        this.eludomIsToggledMethod = isToggled;
                        Method onTick = cls.getDeclaredMethod("onTick");
                        forceAccessible(onTick);
                        this.eludomOnTickMethod = onTick;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            log(writer, "Notice preparing mod state: " + t);
        }
    }

    private void injectGuiOverlays(PrintWriter writer, ClassLoader cl, List<Class<?>> allClasses) {
        try {
            Class<?> eventCls = Class.forName("net.minecraftforge.client.event.AddGuiOverlayLayersEvent", false, cl);
            Class<?> layeredDrawCls = Class.forName("net.minecraft.client.gui.LayeredDraw", false, cl);
            Class<?> layerCls = Class.forName("net.minecraft.client.gui.LayeredDraw$Layer", false, cl);

            Constructor<?> ldCtor = layeredDrawCls.getDeclaredConstructor();
            forceAccessible(ldCtor);
            Object capturedLayeredDraw = ldCtor.newInstance();

            Object addEvent = unsafeAllocateInstance.invoke(theUnsafe, eventCls);
            Field directLdField = findFieldByType(eventCls, layeredDrawCls);
            if (directLdField != null) {
                setFieldValue(addEvent, directLdField, capturedLayeredDraw);
            }

            Object forgeLayeredDraw = null;
            try {
                Class<?> forgeLayeredDrawCls = Class.forName("net.minecraftforge.client.gui.overlay.ForgeLayeredDraw",
                        false, cl);
                forgeLayeredDraw = unsafeAllocateInstance.invoke(theUnsafe, forgeLayeredDrawCls);
                Class<?> curr = forgeLayeredDrawCls;
                while (curr != null && curr != Object.class) {
                    for (Field f : curr.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()))
                            continue;
                        if (layeredDrawCls.isAssignableFrom(f.getType())) {
                            setFieldValue(forgeLayeredDraw, f, capturedLayeredDraw);
                        } else if (List.class.isAssignableFrom(f.getType())) {
                            setFieldValue(forgeLayeredDraw, f, new ArrayList<Object>());
                        } else if (Map.class.isAssignableFrom(f.getType())) {
                            setFieldValue(forgeLayeredDraw, f, new LinkedHashMap<Object, Object>());
                        }
                    }
                    curr = curr.getSuperclass();
                }
                Field fldField = findFieldByType(eventCls, forgeLayeredDrawCls);
                if (fldField != null) {
                    setFieldValue(addEvent, fldField, forgeLayeredDraw);
                }
            } catch (ClassNotFoundException ignored) {
                // Forge versions without ForgeLayeredDraw
            }

            for (Class<?> cls : allClasses) {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == eventCls && Modifier.isStatic(m.getModifiers())) {
                        forceAccessible(m);
                        log(writer, "Invoking overlay handler " + cls.getName() + "." + m.getName()
                                + "(AddGuiOverlayLayersEvent)");
                        m.invoke(null, addEvent);
                    }
                }
            }

            Field layersListField = findFieldByType(layeredDrawCls, List.class);
            List<Object> capturedLayers = new ArrayList<Object>();
            if (layersListField != null) {
                List<?> directList = (List<?>) layersListField.get(capturedLayeredDraw);
                if (directList != null) {
                    capturedLayers.addAll(directList);
                }
                if (forgeLayeredDraw != null) {
                    List<?> fldDirectList = (List<?>) layersListField.get(forgeLayeredDraw);
                    if (fldDirectList != null) {
                        for (Object item : fldDirectList) {
                            if (layerCls.isInstance(item) && !capturedLayers.contains(item)) {
                                capturedLayers.add(item);
                            }
                        }
                    }
                }
            }
            if (forgeLayeredDraw != null) {
                for (Field f : forgeLayeredDraw.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()))
                        continue;
                    forceAccessible(f);
                    Object val = f.get(forgeLayeredDraw);
                    if (val instanceof Map) {
                        for (Object mapVal : ((Map<?, ?>) val).values()) {
                            if (layerCls.isInstance(mapVal) && !capturedLayers.contains(mapVal)) {
                                capturedLayers.add(mapVal);
                            }
                        }
                    }
                }
            }

            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, cl);
            Class<?> guiCls = Class.forName("net.minecraft.client.gui.Gui", false, cl);
            Object mc = null;
            for (Method m : mcCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                        && m.getReturnType() == mcCls) {
                    forceAccessible(m);
                    mc = m.invoke(null);
                    if (mc != null)
                        break;
                }
            }
            if (mc != null) {
                try {
                    Class<?> optionsCls = Class.forName("net.minecraft.client.Options", false, cl);
                    Field optionsField = findFieldByType(mcCls, optionsCls);
                    if (optionsField != null) {
                        Object options = optionsField.get(mc);
                        if (options != null) {
                            Method gammaMethod = optionsCls.getDeclaredMethod("gamma");
                            forceAccessible(gammaMethod);
                            Object gammaOpt = gammaMethod.invoke(options);
                            if (gammaOpt != null) {
                                this.gammaOptionInstance = gammaOpt;
                                for (Field f : gammaOpt.getClass().getDeclaredFields()) {
                                    if (!Modifier.isStatic(f.getModifiers())
                                            && !Modifier.isFinal(f.getModifiers())
                                            && f.getType() == Object.class) {
                                        forceAccessible(f);
                                        this.optionInstanceValueField = f;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                Field guiField = findFieldByType(mcCls, guiCls);
                if (guiField != null) {
                    Object gui = guiField.get(mc);
                    if (gui != null) {
                        Field liveLdField = findFieldByType(guiCls, layeredDrawCls);
                        if (liveLdField != null) {
                            Object liveLayeredDraw = liveLdField.get(gui);
                            if (liveLayeredDraw != null && layersListField != null) {
                                List<Object> liveList = (List<Object>) layersListField.get(liveLayeredDraw);
                                if (liveList != null) {
                                    for (int i = liveList.size() - 1; i >= 0; i--) {
                                        Object existing = liveList.get(i);
                                        if (existing == null)
                                            continue;
                                        String cName = existing.getClass().getName();
                                        if (cName.contains("emerpus") || (Proxy.isProxyClass(existing.getClass())
                                                && "CleanJarToDLL_Proxy".equals(existing.toString()))) {
                                            liveList.remove(i);
                                        }
                                    }
                                    Object tickProxyLayer = Proxy.newProxyInstance(cl, new Class<?>[] { layerCls }, this);
                                    liveList.add(tickProxyLayer);
                                    for (Object layer : capturedLayers) {
                                        liveList.add(layer);
                                    }
                                    log(writer, "Injected " + capturedLayers.size()
                                            + " HUD overlay layer(s) + tick/gamma dispatcher into live Gui.layers (total layers: "
                                            + liveList.size() + ")!");
                                }
                            }
                        }
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log(writer, "Warning injecting GUI overlays: " + t);
            t.printStackTrace(writer);
            writer.flush();
        }
    }

    private void executeModInitializationOnTargetThread() {
        PrintWriter writer = this.activeWriter;
        ClassLoader cl = this.targetClassLoader;
        try {
            Thread.currentThread().setContextClassLoader(cl);
            Object modEventBus = setupModernModLoadingContext(writer, cl);

            Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, Integer.TYPE, Integer.TYPE, ProtectionDomain.class);
            forceAccessible(defineClassMethod);

            Object classTransformer = null;
            Method transformMethod = null;
            try {
                Field ctField = findFieldByName(cl.getClass(), "classTransformer");
                if (ctField != null) {
                    classTransformer = ctField.get(cl);
                    if (classTransformer != null) {
                        transformMethod = classTransformer.getClass().getDeclaredMethod("transform", byte[].class,
                                String.class, String.class);
                        forceAccessible(transformMethod);
                        log(writer, "Hooked ModLauncher ClassTransformer for EventBusEngine bytecode transformation");
                    }
                }
            } catch (Throwable ignored) {
            }

            ProtectionDomain pd = cl.getClass().getProtectionDomain();
            Map<String, byte[]> pendingBytes = new LinkedHashMap<String, byte[]>();
            Map<String, String[][]> parsedHeaders = new HashMap<String, String[][]>();
            Map<String, Class<?>> definedClasses = new LinkedHashMap<String, Class<?>>();
            Set<String> visiting = new HashSet<String>();
            Set<String> packageNames = new HashSet<String>();

            log(writer, "Parsing and defining " + classes.length + " classes...");

            for (byte[] classData : classes) {
                if (classData == null)
                    continue;
                String[][] header = parseClassHeader(classData);
                String internalName = header[0][0];
                pendingBytes.put(internalName, classData);
                parsedHeaders.put(internalName, header);
                int lastSlash = internalName.lastIndexOf('/');
                if (lastSlash > 0) {
                    packageNames.add(internalName.substring(0, lastSlash).replace('/', '.'));
                }
            }

            for (String internalName : pendingBytes.keySet()) {
                defineClassRecursive(writer, cl, defineClassMethod, classTransformer, transformMethod, pd, internalName,
                        pendingBytes, parsedHeaders, definedClasses, visiting);
            }

            List<Class<?>> allDefinedClasses = new ArrayList<Class<?>>(definedClasses.values());
            log(writer, "Successfully defined " + allDefinedClasses.size() + " classes!");

            registerPackagesInParentModuleClassLoaders(writer, cl, packageNames);
            ensureEventBusFactoryWorks(writer, cl, allDefinedClasses);

            Class<?> modAnnotation = tryGetClass(cl, "net.minecraftforge.fml.common.Mod", "cpw.mods.fml.common.Mod");
            Class<?> forgeEventHandlerAnnotation = null;
            Class<?> fmlInitializationEventClass = null;
            Class<?> fmlPreInitializationEventClass = null;
            try {
                forgeEventHandlerAnnotation = tryGetClass(cl, "net.minecraftforge.fml.common.Mod$EventHandler",
                        "cpw.mods.fml.common.Mod$EventHandler");
                fmlPreInitializationEventClass = tryGetClass(cl,
                        "net.minecraftforge.fml.common.event.FMLPreInitializationEvent",
                        "cpw.mods.fml.common.event.FMLPreInitializationEvent");
                fmlInitializationEventClass = tryGetClass(cl,
                        "net.minecraftforge.fml.common.event.FMLInitializationEvent",
                        "cpw.mods.fml.common.event.FMLInitializationEvent");
            } catch (Exception e) {
                log(writer, "Legacy @Mod.EventHandler not present (modern FML detected)");
            }

            ArrayList<Object[]> mods = new ArrayList<Object[]>();
            for (Class<?> tClass : allDefinedClasses) {
                if (tClass == null || tClass.getAnnotation((Class) modAnnotation) == null) {
                    continue;
                }
                Object[] mod = new Object[3];
                mod[0] = tClass;
                ArrayList<Method> fmlPreInitMethods = new ArrayList<Method>();
                ArrayList<Method> fmlInitMethods = new ArrayList<Method>();
                if (forgeEventHandlerAnnotation != null) {
                    for (Method m : tClass.getDeclaredMethods()) {
                        if (m.getAnnotation((Class) forgeEventHandlerAnnotation) != null
                                && m.getParameterCount() == 1) {
                            Class<?> paramType = m.getParameterTypes()[0];
                            if (paramType == fmlInitializationEventClass) {
                                forceAccessible(m);
                                fmlInitMethods.add(m);
                            } else if (paramType == fmlPreInitializationEventClass) {
                                forceAccessible(m);
                                fmlPreInitMethods.add(m);
                            }
                        }
                    }
                }
                mod[1] = fmlPreInitMethods;
                mod[2] = fmlInitMethods;
                mods.add(mod);
            }

            cleanupAndPrepareModState(writer, cl, allDefinedClasses);

            for (Object[] mod : mods) {
                Class<?> modClass = (Class<?>) mod[0];
                ArrayList<Method> fmlPreInitMethods = (ArrayList<Method>) mod[1];
                ArrayList<Method> fmlInitMethods = (ArrayList<Method>) mod[2];

                Object modInstance = instantiateMod(writer, modClass, modEventBus);

                for (Method preInitMethod : fmlPreInitMethods) {
                    log(writer, "Invoking legacy preInit: " + preInitMethod);
                    preInitMethod.invoke(modInstance, new Object[] { null });
                }

                for (Method initMethod : fmlInitMethods) {
                    log(writer, "Invoking legacy init: " + initMethod);
                    initMethod.invoke(modInstance, new Object[] { null });
                }
            }

            registerEventBusSubscribers(writer, cl, modEventBus, allDefinedClasses);
            fireLifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent");
            fireLifecycleEvent(writer, cl, allDefinedClasses,
                    "net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent");
            injectGuiOverlays(writer, cl, allDefinedClasses);

            log(writer, "Successfully injected!");
        } catch (Throwable t) {
            log(writer, "Error during mod initialization: " + t);
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
            log(writer, "Starting CleanJarToDLL Injector!");
            try {
                initUnsafe(writer);
                unlockModules(writer, null);

                ClassLoader cl = findForgeClassLoader(writer);
                if (cl == null) {
                    throw new Exception("Could not locate Forge ClassLoader");
                }
                this.targetClassLoader = cl;
                this.setContextClassLoader(cl);
                unlockModules(writer, cl);

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
                                if (mc != null)
                                    break;
                            }
                        }
                        if (mc instanceof Executor) {
                            log(writer, "Scheduling mod initialization on Minecraft Render thread...");
                            ((Executor) mc).execute(this);
                            scheduledOnRenderThread = true;
                            synchronized (this.renderThreadLock) {
                                if (!this.renderThreadDone) {
                                    this.renderThreadLock.wait(4000L);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                if (!scheduledOnRenderThread || !this.renderThreadDone) {
                    log(writer, "Running mod initialization directly on injector thread...");
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
