package snn;

import java.util.Random;

/**
 * Neural network layer.
 */
public abstract class Layer implements Cloneable {
  @ParamsSearch.Ignore
  public int units;

  public float rate;

  public float rateAnnealing;

  public float l1, l2;

  @ParamsSearch.Info(origin = 1)
  public float momentumStart;

  /**
   * Number of samples during which momentum value varies.
   */
  public long momentumRamp;

  @ParamsSearch.Info(origin = 1)
  public float momentumStable;

  public float[] _w;
  public int _wi, _wl; // Offset and length
  public int _bi, _bl;

  // Weights, biases, activity, error
  // TODO hold transients only for current two layers
  // TODO extract transients & code in separate one-shot trees to avoid cloning
  protected float[] _a, _e;

  // Momentum for weights and biases
  protected float[] _wm, _bm;

  // Previous and input layers
  protected Layer _previous;
  protected Input _input;

  /**
   * Start of refactoring in specification & running data, for layers and trainers.
   */
  static abstract class Training {
    abstract long processed();
  }

  transient Training _training;

  public void init(Layer[] ls, int index, long step) {
    _a = new float[units];
    _e = new float[units];
    _previous = ls[index - 1];
    _input = (Input) ls[0];
    _wi = _previous._bi + _previous._bl;
    _wl = units * _previous.units;
    _bi = _wi + _wl;
    _bl = units;

//    if( momentumStart != 0 || momentumStable != 0 ) {
//      _wm = new float[_w.length];
//      _bm = new float[_b.length];
//    }
  }

  public void randomize(Random rand) {
  }

  protected abstract void fprop(boolean training);

  protected abstract void bprop();

  /**
   * Apply gradient g to unit u with rate r and momentum m.
   */
  protected final void bprop(int u, float g, float r, float m) {
    float r2 = 0;
    for( int i = 0; i < _previous._a.length; i++ ) {
      int w = _wi + u * _previous._a.length + i;
      if( _previous._e != null )
        _previous._e[i] += g * _w[w];
      float d = g * _previous._a[i] - _w[w] * l2 - Math.signum(_w[w]) * l1;

      // TODO finish per-weight acceleration, doesn't help for now
//      if( _wp != null && d != 0 ) {
//        boolean sign = _wp[w] >= 0;
//        float mult = Math.abs(_wp[w]);
//        // If the gradient kept its sign, increase
//        if( (d >= 0) == sign )
//          mult += .05f;
//        else {
//          if( mult > 1 )
//            mult *= .95f;
//          else
//            sign = !sign;
//        }
//        d *= mult;
//        _wp[w] = sign ? mult : -mult;
//      }

      if( _wm != null ) {
        _wm[w] *= m;
        _wm[w] = d = _wm[w] + d;
      }
      _w[w] += r * d;
      r2 += _w[w] * _w[w];
    }
    if( r2 > 15 ) { // C.f. Improving neural networks by preventing co-adaptation of feature detectors
      float scale = (float) Math.sqrt(15 / r2);
      for( int i = 0; i < _previous._a.length; i++ ) {
        int w = u * _previous._a.length + i;
        _w[w] *= scale;
      }
    }
    float d = g;
    if( _bm != null ) {
      _bm[u] *= m;
      _bm[u] = d = _bm[u] + d;
    }
    _w[_bi + u] += r * d;
  }

  public float rate(long n) {
    return rate / (1 + rateAnnealing * n);
  }

  public float momentum(long n) {
    float m = momentumStart;
    if( momentumRamp > 0 ) {
      if( n >= momentumRamp )
        m = momentumStable;
      else
        m += (momentumStable - momentumStart) * n / momentumRamp;
    }
    return m;
  }

  // TODO add 20% dropout
  public static abstract class Input extends Layer {
    @ParamsSearch.Ignore
    protected long _pos, _len;

    @Override
    public void init(Layer[] ls, int index, long step) {
      _a = new float[units];
    }

    @Override
    protected void bprop() {
      throw new UnsupportedOperationException();
    }

    public final long move() {
      return _pos = _pos == _len - 1 ? 0 : _pos + 1;
    }
  }

  public static abstract class Output extends Layer {
    public enum Loss {
      MeanSquare, CrossEntropy
    }

    public Loss loss = Loss.CrossEntropy;

    protected final long pos() {
      return _input._pos;
    }
  }

  public static abstract class Softmax extends Output {
    protected abstract int target();

    @Override
    public void randomize(Random rand) {
      super.randomize(rand);
      float min = (float) -Math.sqrt(6. / (_previous.units + units));
      float max = (float) +Math.sqrt(6. / (_previous.units + units));
      for( int i = 0; i < _wl; i++ )
        _w[_wi + i] = rand(rand, min, max);
    }

    @Override
    protected void fprop(boolean training) {
      float max = Float.NEGATIVE_INFINITY;
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        for( int i = 0; i < _previous._a.length; i++ )
          _a[o] += _w[_wi + o * _previous._a.length + i] * _previous._a[i];
        _a[o] += _w[_bi + o];
        if( max < _a[o] )
          max = _a[o];
      }
      float scale = 0;
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = (float) Math.exp(_a[o] - max);
        scale += _a[o];
      }
      for( int o = 0; o < _a.length; o++ )
        _a[o] /= scale;
    }

    @Override
    protected void bprop() {
      long processed = _training.processed();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      int label = target();
      for( int u = 0; u < _a.length; u++ ) {
        float t = u == label ? 1 : 0;
        float e = t - _a[u];
        float g = e;
        if( loss == Loss.MeanSquare )
          g *= (1 - _a[u]) * _a[u];
        bprop(u, g, r, m);
      }
    }
  }

  public static abstract class Linear extends Output {
    abstract float[] target();

    @Override
    protected void fprop(boolean training) {
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        for( int i = 0; i < _previous._a.length; i++ )
          _a[o] += _w[_wi + o * _previous._a.length + i] * _previous._a[i];
        _a[o] += _w[_bi + o];
      }
    }

    @Override
    protected void bprop() {
      long processed = _training.processed();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      float[] v = target();
      for( int u = 0; u < _a.length; u++ ) {
        float e = v[u] - _a[u];
        float g = e * (1 - _a[u]) * _a[u]; // Square error
        bprop(u, g, r, m);
      }
    }
  }

  public static class Tanh extends Layer {
    Tanh() {
    }

    public Tanh(int units) {
      this.units = units;
    }

    @Override
    public void randomize(Random rand) {
      super.randomize(rand);
      // C.f. deeplearning.net tutorial
      float min = (float) -Math.sqrt(6. / (_previous.units + units));
      float max = (float) +Math.sqrt(6. / (_previous.units + units));
      for( int i = 0; i < _wl; i++ )
        _w[_wi + i] = rand(rand, min, max);
    }

    @Override
    protected void fprop(boolean training) {
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        for( int i = 0; i < _previous._a.length; i++ )
          _a[o] += _w[_wi + o * _previous._a.length + i] * _previous._a[i];
        _a[o] += _w[_bi + o];

        // tanh approx, slightly faster, untested
        // float a = Math.abs(_a[o]);
        // float b = 12 + a * (6 + a * (3 + a));
        // _a[o] = (_a[o] * b) / (a * b + 24);

        // Other approx to try
        // _a[o] = -1 + (2 / (1 + Math.exp(-2 * _a[o])));

        _a[o] = (float) Math.tanh(_a[o]);
      }
    }

    @Override
    protected void bprop() {
      long processed = _training.processed();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      for( int u = 0; u < _a.length; u++ ) {
        // Gradient is error * derivative of hyperbolic tangent: (1 - x^2)
        float g = _e[u] * (1 - _a[u] * _a[u]);
        bprop(u, g, r, m);
      }
    }
  }

  public static class Rectifier extends Layer {
    Rectifier() {
    }

    public Rectifier(int units) {
      this.units = units;
    }

    @Override
    public void randomize(Random rand) {
      super.randomize(rand);
//        int count = Math.min(15, _previous.units);
//        //float min = -.1f, max = +.1f;
//        float min = -1f, max = +1f;
//        for( int o = 0; o < units; o++ ) {
//          for( int n = 0; n < count; n++ ) {
//            int i = rand.nextInt(_previous.units);
//            int w = o * _previous.units + i;
//            _w[w] = rand(rand, min, max);
//          }
//        }
      float min = (float) -Math.sqrt(6. / (_previous.units + units));
      float max = (float) +Math.sqrt(6. / (_previous.units + units));
      for( int i = 0; i < _wl; i++ )
        _w[_wi + i] = rand(rand, min, max);
      for( int i = 0; i < _bl; i++ )
        _w[_bi + i] = 1;
    }

    @Override
    protected void fprop(boolean training) {
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        for( int i = 0; i < _previous._a.length; i++ )
          _a[o] += _w[_wi + o * _previous._a.length + i] * _previous._a[i];
        _a[o] += _w[_bi + o];
        // TODO test using bit stuff
        //int i = Float.floatToRawIntBits(_a[o]);
        //int s = i & 0x80000000;
        //s = s >> 31;
        //_a[o] = Float.intBitsToFloat(i & ~s);
        if( _a[o] < 0 )
          _a[o] = 0;
      }
    }

    @Override
    protected void bprop() {
      long processed = _training.processed();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      for( int u = 0; u < _a.length; u++ ) {
        float g = _e[u];
        if( _a[u] > 0 )
          bprop(u, g, r, m);
      }
    }
  }

  public static class RectifierDropout extends Rectifier {
    transient Random _rand;
    transient byte[] _bits;

    RectifierDropout() {
    }

    public RectifierDropout(int units) {
      super(units);
    }

    @Override
    protected void fprop(boolean training) {
      if( _rand == null ) {
        _rand = new MersenneTwisterRNG();
        _bits = new byte[(units + 7) / 8];
      }
      _rand.nextBytes(_bits);
      for( int o = 0; o < _a.length; o++ ) {
        _a[o] = 0;
        boolean b = (_bits[o / 8] & (1 << (o % 8))) != 0;
        if( !training || b ) {
          for( int i = 0; i < _previous._a.length; i++ )
            _a[o] += _w[_wi + o * _previous._a.length + i] * _previous._a[i];
          _a[o] += _w[_bi + o];
          if( _a[o] < 0 )
            _a[o] = 0;
          if( !training )
            _a[o] *= .5f;
        }
      }
    }
  }

  //

  @Override
  public Layer clone() {
    try {
      return (Layer) super.clone();
    } catch( CloneNotSupportedException e ) {
      throw new RuntimeException(e);
    }
  }

  private static float rand(Random rand, float min, float max) {
    return min + rand.nextFloat() * (max - min);
  }
}
