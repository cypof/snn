package snn;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;

/**
 * Emulates a separate process using a class loader. Running multiple cluster nodes in the same
 * process can simplify distributed debugging, e.g. by allowing cluster-wide breakpoints etc.
 */
public class ClassLoaderSandbox extends Thread {
  private final URL[] _classpath;
  private String _serialized;
  private ClassLoader _initialClassLoader, _classLoader;

  public ClassLoaderSandbox() {
    super("NodeCL");
    _classpath = getClassPath();
    _initialClassLoader = Thread.currentThread().getContextClassLoader();
    _classLoader = new URLClassLoader(_classpath, null);
  }

  public void start(Runnable main) {
    _serialized = Utils.serializeString((Serializable) main);
    start();
  }

  static URL[] getClassPath() {
    String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
    try {
      final List<URL> list = new ArrayList<URL>();
      if( classpath != null ) {
        for( final String element : classpath ) {
          list.addAll(getDirectoryClassPath(element));
          list.add(new File(element).toURI().toURL());
        }
      }
      return list.toArray(new URL[list.size()]);
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void run() {
    assert Thread.currentThread().getContextClassLoader() == _initialClassLoader;
    Thread.currentThread().setContextClassLoader(_classLoader);

    try {
      Class<?> c = _classLoader.loadClass(Runner.class.getName());
      Method method = c.getMethod("main", String[].class);
      method.setAccessible(true);
      method.invoke(null, (Object) new String[] { _serialized });
    } catch( Exception e ) {
      throw new RuntimeException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(_initialClassLoader);
    }
  }

  private static List<URL> getDirectoryClassPath(String aDir) {
    try {
      final List<URL> list = new LinkedList<URL>();
      final File dir = new File(aDir);
      final URL directoryURL = dir.toURI().toURL();
      final String[] children = dir.list();

      if( children != null ) {
        for( final String element : children ) {
          if( element.endsWith(".jar") ) {
            final URL url = new URL(directoryURL, URLEncoder.encode(element, "UTF-8"));
            list.add(url);
          }
        }
      }
      return list;
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }
}
