package snn;

import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import snn.Layer.Input;
import snn.Layer.Output.Loss;
import snn.Layer.Softmax;

public class NeuralNetIrisTest {
  public static class TestInput extends Input {
    float[][] _data;

    public TestInput(float[][] data) {
      units = 4;
      _len = data.length;
      _data = data;
    }

    @Override
    protected void fprop(boolean training) {
      System.arraycopy(_data[(int) _pos], 0, _a, 0, _a.length);
    }
  }

  public static class TestSoftmax extends Softmax {
    float[][] _data;

    public TestSoftmax(float[][] data) {
      units = 3;
      _data = data;
    }

    @Override
    protected int target() {
      for( int i = 0; i < units; i++ )
        if( _data[(int) pos()][4 + i] > 0 )
          return i;
      throw new IllegalStateException();
    }
  }

  @Test
  public void compare() throws Exception {
    NeuralNetMLPReference ref = new NeuralNetMLPReference();
    ref.init();

    float rate = 0.01f;
    int epochs = 1000;
    TestInput input = new TestInput(ref._trainData);
    TestSoftmax output = new TestSoftmax(ref._trainData);
    output.loss = Loss.MeanSquare;
    Layer[] ls = new Layer[3];
    ls[0] = input;
    ls[1] = new Layer.Tanh(7);
    ls[1].rate = rate;
    ls[2] = output;
    ls[2].rate = rate;
    for( int i = 0; i < ls.length; i++ )
      ls[i].init(ls, i, 0);
    Layer last = ls[ls.length - 1];
    final float[] w = new float[last._bi + last._bl];
    for( int i = 0; i < ls.length; i++ )
      ls[i]._w = w;
    Random rand = new MersenneTwisterRNG();
    for( int i = 0; i < ls.length; i++ )
      ls[i].randomize(rand);

    Layer l = ls[1];
    for( int o = 0; o < l._a.length; o++ ) {
      for( int i = 0; i < l._previous._a.length; i++ )
        ref._nn.ihWeights[i][o] = w[l._wi + o * l._previous._a.length + i];
      ref._nn.hBiases[o] = w[l._bi + o];
    }
    l = ls[2];
    for( int o = 0; o < l._a.length; o++ ) {
      for( int i = 0; i < l._previous._a.length; i++ )
        ref._nn.hoWeights[i][o] = w[l._wi + o * l._previous._a.length + i];
      ref._nn.oBiases[o] = w[l._bi + o];
    }

    // Reference
    ref.train(epochs, rate);

    Trainer.SingleThreaded trainer = new Trainer.SingleThreaded(ls, epochs);
    trainer.run();

    // Make sure outputs are equal
    float epsilon = 1e-4f;
    for( int o = 0; o < ls[2]._a.length; o++ ) {
      float a = ref._nn.outputs[o];
      float b = ls[2]._a[o];
      Assert.assertEquals(a, b, epsilon);
    }

    // Make sure weights are equal
    l = ls[1];
    for( int o = 0; o < l._a.length; o++ ) {
      for( int i = 0; i < l._previous._a.length; i++ ) {
        float a = ref._nn.ihWeights[i][o];
        float b = w[l._wi + o * l._previous._a.length + i];
        Assert.assertEquals(a, b, epsilon);
      }
    }

    // Make sure errors are equal
    NeuralNet.Errors train = NeuralNet.eval(ls, 0, null);
    input._data = ref._testData;
    input._len = ref._testData.length;
    output._data = ref._testData;
    NeuralNet.Errors test = NeuralNet.eval(ls, 0, null);
    float trainAcc = ref._nn.Accuracy(ref._trainData);
    Assert.assertEquals(trainAcc, train.classification, epsilon);
    float testAcc = ref._nn.Accuracy(ref._testData);
    Assert.assertEquals(testAcc, test.classification, epsilon);

    Log.write("SNN and Reference equal, train: " + train + ", test: " + test);
  }
}
