package samples.launchers;

import snn.VM;

public class LocalCluster {
  public static void launch(Runnable... runs) throws Exception {
    for( int i = 1; i < runs.length; i++ ) {
      VM vm = new VM(runs[i]);
      vm.start();
    }
    runs[0].run();
  }
}
