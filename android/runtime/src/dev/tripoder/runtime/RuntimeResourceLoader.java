package dev.tripoder.runtime;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** The guest's classes come from memory, which leaves its class loader without any resources. Real applications read META-INF/services files and similar entries through it (ServiceLoader, kotlinx.coroutines, ...). This parent serves exactly the entries of the verified APK, ahead of the platform parent, and never loads a class itself. */
final class RuntimeResourceLoader extends ClassLoader {
    private static final class FastJarURLConnection extends JarURLConnection {
        private final JarFile jarFile;
        private JarEntry jarEntry;

        FastJarURLConnection(URL url, JarFile jarFile) throws MalformedURLException {
            super(url);
            this.jarFile = jarFile;
        }

        @Override public void connect() throws IOException {
            if (!connected) {
                jarEntry = jarFile.getJarEntry(getEntryName());
                if (jarEntry == null) throw new FileNotFoundException("Entry not found: " + getEntryName());
                connected = true;
            }
        }

        @Override public JarFile getJarFile() throws IOException {
            connect();
            return jarFile;
        }

        @Override public JarEntry getJarEntry() throws IOException {
            connect();
            return jarEntry;
        }

        @Override public InputStream getInputStream() throws IOException {
            connect();
            return jarFile.getInputStream(jarEntry);
        }

        @Override public int getContentLength() {
            try {
                connect();
                return (int) jarEntry.getSize();
            } catch (IOException ignored) {
                return -1;
            }
        }

        @Override public long getContentLengthLong() {
            try {
                connect();
                return jarEntry.getSize();
            } catch (IOException ignored) {
                return -1;
            }
        }

        @Override public String getContentType() {
            String type = guessContentTypeFromName(getEntryName());
            return type != null ? type : "content/unknown";
        }

        @Override public String getHeaderField(String name) {
            if ("content-length".equalsIgnoreCase(name)) return String.valueOf(getContentLengthLong());
            if ("content-type".equalsIgnoreCase(name)) return getContentType();
            return super.getHeaderField(name);
        }
    }

    private static final class FastJarHandler extends URLStreamHandler {
        private final File apk;
        private final String fileUri;
        private JarFile jarFile;

        FastJarHandler(File apk) {
            this.apk = apk;
            this.fileUri = apk.toURI().toString();
        }

        private synchronized JarFile jarFile() throws IOException {
            if (jarFile == null) jarFile = new JarFile(apk, false);
            return jarFile;
        }

        boolean hasEntry(String entryName) {
            try {
                return jarFile().getEntry(entryName) != null;
            } catch (Exception ignored) {
                return false;
            }
        }

        URL getEntryUrl(String entryName, String rawPath) {
            try {
                return new URL("jar", null, -1, fileUri + "!/" + rawPath, this);
            } catch (Exception ignored) {}
            return null;
        }

        @Override protected URLConnection openConnection(URL url) throws IOException {
            return new FastJarURLConnection(url, jarFile());
        }
    }

    private static final class FastClassNotFoundException extends ClassNotFoundException {
        FastClassNotFoundException(String name) { super(name); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    private static boolean isPlatformOrHost(String name) {
        if (name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        switch (first) {
            case 'j':
                return name.startsWith("java.") || name.startsWith("javax.");
            case 'a':
                return name.startsWith("android.") && !name.startsWith("android.support.");
            case 'c':
                if (name.startsWith("com.netease.")) return false;
                return name.startsWith("com.android.") || name.startsWith("com.miui.") || name.startsWith("com.huawei.");
            case 'd':
                return name.startsWith("dalvik.") || name.startsWith("dev.tripoder.");
            case 'l':
                return name.startsWith("libcore.");
            case 's':
                return name.startsWith("sun.");
            case 'o':
                return name.startsWith("org.apache.http.")
                        || name.startsWith("org.w3c.dom.")
                        || name.startsWith("org.xml.")
                        || name.startsWith("org.xmlpull.")
                        || name.startsWith("org.json.")
                        || name.startsWith("org.conscrypt.")
                        || name.startsWith("org.bouncycastle.");
            case 'm':
                return name.startsWith("miui.");
            case 'h':
                return name.startsWith("huawei.");
            default:
                return false;
        }
    }

    private final List<File> apks;
    private final FastJarHandler[] handlers;

    RuntimeResourceLoader(ClassLoader parent, File apk) { this(parent, List.of(apk)); }
    RuntimeResourceLoader(ClassLoader parent, List<File> apks) {
        super(parent);
        this.apks = List.copyOf(apks);
        this.handlers = new FastJarHandler[this.apks.size()];
        for (int i = 0; i < this.apks.size(); i++) {
            this.handlers[i] = new FastJarHandler(this.apks.get(i));
        }
    }

    private static boolean validEntryName(String name) {
        return name != null && !name.isEmpty() && !name.startsWith("/") && !name.contains("..") && !name.contains("\\") && name.indexOf('\0') < 0 && name.length() <= 1024;
    }

    private URL own(String name) {
        if (!validEntryName(name)) return null;
        for (FastJarHandler handler : handlers) {
            if (handler.hasEntry(name)) {
                String rawPath;
                try {
                    rawPath = new URI(null, null, name, null).getRawPath();
                } catch (Exception e) {
                    return null;
                }
                return handler.getEntryUrl(name, rawPath);
            }
        }
        return null;
    }

    private List<URL> ownAll(String name) {
        if (!validEntryName(name)) return List.of();
        List<URL> found = null;
        String rawPath = null;
        for (FastJarHandler handler : handlers) {
            if (handler.hasEntry(name)) {
                if (rawPath == null) {
                    try {
                        rawPath = new URI(null, null, name, null).getRawPath();
                    } catch (Exception e) {
                        return List.of();
                    }
                }
                URL url = handler.getEntryUrl(name, rawPath);
                if (url != null) {
                    if (found == null) found = new ArrayList<>();
                    found.add(url);
                }
            }
        }
        return found != null ? found : List.of();
    }

    private ClassLoader legacyHttpLoader;
    private synchronized ClassLoader legacyHttp() {
        if (legacyHttpLoader == null) {
            File legacyJar = new File("/system/framework/org.apache.http.legacy.jar");
            if (legacyJar.isFile()) legacyHttpLoader = new dalvik.system.PathClassLoader(legacyJar.getPath(), getParent());
        }
        return legacyHttpLoader;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isPlatformOrHost(name)) {
            throw new FastClassNotFoundException(name);
        }
        try { return super.loadClass(name, resolve); }
        catch (ClassNotFoundException error) {
            if (name != null && (name.startsWith("org.apache.http.") || name.startsWith("android.net.http.") || name.startsWith("com.android.internal.http."))) {
                ClassLoader legacy = legacyHttp();
                if (legacy != null) return legacy.loadClass(name);
            }
            throw new FastClassNotFoundException(name);
        }
    }

    @Override public URL getResource(String name) {
        URL found = own(name);
        return found != null ? found : super.getResource(name);
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>(ownAll(name));
        Enumeration<URL> inherited = super.getResources(name);
        while (inherited.hasMoreElements()) all.add(inherited.nextElement());
        return Collections.enumeration(all);
    }

    @Override protected URL findResource(String name) { return own(name); }
    @Override protected Enumeration<URL> findResources(String name) { return Collections.enumeration(ownAll(name)); }
}
