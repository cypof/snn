package snn;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.*;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.util.concurrent.atomic.AtomicBoolean;

import sun.misc.Unsafe;

/**
 * Memory mapped file with long offsets for big files.
 */
public final class FileMap implements Closeable {
  private final Class _channelImpl;
  private final long _mapAddress, _mapLength;
  private final long _address, _length;
  private final AtomicBoolean _closed = new AtomicBoolean();

  private static final Unsafe _unsafe = getUnsafe();
  private static final long _arrayBaseOffset = _unsafe.arrayBaseOffset(float[].class);

  public FileMap(String path) {
    this(new File(path));
  }

  public FileMap(File file) {
    this(file, 0, file.length(), MapMode.READ_ONLY);
  }

  public FileMap(File file, long off, long len) {
    this(file, off, len, MapMode.READ_WRITE);
  }

  public FileMap(File file, long off, long len, MapMode mode) {
    FileChannel channel = null;
    try {
      boolean read = mode == MapMode.READ_ONLY;
      FileSystem fs = FileSystems.getDefault();
      Path path = fs.getPath(file.getPath());
      if( read )
        channel = FileChannel.open(path, StandardOpenOption.READ);
      else
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

      // Adapted from sun.nio.ch.FileChannelImpl.map

      _channelImpl = channel.getClass();
      Field f = _channelImpl.getDeclaredField("allocationGranularity");
      f.setAccessible(true);
      long allocationGranularity = (Long) f.get(channel);

      if( !channel.isOpen() )
        throw new ClosedChannelException();
      if( off < 0 )
        throw new IllegalArgumentException("Negative position");
      if( len <= 0 )
        throw new IllegalArgumentException("Negative or zero size");
      if( off + len < 0 )
        throw new IllegalArgumentException("Position + size overflow");
      final int MAP_RO = 0;
      final int MAP_RW = 1;
      final int MAP_PV = 2;
      int imode = -1;
      if( mode == MapMode.READ_ONLY )
        imode = MAP_RO;
      else if( mode == MapMode.READ_WRITE )
        imode = MAP_RW;
      else if( mode == MapMode.PRIVATE )
        imode = MAP_PV;
      assert (imode >= 0);
      if( channel.size() < off + len ) {
        if( read )
          throw new IllegalArgumentException("Cannot extend file in read-only mode");
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
          raf.setLength(off + len);
        } finally {
          raf.close();
        }
      }
      Method map0 = _channelImpl.getDeclaredMethod("map0", int.class, long.class, long.class);
      map0.setAccessible(true);
      int pagePosition = (int) (off % allocationGranularity);
      long mapPosition = off - pagePosition;
      _mapLength = len + pagePosition;

      _mapAddress = (Long) map0.invoke(channel, imode, mapPosition, _mapLength);
      assert (_mapAddress % allocationGranularity == 0);

      _address = _mapAddress + pagePosition;
      _length = len;
    } catch( Exception ex ) {
      throw new RuntimeException(ex);
    } finally {
      Utils.close(channel);
    }
  }

  @Override
  protected void finalize() {
    close0();
  }

  @Override
  public void close() {
    close0();
  }

  private void close0() {
    if( _closed.compareAndSet(false, true) ) {
      Method unmap0 = null;
      for( Method m : _channelImpl.getDeclaredMethods() )
        if( m.getName().equals("unmap0") )
          unmap0 = m;
      unmap0.setAccessible(true);
      try {
        unmap0.invoke(null, _mapAddress, _mapLength);
      } catch( Exception e ) {
        throw new RuntimeException(e);
      }
    }
  }

  public long length() {
    return _length;
  }

  public float getFloat(long off) {
    assert off % 4 == 0;
    if( off < 0 || off + 4 > _length )
      throw new IllegalArgumentException();
    return _unsafe.getFloat(_address + off);
  }

  public void setFloat(long off, float f) {
    assert off % 4 == 0;
    if( off < 0 || off + 4 > _length )
      throw new IllegalArgumentException();
    _unsafe.putFloat(_address + off, f);
  }

  public void get(long off, float[] a) {
    assert off % 4 == 0;
    if( off < 0 || off + a.length * 4 > _length )
      throw new IllegalArgumentException();
    _unsafe.copyMemory(null, _address + off, a, _arrayBaseOffset, a.length * 4);
  }

  private static Unsafe getUnsafe() {
    try {
      return Unsafe.getUnsafe();
    } catch( SecurityException se ) {
      try {
        return java.security.AccessController.doPrivileged(new java.security.PrivilegedExceptionAction<Unsafe>() {
          @Override
          public Unsafe run() throws Exception {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
          }
        });
      } catch( java.security.PrivilegedActionException e ) {
        throw new RuntimeException(e);
      }
    }
  }
}
