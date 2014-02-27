package samples.launchers;

import java.io.File;
import java.io.Serializable;
import java.util.*;

import snn.*;
import snn.VM.Watchdog;

/**
 * Local machine is part of a distributed cluster.
 */
public class MixedCluster {
  // Code and data will be rsync'ed to this folder in the current user's home on remote machines
  static final String DIR = "ssn_rsync";

  // To avoid configuring remote machines, a JVM can also be sent. By default, decompress
  // the Oracle Linux x64 JDK to a local folder and point this path to it.
  static final String JRE = System.getProperty("user.home") + "/libs/jdk/jre";

  static final String JVM_ARGS = "-ea -Xmx60G";

  /**
   * The current user is assumed to have ssh access (key-pair, no password) to the remote machines.
   */
  public static void launch(Runnable[] runs, String... ips) throws Exception {
    Host[] hosts = new Host[ips.length];
    for( int i = 0; i < ips.length; i++ )
      hosts[i] = new Host(ips[i]);
    launch(runs, hosts);
  }

  public static void launch(Runnable[] runs, Host... hosts) {
    VM.getDependencies();
    launchRemote(runs, false, hosts);
  }

  static void launchRemote(Runnable[] runs, boolean remote, Host... hosts) {
    Log.init();

    ArrayList<SSH> sshs = new ArrayList<SSH>();
    for( int i = 1; i < runs.length; i++ ) {
      SSH ssh = new SSH();
      ssh._host = hosts[i - 1];
      ssh._serialized = Utils.serializeString((Serializable) runs[i]);
      sshs.add(ssh);
    }

    Set<String> rsyncIncludes = new HashSet<String>();
    rsyncIncludes.add("target");
    rsyncIncludes.add("data");
    if( JRE != null )
      rsyncIncludes.add(remote ? new File(JRE).getName() : JRE);
    Host.rsync(hosts, DIR, rsyncIncludes, null, false);

    for( SSH ssh : sshs )
      ssh.start();

    runs[0].run();
  }

  static class SSH extends Watchdog {
    Host _host;
    String _serialized;

    @Override
    protected void exec() {
      ArrayList<String> list = new ArrayList<String>();
      list.addAll(Arrays.asList(_host.ssh().split(" ")));
      list.add(_host.address());
      list.add(java() + " " + Runner.class.getName() + " " + _serialized);
      exec(list);
    }
  }

  static String java() {
    String java = JRE == null ? "java" : new File(JRE).getName() + "/bin/java";
    return "cd " + DIR + ";" + java + " " + JVM_ARGS + " -cp target/lib/*:target/classes";
  }
}
