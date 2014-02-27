package snn;

import org.junit.Ignore;
import org.junit.Test;

import snn.NeuralNet.Errors;

@Ignore
public class NeuralNetSpiralsTest {
  @Test
  public void run() throws Exception {
//    File file = new File("data/two_spiral.data");
    Layer[] ls = new Layer[3];
//    ls[0] = input;
//    ls[1] = new Layer.Tanh(50);
//    ls[1].rate = .005f;
//    ls[2] = output;
    ls[2].rate = .0005f;
//    for( int i = 0; i < ls.length; i++ )
//      ls[i].init(ls, i);

//    for( ;; ) {
    Trainer.Threaded trainer = new Trainer.Threaded(ls, 1000);
    trainer.start();

//      NeuralNet.Error train = NeuralNet.eval(ls, (int) frame.numRows(), null);
//      Log.info("SNN and Reference equal, train: " + train);
//    }

    long start = System.nanoTime();
    for( ;; ) {
      try {
        Thread.sleep(2000);
      } catch( InterruptedException e ) {
        throw new RuntimeException(e);
      }

      double time = (System.nanoTime() - start) / 1e9;
      long processed = trainer.processed();
      int ps = (int) (processed / time);
      String text = (int) time + "s, " + processed + " samples (" + (ps) + "/s) ";

      Errors error = null;//NeuralNet.eval(ls, data, labels, 0, null);
      text += "train: " + error;
      text += ", rates: ";
      for( int i = 1; i < ls.length; i++ )
        text += String.format("%.3g", ls[i].rate(processed)) + ", ";

      System.out.println(text);
    }

//    for( int i = 0; i < ls.length; i++ )
//      ls[i].close();
//    UKV.remove(key);
  }
}
