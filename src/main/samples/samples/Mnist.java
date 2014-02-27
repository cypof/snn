package samples;

import java.io.Serializable;
import java.net.SocketAddress;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import snn.*;
import snn.Layer.Input;
import snn.Layer.Softmax;
import snn.Layer.Tanh;
import snn.NeuralNet.Errors;

/**
 * Runs ReLU + Dropout on MNIST. Please run MnistPrepare first, once, to create datasets.
 */
public class Mnist implements Runnable, Serializable {
  public static void main(String[] args) throws Exception {
    Runnable[] runs = new Runnable[] { new Mnist(null, 0) };
    samples.launchers.LocalCluster.launch(runs);

// Remote cluster
//    String[] ips = new String[] { "192.168.1.161", "192.168.1.162", "192.168.1.163", "192.168.1.164" };
//    ArrayList<SocketAddress> list = new ArrayList();
//    for( String ip : ips )
//      list.add(new InetSocketAddress(ip, 8888));
//    SocketAddress[] nodes = list.toArray(new SocketAddress[0]);
//    Runnable[] runs = new Runnable[nodes.length];
//    for( int i = 0; i < nodes.length; i++ )
//      runs[i] = new Mnist(nodes, i);
//    samples.launchers.RemoteCluster.launch(runs, ips);

// Other deployment options
//  samples.launchers.MixedCluster.launch(runs, "192.168.1.161", "192.168.1.162");
//  samples.launchers.RemoteCluster.launchEC2(runs);
//  samples.launchers.InProcessCluster.launch(runs);
  }

  public static final int PIXELS = 784;
  public static final int ROW = (PIXELS + 1) * 4;

  static FileMap _train = new FileMap("./data/mnist/train.snn");
  static FileMap _test = new FileMap("./data/mnist/test.snn");

  volatile Trainer _trainer;
  volatile Streamer _streamer;
  SocketAddress[] _nodes;
  int _local;

  Mnist(SocketAddress[] nodes, int local) {
    _nodes = nodes;
    _local = local;
  }

  public static class FileMapInput extends Input {
    FileMap _file;
    MersenneTwisterRNG _rand = new MersenneTwisterRNG();

    public FileMapInput(FileMap file) {
      units = PIXELS;
      _len = file.length() / ROW;
      _file = file;
    }

    @Override
    public Layer clone() {
      FileMapInput clone = (FileMapInput) super.clone();
      clone._rand = new MersenneTwisterRNG();
      return clone;
    }

    @Override
    protected void fprop(boolean training) {
      long offset = _pos * ROW;
      _file.get(offset, _a);
      for( int i = 0; i < _a.length; i++ )
        if( _rand.nextFloat() < .2f )
          _a[i] = 0;
    }
  }

  public static class FileMapSoftmax extends Softmax {
    private FileMap _file;

    public FileMapSoftmax(FileMap file) {
      units = 10;
      _file = file;
    }

    @Override
    protected int target() {
      long offset = pos() * ROW + PIXELS * 4;
      return (int) _file.getFloat(offset);
    }
  }

  protected Layer[] build(FileMap file) {
//    return tanh(file);
    return rectif(file);
  }

  Layer[] tanh(FileMap file) {
    Layer[] ls = new Layer[3];
    ls[0] = new FileMapInput(file);
    ls[1] = new Tanh(500);
    ls[2] = new FileMapSoftmax(file);
    for( int i = 0; i < ls.length; i++ ) {
      ls[i].rate = .005f;
      ls[i].rateAnnealing = 1e-6f;
      ls[i].l2 = .001f;
    }
    return ls;
  }

  Layer[] rectif(FileMap file) {
    Layer[] ls = new Layer[5];
    ls[0] = new FileMapInput(file);
    ls[1] = new Layer.RectifierDropout(1024);
    ls[2] = new Layer.RectifierDropout(1024);
    ls[3] = new Layer.RectifierDropout(2048);
    ls[4] = new FileMapSoftmax(file);
    for( int i = 0; i < ls.length; i++ ) {
      ls[i].rate = .01f;
      ls[i].rateAnnealing = 1e-7f;
      ls[i].momentumStart = .5f;
      ls[i].momentumRamp = 60000 * 300;
      ls[i].momentumStable = .99f;
      ls[i].l1 = .00001f;
    }
    return ls;
  }

  protected void startTraining(Layer[] ls) {
    // Single-thread SGD
//    _trainer = new Trainer.SingleThreaded(ls, 0);

    // Single-node parallel
    _trainer = new Trainer.Threaded(ls, 0);

    _trainer.start();
  }

  @Override
  public void run() {
    VM.exitWithParent();
    Log.write("Java location: " + System.getProperty("java.home"));

//    JGroups jg = new JGroups();
//    jg.start(VM.localIP4());

    //   Utils.readConsole();

    final Layer[] ls = build(_train);
    final float[] w = NeuralNet.init(ls, _local == 0);

    if( _nodes == null )
      startTraining(ls);
    else {
      _streamer = new Streamer(w, _nodes, _local) {
        @Override
        protected void weightsReady() {
          System.out.println("Weights ready");
          startTraining(ls);
        }
      };
      _streamer.start();
    }

    if( _local == 0 ) {
      // Monitor training
      final Timer timer = new Timer();
      final long start = System.nanoTime();
      final AtomicInteger evals = new AtomicInteger(1);
      timer.schedule(new TimerTask() {
        @Override
        public void run() {
          double time = (System.nanoTime() - start) / 1e9;
          Trainer trainer = _trainer;
          long processed = trainer == null ? 0 : trainer.processed();
          int ps = (int) (processed / time);
          String text = (int) time + "s, " + processed + " (" + (ps) + "/s) ";

          // Build separate nets for scoring purposes, use same normalization stats as for training
          Layer[] temp = build(_train);
          for( int i = 0; i < temp.length; i++ ) {
            temp[i].init(temp, i, 0);
            temp[i]._w = w;
          }
          // Estimate training error on subset of dataset for speed
          Errors e = NeuralNet.eval(temp, 1000, null);
          text += "train: " + e;
          text += ", rate: ";
          text += String.format("%.5g", ls[0].rate(processed));
          text += ", mtum: ";
          text += String.format("%.5g", ls[0].momentum(processed));
          if( _streamer != null ) {
            int sent = (int) (_streamer._sent / time);
            int rcev = (int) (_streamer._received / time);
            int cycl = (int) (_streamer._cycles / time);
            text += ", sent/s: " + sent + ", rcev/s: " + rcev + ", cycl/s: " + cycl;
          }
          System.out.println(text);
          if( (evals.incrementAndGet() % 16) == 0 ) {
            System.out.println("Computing test error");
            temp = build(_test);
            for( int i = 0; i < temp.length; i++ ) {
              temp[i].init(temp, i, 0);
              temp[i]._w = w;
            }
            e = NeuralNet.eval(temp, 0, null);
            System.out.println("Test error: " + e);
          }
        }
      }, 0, 10000);
    }

//    JFrame frame = new JFrame("");
//    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//    MnistCanvas canvas = new MnistCanvas(_trainer);
//    frame.setContentPane(canvas.init());
//    frame.pack();
//    frame.setLocationRelativeTo(null);
//    frame.setVisible(true);
//    frame.setExtendedState(Frame.MAXIMIZED_BOTH);
  }
}
