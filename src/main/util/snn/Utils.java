package snn;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;

public class Utils {
  public static int set4(byte[] buf, int off, int x) {
    for( int i = 0; i < 4; i++ )
      buf[i + off] = (byte) (x >> (i << 3));
    return 4;
  }

  public static int get4(byte[] buf, int off) {
    int sum = 0;
    for( int i = 0; i < 4; i++ )
      sum |= (0xff & buf[off + i]) << (i << 3);
    return sum;
  }

  public static int set8d(byte[] buf, int off, double d) {
    long x = Double.doubleToLongBits(d);
    for( int i = 0; i < 8; i++ )
      buf[i + off] = (byte) (x >> (i << 3));
    return 8;
  }

  public static double get8d(byte[] buf, int off) {
    long sum = 0;
    for( int i = 0; i < 8; i++ )
      sum |= ((long) (0xff & buf[off + i])) << (i << 3);
    return Double.longBitsToDouble(sum);
  }

  public static int nextPowerOf2(int value) {
    value--;
    value |= value >> 1;
    value |= value >> 2;
    value |= value >> 4;
    value |= value >> 8;
    value |= value >> 16;
    value++;
    return value;
  }

  public static int sum(int[] from) {
    int result = 0;
    for( int d : from )
      result += d;
    return result;
  }

  public static float sum(float[] from) {
    float result = 0;
    for( float d : from )
      result += d;
    return result;
  }

  public static String sampleToString(int[] val, int max) {
    if( val == null || val.length < max )
      return Arrays.toString(val);

    StringBuilder b = new StringBuilder();
    b.append('[');
    max -= 10;
    int valMax = val.length - 1;
    for( int i = 0;; i++ ) {
      b.append(val[i]);
      if( i == max ) {
        b.append(", ...");
        i = val.length - 10;
      }
      if( i == valMax ) {
        return b.append(']').toString();
      }
      b.append(", ");
    }
  }

  public static void close(Closeable... closeable) {
    for( Closeable c : closeable )
      try {
        if( c != null )
          c.close();
      } catch( IOException _ ) {
      }
  }

  public static void close(Socket s) {
    try {
      if( s != null )
        s.close();
    } catch( IOException _ ) {
    }
  }

  public static String getStackAsString(Throwable t) {
    Writer result = new StringWriter();
    PrintWriter printWriter = new PrintWriter(result);
    t.printStackTrace(printWriter);
    return result.toString();
  }

  public static String readConsole() {
    BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
    try {
      return console.readLine();
    } catch( IOException e ) {
      throw new RuntimeException(e);
    }
  }

  public static File writeFile(String content) {
    try {
      return writeFile(File.createTempFile("snn", null), content);
    } catch( IOException e ) {
      throw new RuntimeException(e);
    }
  }

  public static File writeFile(File file, String content) {
    FileWriter w = null;
    try {
      w = new FileWriter(file);
      w.write(content);
    } catch( IOException e ) {
      throw new RuntimeException(e);
    } finally {
      close(w);
    }
    return file;
  }

  public static void writeFileAndClose(File file, InputStream in) {
    OutputStream out = null;
    try {
      out = new FileOutputStream(file);
      byte[] buffer = new byte[1024];
      int len = in.read(buffer);
      while( len > 0 ) {
        out.write(buffer, 0, len);
        len = in.read(buffer);
      }
    } catch( IOException e ) {
      throw new RuntimeException(e);
    } finally {
      close(in, out);
    }
  }

  public static String readFile(File file) {
    FileReader r = null;
    try {
      r = new FileReader(file);
      char[] data = new char[(int) file.length()];
      r.read(data);
      return new String(data);
    } catch( IOException e ) {
      throw new RuntimeException(e);
    } finally {
      close(r);
    }
  }

  public static void readFile(File file, OutputStream out) {
    BufferedInputStream in = null;
    try {
      in = new BufferedInputStream(new FileInputStream(file));
      byte[] buffer = new byte[1024];
      while( true ) {
        int count = in.read(buffer);
        if( count == -1 )
          break;
        out.write(buffer, 0, count);
      }
    } catch( IOException e ) {
      throw new RuntimeException(e);
    } finally {
      close(in);
    }
  }

  public static String join(char sep, Object[] array) {
    return join(sep, Arrays.asList(array));
  }

  public static String join(char sep, Iterable it) {
    String s = "";
    for( Object o : it )
      s += (s.length() == 0 ? "" : sep) + o.toString();
    return s;
  }

  public static String padRight(String stringToPad, int size) {
    StringBuilder strb = new StringBuilder(stringToPad);
    while( strb.length() < size )
      if( strb.length() < size )
        strb.append(' ');
    return strb.toString();
  }

  public static String fixedLength(String s, int length) {
    String r = Utils.padRight(s, length);
    if( r.length() > length ) {
      int a = Math.max(r.length() - length + 1, 0);
      int b = Math.max(a, r.length());
      r = "#" + r.substring(a, b);
    }
    return r;
  }

  public static double[][] append(double[][] a, double[][] b) {
    double[][] res = new double[a.length + b.length][];
    System.arraycopy(a, 0, res, 0, a.length);
    System.arraycopy(b, 0, res, a.length, b.length);
    return res;
  }

  public static int[] append(int[] a, int[] b) {
    int[] res = new int[a.length + b.length];
    System.arraycopy(a, 0, res, 0, a.length);
    System.arraycopy(b, 0, res, a.length, b.length);
    return res;
  }

  public static String[] append(String[] a, String[] b) {
    String[] res = new String[a.length + b.length];
    System.arraycopy(a, 0, res, 0, a.length);
    System.arraycopy(b, 0, res, a.length, b.length);
    return res;
  }

  public static <T> T[] append(T[] a, T... b) {
    if( a == null )
      return b;
    T[] tmp = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, tmp, a.length, b.length);
    return tmp;
  }

  public static <T> T[] remove(T[] a, int i) {
    T[] tmp = Arrays.copyOf(a, a.length - 1);
    System.arraycopy(a, i + 1, tmp, i, tmp.length - i);
    return tmp;
  }

  public static int[] remove(int[] a, int i) {
    int[] tmp = Arrays.copyOf(a, a.length - 1);
    System.arraycopy(a, i + 1, tmp, i, tmp.length - i);
    return tmp;
  }

  public static <T> T[] subarray(T[] a, int off, int len) {
    return Arrays.copyOfRange(a, off, off + len);
  }

  public static void clearFolder(String folder) {
    clearFolder(new File(folder));
  }

  public static void clearFolder(File folder) {
    if( folder.exists() ) {
      for( File child : folder.listFiles() ) {
        if( child.isDirectory() )
          clearFolder(child);

        if( !child.delete() )
          throw new RuntimeException("Cannot delete " + child);
      }
    }
  }

  /**
   * Returns the system temporary folder, e.g. /tmp
   */
  public static File tmp() {
    try {
      return File.createTempFile("snn", null).getParentFile();
    } catch( IOException e ) {
      throw new RuntimeException(e);
    }
  }

  public static String serializeString(Serializable s) {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutput out = null;
      try {
        out = new ObjectOutputStream(bos);
        out.writeObject(s);
        return Base64.encodeToString(bos.toByteArray(), false);
      } finally {
        out.close();
        bos.close();
      }
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T deserializeString(String s) {
    try {
      ObjectInput in = new ObjectInputStream(new ByteArrayInputStream(Base64.decode(s)));
      try {
        return (T) in.readObject();
      } finally {
        in.close();
      }
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }
}
