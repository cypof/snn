package snn;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public abstract class Log {
  private static final ThreadLocal<SimpleDateFormat> _format = new ThreadLocal<SimpleDateFormat>() {
    @Override
    protected SimpleDateFormat initialValue() {
      //SimpleDateFormat format = new SimpleDateFormat("yyMMdd'-'HH:mm:ss.SSS");
      //format.setTimeZone(TimeZone.getTimeZone("UTC"));
      SimpleDateFormat format = new SimpleDateFormat("hh:mm:ss.SSS");
      return format;
    }
  };

  private static String _address;

  static {
    System.setOut(new Wrapper(System.out));
    System.setErr(new Wrapper(System.err));
  }

  public static void init() {
  }

  public static void write(Exception ex) {
    write(Utils.getStackAsString(ex));
  }

  public static void write(String s) {
    System.out.println(s);
  }

  private static void header(StringBuilder sb) {
    String time = _format.get().format(new Date());
    sb.append(time);
    sb.append(" ");
    if( _address == null ) {
      String addr = VM.localIP4() + ":" + getPid();
      _address = Utils.fixedLength(addr + " ", 18);
    }
    sb.append(_address);
    sb.append(Utils.fixedLength(Thread.currentThread().getName(), 8));
    sb.append(": ");
  }

  private static long getPid() {
    try {
      String n = ManagementFactory.getRuntimeMXBean().getName();
      int i = n.indexOf('@');
      if( i == -1 )
        return -1;
      return Long.parseLong(n.substring(0, i));
    } catch( Throwable t ) {
      return -1;
    }
  }

  public static void unwrap(PrintStream stream, String s) {
    if( stream instanceof Wrapper )
      ((Wrapper) stream).printlnParent(s);
    else
      stream.println(s);
  }

  private static final class Wrapper extends PrintStream {
    Wrapper(PrintStream parent) {
      super(parent);
    }

    static String h() {
      StringBuilder sb = new StringBuilder();
      header(sb);
      return sb.toString();
    }

    @Override
    public PrintStream printf(String format, Object... args) {
      return super.printf(h() + format, args);
    }

    @Override
    public PrintStream printf(Locale l, String format, Object... args) {
      return super.printf(l, h() + format, args);
    }

    @Override
    public void println(String x) {
      super.println(h() + x);
    }

    void printlnParent(String s) {
      super.println(s);
    }
  }
}
