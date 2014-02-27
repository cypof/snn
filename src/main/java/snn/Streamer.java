package snn;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.*;
import java.nio.channels.DatagramChannel;
import java.util.BitSet;

public class Streamer {
  static final int PACKET = 1500 - 20 - 8; // TODO test
  static final int HEADER = 1;
  static final int BLOCK = PACKET / 4 - HEADER;

  float[] _w, _last;
  SocketAddress[] _nodes;
  int _local, _mask;
  BitSet _receivedBlocks;
  int _blocks, _remainingBlocks;

  DatagramChannel _channel;
  ByteBuffer _buffer = ByteBuffer.allocateDirect(PACKET).order(ByteOrder.nativeOrder());
  FloatBuffer _floats = _buffer.asFloatBuffer();

  public volatile long _sent, _received, _cycles;

  public Streamer(float[] w, SocketAddress[] nodes, int local) {
    _w = w;
    _nodes = nodes;
    _local = local;

    if( nodes.length != Utils.nextPowerOf2(nodes.length) )
      throw new IllegalArgumentException();
    _mask = nodes.length - 1;

    _last = _w.clone();
    _receivedBlocks = new BitSet(w.length);
    assert _w.length % BLOCK == 0;
    _blocks = _w.length / BLOCK;
    _remainingBlocks = _blocks;
    for( int block = 0; block < _blocks; block++ ) {
      if( _local == master(block) ) {
        _receivedBlocks.set(block);
        _remainingBlocks--;
      }
    }
  }

  public void start() {
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          DatagramChannel channel = DatagramChannel.open();
          //_socket.socket().setReuseAddress(true);
          // TODO bench 'connect'
          channel.socket().bind(_nodes[_local]);
          channel.configureBlocking(false);
          _channel = channel;
        } catch( IOException e ) {
          throw new RuntimeException(e);
        }

        try {
          int block = 0;
          for( ;; ) {
            send(block);
            if( ++block == _blocks ) {
              block = 0;
              _cycles++;
            }

//            try {
//              Thread.sleep(1000);
//            } catch( InterruptedException ex ) {
//            }

            receive();
          }
        } catch( IOException e ) {
          throw new RuntimeException(e);
        }
      }
    };
//    thread.setDaemon(true);
    thread.start();
  }

  protected void weightsReady() {
  }

  final int master(int block) {
    return block & _mask;
  }

  final void send(int block) throws IOException {
    int off = block * BLOCK;
    _buffer.putInt(0, block);
    if( _local == master(block) ) {
      for( int i = 0; i < BLOCK; i++ )
        _floats.put(HEADER + i, _w[off + i]);
      // TODO multi-cast?
      for( int i = 0; i < _nodes.length; i++ )
        if( i != _local )
          send(_nodes[i]);
    } else {
      for( int i = 0; i < BLOCK; i++ ) {
        float d = _w[off + i] - _last[off + i];
        _floats.put(HEADER + i, d);
        _last[off + i] = _w[off + i];
      }
      send(_nodes[master(block)]);
    }
  }

  final void send(SocketAddress node) throws IOException {
    assert _buffer.position() == 0;
    int sent = _channel.send(_buffer, node);
    if( sent > 0 ) {
      assert _buffer.position() == _buffer.capacity() : _buffer.position();
      _buffer.position(0);
      _sent++;
    }
  }

  final void receive() throws IOException {
    assert _buffer.position() == 0;
    SocketAddress src = _channel.receive(_buffer);
    if( src != null ) {
      assert _buffer.position() == _buffer.capacity();
      _buffer.position(0);
      int block = _buffer.getInt(0);
      int off = block * BLOCK;
      if( _local == master(block) ) {
        for( int i = 0; i < BLOCK; i++ )
          _w[off + i] += _floats.get(HEADER + i);
      } else {
        for( int i = 0; i < BLOCK; i++ ) {
          float d = _w[off + i] - _last[off + i];
          _last[off + i] = _floats.get(HEADER + i);
          _w[off + i] = _last[off + i] + d;
        }

        if( _remainingBlocks > 0 && !_receivedBlocks.get(block) ) {
          _receivedBlocks.set(block);
          _remainingBlocks--;
          if( _remainingBlocks == 0 )
            weightsReady();
        }
      }
      _received++;
    }
  }
}
