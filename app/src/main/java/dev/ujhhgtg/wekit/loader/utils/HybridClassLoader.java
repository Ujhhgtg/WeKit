package dev.ujhhgtg.wekit.loader.utils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Uses only platform classes so resolving the payload's own runtime cannot reenter this loader. */
public final class HybridClassLoader extends ClassLoader {
  public static final HybridClassLoader INSTANCE = new HybridClassLoader();

  private static final String PREFIX_HOST = "HOST.";
  private static final String[] SHARED_PREFIXES = {
    "dev.ujhhgtg.wekit.loader.abc.",
    "dev.ujhhgtg.wekit.loader.api.",
    "dev.ujhhgtg.wekit.bootstrap.",
    "dev.ujhhgtg.reflekt.",
    "kotlin."
  };

  public ClassLoader moduleParentClassLoader;
  public ClassLoader moduleClassLoader;
  public ClassLoader hostClassLoader;

  private final ThreadLocal<Set<ClassLoader>> resolvingResourceLoaders =
      ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
  private final Method moduleFindClassMethod;

  private HybridClassLoader() {
    super(Object.class.getClassLoader());
    try {
      moduleFindClassMethod = ClassLoader.class.getDeclaredMethod("findClass", String.class);
      moduleFindClassMethod.setAccessible(true);
    } catch (NoSuchMethodException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    if (name.startsWith(PREFIX_HOST)) {
      if (hostClassLoader != null) {
        return hostClassLoader.loadClass(name.substring(PREFIX_HOST.length()));
      }
      throw new ClassNotFoundException("hostClassLoader is not initialized. Class: " + name);
    }

    for (String prefix : SHARED_PREFIXES) {
      if (name.startsWith(prefix)) return moduleParentClassLoader.loadClass(name);
    }

    if (moduleClassLoader != null) {
      try {
        return findModuleOwnClass(name);
      } catch (ClassNotFoundException ignored) {
      }
    }

    if (moduleParentClassLoader != null) {
      try {
        return moduleParentClassLoader.loadClass(name);
      } catch (ClassNotFoundException ignored) {
      }
    }

    if (hostClassLoader != null) {
      try {
        return hostClassLoader.loadClass(name);
      } catch (ClassNotFoundException ignored) {
      }
    }

    throw new ClassNotFoundException(name);
  }

  @Override
  protected URL findResource(String name) {
    Set<ClassLoader> resolving = resolvingResourceLoaders.get();
    assert resolving != null;
    try {
      for (ClassLoader loader : resourceLoaders()) {
        if (!resolving.add(loader)) continue;
        try {
          URL resource = loader.getResource(name);
          if (resource != null) return resource;
        } finally {
          resolving.remove(loader);
        }
      }
      return null;
    } finally {
      if (resolving.isEmpty()) resolvingResourceLoaders.remove();
    }
  }

  @Override
  protected Enumeration<URL> findResources(String name) throws IOException {
    Map<String, URL> urls = new LinkedHashMap<>();
    Set<ClassLoader> resolving = resolvingResourceLoaders.get();
    assert resolving != null;
    try {
      for (ClassLoader loader : resourceLoaders()) {
        if (!resolving.add(loader)) continue;
        try {
          Enumeration<URL> resources = loader.getResources(name);
          while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            urls.putIfAbsent(resource.toExternalForm(), resource);
          }
        } finally {
          resolving.remove(loader);
        }
      }
    } finally {
      if (resolving.isEmpty()) resolvingResourceLoaders.remove();
    }
    return Collections.enumeration(urls.values());
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    Enumeration<URL> resources = super.getResources(name);
    Map<String, URL> urls = new LinkedHashMap<>();
    while (resources.hasMoreElements()) {
      URL resource = resources.nextElement();
      urls.putIfAbsent(resource.toExternalForm(), resource);
    }
    return Collections.enumeration(urls.values());
  }

  private List<ClassLoader> resourceLoaders() {
    List<ClassLoader> loaders = new ArrayList<>(2);
    if (moduleParentClassLoader != null) loaders.add(moduleParentClassLoader);
    if (hostClassLoader != null) loaders.add(hostClassLoader);
    return loaders;
  }

  private Class<?> findModuleOwnClass(String name) throws ClassNotFoundException {
    try {
      return (Class<?>) moduleFindClassMethod.invoke(moduleClassLoader, name);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof ClassNotFoundException) throw (ClassNotFoundException) cause;
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }
}
