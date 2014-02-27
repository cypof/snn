package snn;

/**
 * Executes a serialized Runnable.
 */
public class Runner {
  public static void main(String[] args) {
    Log.init();
    Runnable r = (Runnable) Utils.deserializeString(args[0]);
    r.run();
  }
}