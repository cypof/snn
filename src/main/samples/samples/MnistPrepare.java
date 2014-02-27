package samples;

import java.io.*;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

import snn.*;

public class MnistPrepare {
  static double[] _means, _sigmas;

  public static void main(String[] args) throws Exception {
    String f = "./data/mnist/";
    run(f + "train.snn", f + "train-images-idx3-ubyte.gz", f + "train-labels-idx1-ubyte.gz");
    run(f + "test.snn", f + "t10k-images-idx3-ubyte.gz", f + "t10k-labels-idx1-ubyte.gz");
    System.out.println("Done!");
  }

  static void run(String dest, String images, String labels) throws Exception {
    DataInputStream imagesIn = new DataInputStream(new GZIPInputStream(new FileInputStream(new File(images))));
    DataInputStream labelsIn = new DataInputStream(new GZIPInputStream(new FileInputStream(new File(labels))));

    imagesIn.readInt(); // Magic
    int count = imagesIn.readInt();
    labelsIn.readInt(); // Magic
    labelsIn.readInt(); // Count
    imagesIn.readInt(); // Rows
    imagesIn.readInt(); // Cols

    System.out.println("Reading " + count + " samples");
    byte[][] rawI = new byte[count][Mnist.PIXELS];
    byte[] rawL = new byte[count];
    for( int n = 0; n < count; n++ ) {
      imagesIn.readFully(rawI[n]);
      rawL[n] = labelsIn.readByte();
    }

    System.out.println("Randomizing");
    MersenneTwisterRNG rand = new MersenneTwisterRNG();
    for( int n = count - 1; n >= 0; n-- ) {
      int shuffle = rand.nextInt(n + 1);
      byte[] image = rawI[shuffle];
      rawI[shuffle] = rawI[n];
      rawI[n] = image;
      byte label = rawL[shuffle];
      rawL[shuffle] = rawL[n];
      rawL[n] = label;
    }

    if( _means == null ) {
      System.out.println("Stats");
      _means = new double[Mnist.PIXELS];
      for( int n = 0; n < count; n++ )
        for( int p = 0; p < rawI[n].length; p++ )
          _means[p] += rawI[n][p] & 0xff;
      for( int p = 0; p < _means.length; p++ )
        _means[p] /= count;

      _sigmas = new double[Mnist.PIXELS];
      for( int n = 0; n < count; n++ ) {
        for( int p = 0; p < rawI[n].length; p++ ) {
          double d = (rawI[n][p] & 0xff) - _means[p];
          _sigmas[p] += d * d;
        }
      }
      for( int i = 0; i < _means.length; i++ )
        _sigmas[i] = Math.sqrt(_sigmas[i] / (count - 1));
      System.out.println("Means  " + Arrays.toString(_means));
      System.out.println("Sigmas " + Arrays.toString(_sigmas));
    }

    System.out.println("Writing " + dest);
    File file = new File(dest);
    file.getParentFile().mkdirs();
    FileMap map = new FileMap(file, 0, count * (Mnist.PIXELS + 1) * 4);
    for( int n = 0; n < count; n++ ) {
      for( int p = 0; p < rawI[n].length; p++ ) {
        double d = rawI[n][p] & 0xff;
        d -= _means[p];
        d = _sigmas[p] > 1e-6 ? d / _sigmas[p] : d;
        map.setFloat((n * (Mnist.PIXELS + 1) + p) * 4, (float) d);
      }
      map.setFloat((n * (Mnist.PIXELS + 1) + Mnist.PIXELS) * 4, rawL[n] & 0xff);
    }
    Utils.close(imagesIn, labelsIn, map);
  }
}
