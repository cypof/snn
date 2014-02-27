package samples.launchers;

import snn.ClassLoaderSandbox;

public class InProcessCluster {
  public static void launch(Runnable... runs) throws Exception {
    for(Runnable run : runs) {
      ClassLoaderSandbox node = new ClassLoaderSandbox();
      node.start(run);
    }
  }
}
