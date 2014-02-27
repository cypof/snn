package samples.launchers;

import java.io.File;
import java.util.*;

import snn.*;
import snn.VM.Watchdog;

public class RemoteCluster {
  /**
   * Starts EC2 machines and builds a cluster.
   */
  public static void launchEC2(Runnable... runs) throws Exception {
    EC2 ec2 = new EC2();
    ec2.boxes = runs.length;
    ec2.resize();

    deployMaster(new Host(ec2.publicIPs.get(0)));

    SSH ssh = new SSH();
    ssh._runs = runs;
    ssh._hosts = new Host[ec2.privateIPs.size()];
    for( int i = 0; i < ec2.privateIPs.size(); i++ )
      ssh._hosts[i] = new Host(ec2.privateIPs.get(i));
    ssh.start();
  }

  /**
   * The current user is assumed to have ssh access (key-pair, no password) to the remote machines.
   */
  public static void launch(Runnable[] runs, String... ips) throws Exception {
    Host[] hosts = new Host[ips.length];
    for( int i = 0; i < ips.length; i++ )
      hosts[i] = new Host(ips[i]);
    launch(runs, hosts);
  }

  public static void launch(Runnable[] runs, Host... hosts) throws Exception {
    deployMaster(hosts[0]);

    SSH ssh = new SSH();
    ssh._runs = runs;
    ssh._hosts = hosts;
    ssh.start();
  }

  static void deployMaster(Host host) throws Exception {
    Set<String> rsyncIncludes = new HashSet<String>();
    rsyncIncludes.add("target");
    rsyncIncludes.add("data");
    if( MixedCluster.JRE != null )
      rsyncIncludes.add(MixedCluster.JRE);
    host.rsync(MixedCluster.DIR, rsyncIncludes, null, false);
  }

  static class SSH extends Watchdog implements Runnable {
    Runnable[] _runs;
    Host[] _hosts;

    @Override
    protected void exec() {
      String key = _hosts[0].key() != null ? _hosts[0].key() : "";
      String s = "ssh-agent sh -c \"ssh-add " + key + "; ssh -l " + _hosts[0].user() + " -A" + Host.SSH_OPTS;
      // Port forwarding?
      //s += " -L 8888:127.0.0.1:8888";
      String cmd = MixedCluster.java() + " " + Runner.class.getName() + " " + Utils.serializeString(this);
      s += " " + _hosts[0].address() + " '" + cmd + "'\"";
      ArrayList<String> list = new ArrayList<String>();
      // Have to copy to file for cygwin, but works also on -nix
      File sh = Utils.writeFile(s);
      File onWindows = new File("C:/cygwin/bin/bash.exe");
      if( onWindows.exists() ) {
        list.add(onWindows.getPath());
        list.add("--login");
      } else
        list.add("bash");
      list.add(sh.getAbsolutePath());
      exec(list);
    }

    @Override
    public void run() {
      VM.exitWithParent();
      Host[] slaves = Utils.remove(_hosts, 0);
      MixedCluster.launchRemote(_runs, true, slaves);
    }
  }
}
