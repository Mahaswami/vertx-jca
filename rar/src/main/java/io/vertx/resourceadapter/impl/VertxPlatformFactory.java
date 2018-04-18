package io.vertx.resourceadapter.impl;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.ConcurrentHashSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.vertx.core.*;
import io.vertx.core.cli.annotations.*;
import io.vertx.core.impl.launcher.VertxLifecycleHooks;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.spi.VertxMetricsFactory;
import io.vertx.core.spi.launcher.ExecutionContext;

import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A singleton factory to start a clustered Vert.x platform.
 *
 * One clusterPort/clusterHost pair matches one Vert.x platform.
 *
 * @author Lin Gao <lgao@redhat.com>
 *
 */
public class VertxPlatformFactory {

  protected String clusterHost;
  private static final Logger log = Logger.getLogger(VertxPlatformFactory.class
      .getName());

  private static final VertxPlatformFactory INSTANCE = new VertxPlatformFactory();

  /**
   * All Vert.x platforms.
   *
   */
  private final Map<String, Vertx> vertxPlatforms = new ConcurrentHashMap<String, Vertx>();

  /**
   * All Vert.x holders
   */
  private final Set<VertxHolder> vertxHolders = new ConcurrentHashSet<VertxHolder>();

  private VertxPlatformFactory() {
  }

  public static VertxPlatformFactory instance() {
    return INSTANCE;
  }

  /**
   * Creates a Vertx if one is not started yet.
   *
   * @param config
   *          the configuration to start a vertx
   * @param lifecyleListener
   *          the vertx lifecycle listener
   */
  public synchronized void getOrCreateVertx(
      final VertxPlatformConfiguration config, final VertxListener listener) {

    Vertx vertx = vertxPlatforms.get(config.getVertxPlatformIdentifier());

    if (vertx != null) {
      listener.whenReady(vertx);
      return;
    }

    clusterHost = getDefaultAddress();
    System.out.println("cluster-host********* " + clusterHost);
    VertxOptions options = new VertxOptions();
    options.setClustered(true);
    options.setClusterHost(clusterHost);
//    options.setClusterPublicHost("10.10.1.81");
    options.setClusterPort(15701);

//    System.setProperty("vertx.cluster.public.host", "10.10.1.81");
//    System.setProperty("vertx.hazelcast.config", "/opt/jboss/wildfly/aws-default-cluster.xml");

    CountDownLatch latch = new CountDownLatch(1);
    Vertx.clusteredVertx(options, ar -> {
      try {
        if (ar.succeeded()) {
          log.log(Level.INFO, "Acquired Vert.x platform.");
          listener.whenReady(ar.result());
          vertxPlatforms.put(config.getVertxPlatformIdentifier(), ar.result());
        } else {
          throw new RuntimeException("Could not acquire Vert.x platform.",
              ar.cause());
        }
      } finally {
        latch.countDown();
      }
    });

    try {
      if (!latch.await(config.getTimeout(), TimeUnit.MILLISECONDS)) {
        log.log(Level.SEVERE, "Could not acquire Vert.x platform in interval.");
        throw new RuntimeException(
            "Could not acquire Vert.x platform in interval");
      }
    } catch (Exception ignore) {
    }
  }

  protected String getDefaultAddress() {
    Enumeration<NetworkInterface> nets;
    try {
      nets = NetworkInterface.getNetworkInterfaces();
    } catch (SocketException e) {
      return null;
    }
    NetworkInterface netinf;
    while (nets.hasMoreElements()) {
      netinf = nets.nextElement();

      Enumeration<InetAddress> addresses = netinf.getInetAddresses();

      while (addresses.hasMoreElements()) {
        InetAddress address = addresses.nextElement();
        if (!address.isAnyLocalAddress() && !address.isMulticastAddress()
          && !(address instanceof Inet6Address)) {
          return address.getHostAddress();
        }
      }
    }
    return null;
  }

  /**
   * Adds VertxHolder to be recorded.
   *
   * @param holder
   *          the VertxHolder
   */
  public void addVertxHolder(VertxHolder holder) {

    if (vertxPlatforms.containsValue(holder.getVertx())) {
      if (!this.vertxHolders.contains(holder)) {
        log.log(Level.INFO, "Adding Vertx Holder: " + holder);
        this.vertxHolders.add(holder);
      } else {
        log.log(Level.WARNING, "Vertx Holder: " + holder
            + " has been added already.");
      }
    } else {
      log.log(Level.SEVERE, "Vertx Holder: " + holder
          + " is out of management.");
    }
  }

  /**
   * Removes the VertxHolder from recorded.
   *
   * @param holder
   *          the VertxHolder
   */
  public void removeVertxHolder(VertxHolder holder) {

    if (this.vertxHolders.contains(holder)) {
      log.log(Level.INFO, "Removing Vertx Holder: " + holder);
      this.vertxHolders.remove(holder);
    } else {
      log.log(Level.SEVERE, "Vertx Holder: " + holder
          + " is out of management.");
    }
  }

  /**
   * Stops the Vert.x Platform Manager and removes it from cache.
   *
   * @param config
   */
  public void stopPlatformManager(VertxPlatformConfiguration config) {

    Vertx vertx = this.vertxPlatforms.get(config.getVertxPlatformIdentifier());
    if (vertx != null && isVertxHolded(vertx)) {
      log.log(Level.INFO,
          "Stopping Vert.x: " + config.getVertxPlatformIdentifier());
      vertxPlatforms.remove(config.getVertxPlatformIdentifier());
      stopVertx(vertx);
    }
  }

  private boolean isVertxHolded(Vertx vertx) {
    for (VertxHolder holder : this.vertxHolders) {
      if (vertx.equals(holder.getVertx())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stops all started Vert.x platforms.
   *
   * Clears all vertx holders.
   */
  public void closeAllPlatforms() {

    log.log(Level.FINEST, "Closing all Vert.x instances");
    try {

      for (Map.Entry<String, Vertx> entry : this.vertxPlatforms.entrySet()) {
        stopVertx(entry.getValue());
      }
      vertxPlatforms.clear();
      vertxHolders.clear();

    } catch (Exception e) {
      log.log(Level.SEVERE, "Error closing Vert.x instance", e.getCause());
    }
  }

  private void stopVertx(Vertx vertx) {

    CountDownLatch latch = new CountDownLatch(1);
    vertx.close(ar -> {
      try {

        if (ar.succeeded()) {
          log.log(Level.INFO, "Closed Vert.x platform");
        } else {
          log.log(Level.WARNING, "Could not close Vert.x platform.",
              ar.cause());
        }
      } finally {
        latch.countDown();
      }
    });

    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        log.log(Level.WARNING, "Could not close Vert.x platform");
      }
    } catch (Exception ignore) {
    }

  }

  /**
   * The Listener to monitor whether the embedded vert.x runtime is ready.
   *
   */
  public interface VertxListener {

    /**
     * When vertx is ready, maybe just started, or have been started already.
     *
     * NOTE: can't call vertxPlatforms related methods within this callback
     * method, which will cause infinite waiting.
     *
     * @param vertx
     *          the Vert.x
     */
    void whenReady(Vertx vertx);

  }

}
