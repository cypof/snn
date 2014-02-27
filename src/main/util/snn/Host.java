package snn;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Set;

public class Host implements Serializable {
  public static final String SSH_OPTS;

  static {
    SSH_OPTS = "" //
        + " -o UserKnownHostsFile=/dev/null" //
        + " -o StrictHostKeyChecking=no" //
        + " -o LogLevel=quiet" //
        + " -o ServerAliveInterval=15" //
        + " -o ServerAliveCountMax=3";
  }

  private final String _address, _user, _key;

  public Host(String addr) {
    this(addr, null);
  }

  public Host(String addr, String user) {
    this(addr, user, null);
  }

  public Host(String addr, String user, String key) {
    _address = addr;
    _user = user != null ? user : System.getProperty("user.name");
    _key = key;
  }

  public String address() {
    return _address;
  }

  public String user() {
    return _user;
  }

  public String key() {
    return _key;
  }

  public String ssh() {
    String k = "";
    if( _key != null )
      k = " -i " + _key;
    return "ssh -l " + _user + " -A" + k + SSH_OPTS;
  }

  public void rsync(String folder, Set<String> includes, Set<String> excludes, boolean delete) {
    Process process = null;
    try {
      ArrayList<String> args = new ArrayList<String>();
      args.add("rsync");
      args.add("-vrzute");
      args.add(ssh());
      args.add("--chmod=u=rwx");

      for( String s : includes )
        args.add(new File(s).getCanonicalPath());

      if( excludes != null ) {
        // --exclude seems ignored on Linux (?) so use --exclude-from
        File file = Utils.writeFile(Utils.join('\n', excludes));
        args.add("--exclude-from");
        args.add(file.getCanonicalPath());
      }

      if( delete )
        args.add("--delete");

      args.add(_address + ":" + "/home/" + _user + "/" + folder);
      ProcessBuilder builder = new ProcessBuilder(args);
      process = builder.start();
      String log = "rsync " + VM.localIP4() + " -> " + _address;
      VM.inheritIO(process, Utils.padRight(log + ": ", 24));
      process.waitFor();
    } catch( Exception ex ) {
      throw new RuntimeException(ex);
    } finally {
      if( process != null ) {
        try {
          process.destroy();
        } catch( Exception _ ) {
          // Ignore
        }
      }
    }
  }

  public static void rsync(final Host[] hosts, final String folder, final Set<String> includes,
      final Set<String> excludes, final boolean delete) {
    ArrayList<Thread> threads = new ArrayList<Thread>();

    for( int i = 0; i < hosts.length; i++ ) {
      final int i_ = i;
      Thread t = new Thread() {
        @Override
        public void run() {
          hosts[i_].rsync(folder, includes, excludes, delete);
        }
      };
      t.setDaemon(true);
      t.start();
      threads.add(t);
    }

    for( Thread t : threads ) {
      try {
        t.join();
      } catch( InterruptedException e ) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public String toString() {
    return "Host " + _address;
  }
}
