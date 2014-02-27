package snn;

import java.util.Random;

import snn.Layer.Input;
import snn.Layer.Linear;
import snn.Layer.Output;
import snn.Layer.Softmax;

public class NeuralNet {
  public static float[] init(Layer[] ls, boolean randomize) {
    for( int i = 0; i < ls.length; i++ )
      ls[i].init(ls, i, 0);
    Layer last = ls[ls.length - 1];
    int len = last._bi + last._bl;
    if( len % Streamer.BLOCK != 0 )
      len += Streamer.BLOCK - len % Streamer.BLOCK;
    float[] w = new float[len];
    for( int i = 0; i < ls.length; i++ )
      ls[i]._w = w;
    if( randomize ) {
      Random rand = new MersenneTwisterRNG();
      for( int i = 0; i < ls.length; i++ )
        ls[i].randomize(rand);
    }
    return w;
  }

  public static class Errors {
    public long training_samples;

    public long training_time_ms;

    public double classification = 1;

    public double mean_square;

    public double[] rates;

    @Override
    public String toString() {
      return String.format("%.2f", (100 * classification)) + "% (MSE:" + String.format("%.2e", mean_square) + ")";
    }
  }

  public static Errors eval(Layer[] ls, Input input, Output output, long n, long[][] cm) {
    Layer[] clones = new Layer[ls.length];
    clones[0] = input;
    for( int y = 1; y < clones.length - 1; y++ )
      clones[y] = ls[y].clone();
    clones[clones.length - 1] = output;
    for( int y = 0; y < clones.length; y++ )
      clones[y].init(clones, y, 0);
    return eval(clones, n, cm);
  }

  public static Errors eval(Layer[] ls, long n, long[][] cm) {
    Errors e = new Errors();
    Input input = (Input) ls[0];
    long len = input._len;
    if( n != 0 )
      len = Math.min(len, n);
    if( ls[ls.length - 1] instanceof Softmax ) {
      int correct = 0;
      for( input._pos = 0; input._pos < len; input._pos++ )
        if( correct(ls, e, cm) )
          correct++;
      e.classification = (len - (double) correct) / len;
      e.mean_square /= len;
    } else {
      for( input._pos = 0; input._pos < len; input._pos++ )
        error(ls, e);
      e.classification = Double.NaN;
      e.mean_square /= len;
    }
    input._pos = 0;
    return e;
  }

  private static boolean correct(Layer[] ls, Errors e, long[][] confusion) {
    Softmax output = (Softmax) ls[ls.length - 1];
    for( int i = 0; i < ls.length; i++ )
      ls[i].fprop(false);
    float[] out = ls[ls.length - 1]._a;
    int target = output.target();
    for( int o = 0; o < out.length; o++ ) {
      float t = o == target ? 1 : 0;
      float d = t - out[o];
      e.mean_square += d * d;
    }
    float max = out[0];
    int idx = 0;
    for( int o = 1; o < out.length; o++ ) {
      if( out[o] > max ) {
        max = out[o];
        idx = o;
      }
    }
    if( confusion != null )
      confusion[output.target()][idx]++;
    return idx == output.target();
  }

  // TODO extract to layer
  private static void error(Layer[] ls, Errors e) {
    Linear linear = (Linear) ls[ls.length - 1];
    for( int i = 0; i < ls.length; i++ )
      ls[i].fprop(false);
    float[] output = ls[ls.length - 1]._a;
    float[] target = linear.target();
    for( int o = 0; o < output.length; o++ ) {
      float d = target[o] - output[o];
      e.mean_square += d * d;
    }
  }
}
