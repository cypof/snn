package snn;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.*;

import org.jboss.shrinkwrap.resolver.api.maven.*;

/**
 * Executes code in a separate VM.
 */
public class VM implements Serializable {
  transient final ArrayList<String> _args;
  transient Process _process;
  transient File _out, _err;
  static String _local;

  public VM(Runnable run) {
    this(cloneJavaArgs(), Runner.class, Utils.serializeString((Serializable) run));
  }

  public VM(String jvmArgs, Class main, String appArgs) {
    _args = new ArrayList<String>();
    _args.add(System.getProperty("java.home") + "/bin/java");
    _args.add("-cp");
    _args.add(System.getProperty("java.class.path"));
    if( jvmArgs != null )
      _args.addAll(Arrays.asList(jvmArgs.split(" ")));
    _args.add(main.getName());
    if( appArgs != null )
      _args.addAll(Arrays.asList(appArgs.split(" ")));
  }

  public Process process() {
    return _process;
  }

  public void persistIO(String out, String err) throws IOException {
    _out = new File(out);
    _err = new File(err);
  }

  public void start() {
    exec(_args);
  }

  protected void exec(ArrayList<String> list) {
    ProcessBuilder builder = new ProcessBuilder(list);
    try {
      _process = builder.start();
      if( _out != null )
        persistIO(_process, _out, _err);
      else
        inheritIO(_process, null);
    } catch( IOException e ) {
      throw new RuntimeException(e);
    }
  }

  public boolean isAlive() {
    try {
      _process.exitValue();
      return false;
    } catch( IllegalThreadStateException _ ) {
      return true;
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }

  public int waitFor() {
    try {
      return _process.waitFor();
    } catch( InterruptedException e ) {
      throw new RuntimeException(e);
    }
  }

  public void kill() {
    _process.destroy();
    try {
      _process.waitFor();
    } catch( InterruptedException _ ) {
      // Ignore
    }
  }

  public static void exitWithParent() {
    Thread thread = new Thread() {
      @Override
      public void run() {
        // Avoid on Windows as it exits immediately. Seems to work using Java7
        // ProcessBuilder.redirectInput, but we need to run on Java 6 for now
        if( !System.getProperty("os.name").toLowerCase().contains("win") ) {
          for( ;; ) {
            int b;
            try {
              b = System.in.read();
            } catch( Exception e ) {
              b = -1;
            }
            if( b < 0 ) {
              Log.write("Assuming parent done, exit(0)");
              System.exit(0);
            }
          }
        }
      }
    };
    thread.setDaemon(true);
    thread.start();
  }

  public static void inheritIO(Process process, final String header) {
    forward(process, header, process.getInputStream(), System.out);
    forward(process, header, process.getErrorStream(), System.err);
  }

  public static void persistIO(Process process, File out, File err) throws IOException {
    forward(process, null, process.getInputStream(), new PrintStream(out));
    forward(process, null, process.getErrorStream(), new PrintStream(err));
  }

  private static void forward(Process process, final String header, InputStream source, final PrintStream target) {
    final BufferedReader source_ = new BufferedReader(new InputStreamReader(source));
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          for( ;; ) {
            String line = source_.readLine();
            if( line == null )
              break;
            String s = header == null ? line : header + line;
            Log.unwrap(target, s);
          }
        } catch( IOException e ) {
          // Ignore, process probably done
        }
      }
    };
    thread.start();
  }

  public static String cloneJavaArgs() {
    RuntimeMXBean r = ManagementFactory.getRuntimeMXBean();
    ArrayList<String> list = new ArrayList<String>();
    for( String s : r.getInputArguments() )
      if( !s.startsWith("-agentlib") )
        if( !s.startsWith("-Xbootclasspath") )
          list.add(s);
    return Utils.join(' ', list.toArray(new String[0]));
  }

  /**
   * Copies Maven dependencies to the target folder.
   */
  public static void getDependencies() {
    MavenResolverSystem system = Maven.resolver();
    system.offline(true);
    MavenFormatStage stage = system.loadPomFromFile("pom.xml") //
        .importDependencies(ScopeType.COMPILE, ScopeType.IMPORT, ScopeType.RUNTIME) //
        .resolve().withTransitivity();
    for( File src : stage.asFile() ) {
      File dst = new File("target/lib/" + src.getName());
      if( !dst.exists() ) {
        System.out.println(src);
        dst.getParentFile().mkdirs();
        try {
          FileChannel a = FileChannel.open(src.toPath(), StandardOpenOption.READ);
          FileChannel b = FileChannel.open(dst.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          b.transferFrom(a, 0, a.size());
        } catch( IOException e ) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  public static String localIP4() {
    if( _local == null )
      _local = local();
    return _local;
  }

  private static String local() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while( interfaces.hasMoreElements() ) {
        NetworkInterface net = interfaces.nextElement();
        if( !net.isUp() || net.isLoopback() || net.isVirtual() )
          continue;
        Enumeration<InetAddress> addresses = net.getInetAddresses();
        while( addresses.hasMoreElements() ) {
          InetAddress addr = addresses.nextElement();
          if( addr.isLoopbackAddress() )
            continue;
          if( addr instanceof Inet6Address )
            continue;
          return addr.getHostAddress();
        }
      }
    } catch( Exception ex ) {
      throw new RuntimeException(ex);
    }
    throw new IllegalStateException();
  }

  /**
   * A VM whose only job is to wait for its parent to be gone, then kill its child process.
   * Otherwise every killed test leaves a bunch of orphan ssh and java processes.
   */
  public static abstract class Watchdog extends VM {
    public Watchdog() {
      super(null, Watchdog.class, null);
    }

    @Override
    public void start() {
      _args.add(Utils.serializeString(this));
      super.start();
    }

    public static void main(String[] args) throws Exception {
      exitWithParent();
      final Watchdog w = Utils.deserializeString(args[0]);
      w.exec();
      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          w._process.destroy();
        }
      });
      w._process.waitFor();
    }

    protected abstract void exec();
  }
}
