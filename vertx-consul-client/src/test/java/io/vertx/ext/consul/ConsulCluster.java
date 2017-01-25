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
package io.vertx.ext.consul;

import com.pszymczyk.consul.ConsulProcess;
import com.pszymczyk.consul.ConsulStarterBuilder;
import com.pszymczyk.consul.LogLevel;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:ruslan.sennov@gmail.com">Ruslan Sennov</a>
 */
class ConsulCluster {

  private static final String MASTER_TOKEN = "topSecret";
  private static final String DC = "test-dc";
  private static final String NODE_NAME = "nodeName";
  private static final String CONSUL_VERSION = "0.7.2";

  private static ConsulCluster instance;

  private static ConsulCluster instance() {
    if (instance == null) {
      synchronized (ConsulCluster.class) {
        if (instance == null) {
          instance = new ConsulCluster();
        }
      }
    }
    return instance;
  }

  static ConsulProcess consul() {
    return instance().consul;
  }

  static void close() {
    instance().consul.close();
  }

  static String dc() {
    return DC;
  }

  static String masterToken() {
    return MASTER_TOKEN;
  }

  static String writeToken() {
    return instance().writeToken;
  }

  static String readToken() {
    return instance().readToken;
  }

  static int httpsPort() {
    return instance().httpsPort;
  }

  static String nodeName() {
    return NODE_NAME;
  }

  private ConsulProcess consul;
  private String writeToken;
  private String readToken;
  private int httpsPort;

  private ConsulCluster() {
    try {
      create();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static ConsulProcess attach(String nodeName) {
    JsonObject config = instance().consulConfig(nodeName, Utils.getFreePort())
      .put("leave_on_terminate", true)
      .put("start_join", new JsonArray().add("127.0.0.1:" + instance().consul.getSerfLanPort()));;
    return ConsulStarterBuilder.consulStarter()
      .withLogLevel(LogLevel.ERR)
      .withConsulVersion(CONSUL_VERSION)
      .withCustomConfig(config.encode())
      .build()
      .start();
  }

  private JsonObject consulConfig(String nodeName, int httpsPort) {
    return new JsonObject()
      .put("server", true)
      .put("key_file", copyFileFromResources("client-key.pem", "client-key"))
      .put("cert_file", copyFileFromResources("client-cert.pem", "client-cert"))
      .put("ca_file", copyFileFromResources("client-cert-root-ca.pem", "client-cert-root-ca"))
      .put("ports", new JsonObject().put("https", httpsPort))
      .put("addresses", new JsonObject().put("https", "0.0.0.0"))
      .put("datacenter", DC)
      .put("node_name", nodeName)
      .put("acl_default_policy", "deny")
      .put("acl_master_token", MASTER_TOKEN)
      .put("acl_datacenter", DC);
  }

  private void create() throws Exception {
    httpsPort = Utils.getFreePort();
    JsonObject config = consulConfig(NODE_NAME, httpsPort);
    consul = ConsulStarterBuilder.consulStarter()
      .withLogLevel(LogLevel.ERR)
      .withConsulVersion(CONSUL_VERSION)
      .withCustomConfig(config.encode())
      .build()
      .start();

    CountDownLatch latch = new CountDownLatch(2);
    Vertx vertx = Vertx.vertx();
    createToken(vertx, "write_rules.hcl", token -> {
      writeToken = token;
      latch.countDown();
    });
    createToken(vertx, "read_rules.hcl", token -> {
      readToken = token;
      latch.countDown();
    });
    latch.await(10, TimeUnit.SECONDS);
    vertx.close();

    if (writeToken == null || readToken == null) {
      throw new RuntimeException("Starting consul fails " + writeToken + "/" + readToken);
    }
  }

  private void createToken(Vertx vertx, String rules, Handler<String> tokenHandler) {
    HttpClientOptions httpClientOptions = new HttpClientOptions().setDefaultPort(consul.getHttpPort());
    HttpClient httpClient = vertx.createHttpClient(httpClientOptions);
    String rulesBody;
    try {
      rulesBody = Utils.readResource(rules);
    } catch (Exception e) {
      tokenHandler.handle(null);
      return;
    }
    String request = new JsonObject()
      .put("Rules", rulesBody)
      .encode();
    httpClient.put("/v1/acl/create?token=" + MASTER_TOKEN, h -> {
      if (h.statusCode() == 200) {
        h.bodyHandler(bh -> {
          JsonObject responce = new JsonObject(bh.toString());
          httpClient.close();
          tokenHandler.handle(responce.getString("ID"));
        });
      } else {
        tokenHandler.handle(null);
      }
    }).end(request);
  }

  private static String copyFileFromResources(String fName, String toName) {
    try {
      String body = Utils.readResource(fName);
      File temp = File.createTempFile(toName, ".pem");
      PrintWriter out = new PrintWriter(temp.getAbsoluteFile());
      out.print(body);
      out.close();
      return temp.getAbsolutePath();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }
}