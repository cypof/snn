package snn;

import java.io.*;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import snn.Layer.Input;
import snn.Layer.Training;

import com.googlecode.javacpp.Builder;
import com.googlecode.javacpp.Loader;
import com.googlecode.javacpp.annotation.Platform;
import com.jogamp.opencl.*;
import com.jogamp.opencl.CLMemory.Mem;

public abstract class Trainer {
  public Trainer() {
  }

  public abstract Layer[] layers();

  public abstract void start();

  public abstract void join();

  public long processed() {
    throw new UnsupportedOperationException();
  }

  public static class Base extends Trainer {
    final Layer[] _ls;

    public Base(Layer[] ls) {
      _ls = ls;
    }

    @Override
    public Layer[] layers() {
      return _ls;
    }

    @Override
    public void start() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void join() {
      throw new UnsupportedOperationException();
    }

    final void step() {
      fprop();
      for( int i = 1; i < _ls.length - 1; i++ )
        Arrays.fill(_ls[i]._e, 0);
      bprop();
    }

    final void fprop() {
      for( int i = 0; i < _ls.length; i++ )
        _ls[i].fprop(true);
    }

    final void bprop() {
      for( int i = _ls.length - 1; i > 0; i-- )
        _ls[i].bprop();
    }
  }

  public static class SingleThreaded extends Base {
    long _processed, _limit;
    Thread _thread;

    public SingleThreaded(Layer[] ls, double epochs) {
      super(ls);
      _limit = (long) (epochs * ((Input) ls[0])._len);
    }

    @Override
    public Layer[] layers() {
      return _ls;
    }

    public void run() {
      Training training = new Training() {
        @Override
        long processed() {
          return _processed;
        }
      };
      for( int i = 0; i < _ls.length; i++ )
        _ls[i]._training = training;

      Input input = (Input) _ls[0];
      for( ; _limit == 0 || _processed < _limit; _processed++ ) {
        step();
        input.move();
      }
    }

    @Override
    public long processed() {
      return _processed;
    }

    @Override
    public void start() {
      _thread = new Thread() {
        @Override
        public void run() {
          SingleThreaded.this.run();
        }
      };
      _thread.start();
    }

    @Override
    public void join() {
      try {
        _thread.join();
      } catch( InterruptedException e ) {
        throw new RuntimeException(e);
      }
    }
  }

  static int cores() {
    // TODO - 1 to dedicate one to streaming is distributed case
    return Runtime.getRuntime().availableProcessors();
  }

  /**
   * Runs several trainers in parallel on the same weights, using threads.
   */
  public static class Threaded extends Trainer {
    final Base[] _trainers;
    final Thread[] _threads;
    final long _stepsPerThread;
    static final CyclicBarrier DONE = new CyclicBarrier(1);
    volatile CyclicBarrier _suspend;
    final CyclicBarrier _resume;
    final AtomicLong _processed = new AtomicLong();

    public Threaded(Layer[] ls, double epochs) {
      _trainers = new Base[cores()];
      _threads = new Thread[_trainers.length];
      _stepsPerThread = (long) (epochs * ((Input) ls[0])._len / _threads.length);
      _resume = new CyclicBarrier(_threads.length + 1);

      for( int t = 0; t < _trainers.length; t++ ) {
        Layer[] clones = new Layer[ls.length];
        for( int y = 0; y < clones.length; y++ )
          clones[y] = ls[y].clone();
        for( int y = 0; y < clones.length; y++ ) {
          clones[y].init(clones, y, 0);
          clones[y]._training = new Training() {
            @Override
            long processed() {
              return _processed.get();
            }
          };
        }
        final Input input = (Input) clones[0];
        input._pos = input._len * t / _trainers.length;
        _trainers[t] = new Base(clones);
        final Base trainer = _trainers[t];

        _threads[t] = new Thread("Trainer " + t) {
          @Override
          public void run() {
            for( long i = 0; _stepsPerThread == 0 || i < _stepsPerThread; i++ ) {
              CyclicBarrier b = _suspend;
              if( b == DONE )
                break;
              if( b != null ) {
                try {
                  b.await();
                  _resume.await();
                } catch( Exception e ) {
                  throw new RuntimeException(e);
                }
              }
              trainer.step();
              input.move();
              _processed.incrementAndGet();
            }
          }
        };
      }
      Log.write("Started " + _trainers.length + " neural network trainers");
    }

    @Override
    public Layer[] layers() {
      return _trainers[0].layers();
    }

    @Override
    public long processed() {
      return _processed.get();
    }

    @Override
    public void start() {
      for( int t = 0; t < _threads.length; t++ )
        _threads[t].start();
    }

    @Override
    public void join() {
      for( int i = 0; i < _threads.length; i++ ) {
        try {
          _threads[i].join();
        } catch( InterruptedException e ) {
          throw new RuntimeException(e);
        }
      }
    }

    public void cancel() {
      _suspend = DONE;
    }

    void suspend() {
      try {
        _suspend = new CyclicBarrier(_threads.length + 1);
        _suspend.await();
        _suspend = null;
      } catch( Exception e ) {
        throw new RuntimeException(e);
      }
    }

    void resume() {
      try {
        _resume.await();
      } catch( Exception e ) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * GPU based trainer. Not finished
   */
  public static class OpenCL extends Trainer {
    final Layer[] _ls;

    public OpenCL(Layer[] ls) {
      _ls = ls;
    }

    @Override
    public Layer[] layers() {
      return _ls;
    }

    @Override
    public void start() {
      CLContext context = CLContext.create();
      Log.write("Created " + context);

      try {
        CLDevice device = context.getMaxFlopsDevice();
        Log.write("Using " + device);
        CLCommandQueue queue = device.createCommandQueue();

        ClassLoader cl = getClass().getClassLoader();
        CLProgram program = context.createProgram(cl.getResourceAsStream("snn/Kernels.c")).build();
        CLKernel[] fprops = new CLKernel[_ls.length];
        CLKernel[] bprops = new CLKernel[_ls.length];
        CLKernel[] resets = new CLKernel[_ls.length];
        CLBuffer<FloatBuffer>[] w = new CLBuffer[_ls.length];
        CLBuffer<FloatBuffer>[] b = new CLBuffer[_ls.length];
        CLBuffer<FloatBuffer>[] a = new CLBuffer[_ls.length];
        CLBuffer<FloatBuffer>[] e = new CLBuffer[_ls.length];
        for( int y = 0; y < _ls.length; y++ ) {
          a[y] = context.createFloatBuffer(_ls[y]._a.length, Mem.READ_WRITE);
          if( y > 0 ) {
//            w[y] = context.createFloatBuffer(_ls[y]._w.length, Mem.READ_ONLY);
//            b[y] = context.createFloatBuffer(_ls[y]._b.length, Mem.READ_ONLY);
            e[y] = context.createFloatBuffer(_ls[y]._e.length, Mem.READ_ONLY);
            queue.putWriteBuffer(w[y], false);
            queue.putWriteBuffer(b[y], false);

            fprops[y] = program.createCLKernel(_ls.getClass().getSimpleName() + "_fprop");
            fprops[y].putArg(_ls[y - 1]._a.length);
            fprops[y].putArgs(a[y - 1], w[y], b[y], a[y]);

            bprops[y] = program.createCLKernel(_ls.getClass().getSimpleName() + "_bprop");
            bprops[y].putArg(_ls[y - 1]._a.length);
            bprops[y].putArgs(a[y - 1], w[y], b[y], a[y], e[y]);
//            bprops[y].putArg(_ls[y]._r);
            if( e[y - 1] != null )
              bprops[y].putArg(e[y - 1]);

            resets[y] = program.createCLKernel("reset_error");
            resets[y].putArg(e[y]);
          }
        }
        int group = device.getMaxWorkGroupSize();
        Input input = (Input) _ls[0];
        for( ;; ) {
          input.fprop(true);
          for( int i = 0; i < input._a.length; i++ )
            a[0].getBuffer().put(i, input._a[i]);
          queue.putWriteBuffer(a[0], false);
          for( int y = 1; y < fprops.length; y++ )
            queue.put1DRangeKernel(fprops[y], 0, _ls[y]._a.length, group);

          queue.putReadBuffer(a[_ls.length - 1], true);
          for( int y = 1; y < fprops.length - 1; y++ )
            queue.put1DRangeKernel(resets[y], 0, _ls[y]._a.length, group);
          queue.putWriteBuffer(a[_ls.length - 1], false);
          queue.putWriteBuffer(e[_ls.length - 1], false);

          for( int y = _ls.length - 1; y > 0; y-- )
            queue.put1DRangeKernel(bprops[y], 0, _ls[y]._a.length, group);
          input.move();
        }
      } catch( IOException ex ) {
        throw new RuntimeException(ex);
      } finally {
        context.release();
      }
    }

    @Override
    public void join() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Generates and invoke C code at runtime, for speed and to share code between GPU/CPU.
   */
  @Platform(include = "Trainer.h")
  public static class Compiled extends Trainer {
    final Layer[] _ls;
    final Thread[] _threads;
    final long _stepsPerThread;

    public Compiled(Layer[] ls, double epochs) {
      _ls = ls;
      _threads = new Thread[cores()];
      _stepsPerThread = (long) (epochs * ((Input) ls[0])._len / _threads.length);
    }

    @Override
    public Layer[] layers() {
      return _ls;
    }

    static native void run(Buffer buffer, long offset, long steps);

    @Override
    public void start() {
      Builder builder = new Builder();
      Properties p = Loader.loadProperties();
      String options = (String) p.get("compiler.output.prefix");
      String n = options.replace("-march=x86-64 -m64", "-march=native");
      assert n.length() != options.length();
      builder.property("compiler.output.prefix", n);

      try {
        File lib = File.createTempFile("snn", "");
        builder.outputDirectory(lib.getParentFile().getAbsolutePath());

        InputStream is = Compiled.class.getClassLoader().getResourceAsStream("snn/Trainer.h");
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        FileWriter w = new FileWriter(new File(lib.getParentFile(), "Kernel.h"));
        for( ;; ) {
          String line = br.readLine();
          if( line == null ) {
            w.close();
            break;
          }
          w.write(line.replace("%cc%", "20") + '\n');
        }

        File so = builder.generateAndCompile(new Class[] { Compiled.class }, lib.getName());
        System.load(so.getAbsolutePath());
      } catch( Exception e ) {
        throw new RuntimeException(e);
      }

      for( int t = 0; t < _threads.length; t++ ) {
        final Input input = (Input) _ls[0];
        input._pos = input._len * t / _threads.length;

        _threads[t] = new Thread("Trainer " + t) {
          @Override
          public void run() {
            Compiled.run(null, input._pos, _stepsPerThread);
          }
        };
      }
      Log.write("Started " + _threads.length + " neural network trainers");
    }

    @Override
    public void join() {
      throw new RuntimeException("TODO Auto-generated method stub");
    }
  }
}
