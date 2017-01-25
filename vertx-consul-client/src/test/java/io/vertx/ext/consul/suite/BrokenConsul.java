/*
 * Copyright (c) 2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.consul.suite;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.ext.consul.ConsulTestBase;
import io.vertx.ext.consul.Utils;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:ruslan.sennov@gmail.com">Ruslan Sennov</a>
 */
public class BrokenConsul extends ConsulTestBase {

  @Test
  public void timeout() {
    SlowHttpServer slowConsul = new SlowHttpServer(vertx, 10000);
    ConsulClient client = clientCreator.apply(vertx, new ConsulClientOptions().setPort(slowConsul.port()).setTimeoutMs(2000));
    client.agentInfo(h -> {
      if (h.failed() && h.cause().getMessage().contains("The timeout period of 2000ms")) {
        testComplete();
      }
    });
    await();
    clientCloser.accept(client);
    slowConsul.close();
  }

  @Test
  public void closedConnection() {
    BrokenHttpServer brokenConsul = new BrokenHttpServer(vertx);
    ConsulClient client = clientCreator.apply(vertx, new ConsulClientOptions().setPort(brokenConsul.port()));
    client.agentInfo(h -> {
      if (h.failed() && h.cause().getMessage().contains("Connection was closed")) {
        testComplete();
      }
    });
    await();
    clientCloser.accept(client);
    brokenConsul.close();
  }

  static class SlowHttpServer extends CustomHttpServer {
    SlowHttpServer(Vertx vertx, long delay) {
      super(vertx, h -> vertx.setTimer(delay, t -> h.response().end()));
    }
  }

  static class BrokenHttpServer extends CustomHttpServer {
    BrokenHttpServer(Vertx vertx) {
      super(vertx, h -> h.response().putHeader(HttpHeaders.CONTENT_LENGTH, "10000").write("start and ... ").close());
    }
  }

  static class CustomHttpServer {

    private final HttpServer server;
    private final int port;

    CustomHttpServer(Vertx vertx, Handler<HttpServerRequest> handler) {
      this.port = Utils.getFreePort();
      CountDownLatch latch = new CountDownLatch(1);
      this.server = vertx.createHttpServer()
        .requestHandler(handler)
        .listen(port, h -> latch.countDown());
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    int port() {
      return port;
    }

    void close() {
      server.close();
    }
  }
}