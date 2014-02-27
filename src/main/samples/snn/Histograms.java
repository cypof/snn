package snn;

import java.util.ArrayList;
import java.util.concurrent.*;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.JFXPanel;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import javax.swing.SwingUtilities;

public class Histograms extends LineChart {
  private static final int SLICES = 64;

  private static final ArrayList<Histograms> _instances = new ArrayList<Histograms>();
  private static final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor();
  private static CheckBox _auto;

  private final float[] _data;
  private final int _off, _len;
  private final ObservableList<Data<Float, Float>> _list = FXCollections.observableArrayList();

  public static void init() {
    final CountDownLatch latch = new CountDownLatch(1);
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        initFromSwingThread();
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch( InterruptedException e ) {
      throw new RuntimeException(e);
    }
  }

  static void initFromSwingThread() {
    new JFXPanel(); // initializes JavaFX environment
  }

  public static void build(final Layer[] ls) {
    Platform.runLater(new Runnable() {
      @Override
      public void run() {
        VBox v = new VBox();
        for( int i = ls.length - 1; i > 0; i-- ) {
          HBox h = new HBox();
          h.getChildren().add(new Histograms("Layer " + i + " W", ls[i]._w, ls[i]._wi, ls[i]._wl));
          h.getChildren().add(new Histograms("Bias", ls[i]._w, ls[i]._bi, ls[i]._bl));
          h.getChildren().add(new Histograms("Activity", ls[i]._a, 0, ls[i]._a.length));
          h.getChildren().add(new Histograms("Error", ls[i]._e, 0, ls[i]._e.length));
//          h.getChildren().add(new Histograms("Momentum", ls[i]._wm));
//          h.getChildren().add(new Histograms("Per weight", ls[i]._wp));
          v.getChildren().add(h);
        }
        Stage stage = new Stage();
        BorderPane root = new BorderPane();
        ToolBar toolbar = new ToolBar();

        Button refresh = new Button("Refresh");
        refresh.setOnAction(new EventHandler<ActionEvent>() {
          @Override
          public void handle(ActionEvent e) {
            refresh();
          }
        });
        toolbar.getItems().add(refresh);

        _auto = new CheckBox("Auto");
        _auto.selectedProperty().addListener(new ChangeListener<Boolean>() {
          @Override
          public void changed(ObservableValue<? extends Boolean> ov, Boolean old_val, Boolean new_val) {
            refresh();
          }
        });
        toolbar.getItems().add(_auto);

        root.setTop(toolbar);
        ScrollPane scroll = new ScrollPane();
        scroll.setContent(v);
        root.setCenter(scroll);
        Scene scene = new Scene(root);
        stage.setScene(scene);
//        stage.setWidth(2450);
//        stage.setHeight(1500);
        stage.setFullScreen(true);
        stage.show();

        scene.getWindow().onCloseRequestProperty().addListener(new ChangeListener() {
          @Override
          public void changed(ObservableValue arg0, Object arg1, Object arg2) {
            _auto.selectedProperty().set(false);
          }
        });
        refresh();
      }
    });
  }

  public Histograms(String title, float[] data, int off, int len) {
    super(new NumberAxis(), new NumberAxis());
    _data = data;
    _off = off;
    _len = len;

    ObservableList<Series<Float, Float>> series = FXCollections.observableArrayList();
    for( int i = 0; i < SLICES; i++ )
      _list.add(new Data<Float, Float>(0f, 0f));
    series.add(new LineChart.Series<Float, Float>(title, _list));
    setData(series);
    setPrefWidth(600);
    setPrefHeight(250);

    _instances.add(this);
  }

  static void refresh() {
    for( Histograms h : _instances ) {
      if( h._data != null ) {
        float[] data = new float[h._len];
        System.arraycopy(h._data, h._off, data, 0, h._len);
        float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        for( int i = 0; i < data.length; i++ ) {
          max = Math.max(max, data[i]);
          min = Math.min(min, data[i]);
        }
        int[] counts = new int[SLICES];
        float inc = (max - min) / (SLICES - 1);
        for( int i = 0; i < data.length; i++ )
          counts[(int) Math.floor((data[i] - min) / inc)]++;

        for( int i = 0; i < SLICES; i++ ) {
          Data<Float, Float> point = h._list.get(i);
          point.setXValue(min + inc * i);
          point.setYValue((float) counts[i] / data.length);
        }
      }
    }

    if( _auto.selectedProperty().get() ) {
      _executor.schedule(new Runnable() {

        @Override
        public void run() {
          Platform.runLater(new Runnable() {
            @Override
            public void run() {
              refresh();
            }
          });
        }
      }, 1000, TimeUnit.MILLISECONDS);
    }
  }
}