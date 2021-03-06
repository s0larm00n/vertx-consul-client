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
package io.vertx.ext.consul.impl;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.consul.*;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.vertx.ext.consul.impl.Utils.listOf;
import static io.vertx.ext.consul.impl.Utils.urlEncode;

/**
 * @author <a href="mailto:ruslan.sennov@gmail.com">Ruslan Sennov</a>
 */
public class ConsulClientImpl implements ConsulClient {

  private static final String TOKEN_HEADER = "X-Consul-Token";
  private static final String INDEX_HEADER = "X-Consul-Index";

  private static final List<Integer> DEFAULT_VALID_CODES = Collections.singletonList(HttpResponseStatus.OK.code());
  private static final List<Integer> TXN_VALID_CODES = Arrays.asList(HttpResponseStatus.OK.code(), HttpResponseStatus.CONFLICT.code());
  private static final List<Integer> KV_VALID_CODES = Arrays.asList(HttpResponseStatus.OK.code(), HttpResponseStatus.NOT_FOUND.code());

  private final WebClient webClient;
  private final Context ctx;
  private final String aclToken;
  private final String dc;
  private final long timeoutMs;

  public ConsulClientImpl(Vertx vertx, ConsulClientOptions options) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(options);
    webClient = WebClient.create(vertx, options);
    ctx = vertx.getOrCreateContext();
    aclToken = options.getAclToken();
    dc = options.getDc();
    timeoutMs = options.getTimeout();
  }

  @Override
  public ConsulClient agentInfo(Handler<AsyncResult<JsonObject>> resultHandler) {
    requestObject(HttpMethod.GET, "/v1/agent/self", null, null, resultHandler, (obj, headers) -> obj);
    return this;
  }

  @Override
  public Future<JsonObject> agentInfo() {
    Promise<JsonObject> promise = Promise.promise();
    agentInfo(promise);
    return promise.future();
  }

  @Override
  public ConsulClient coordinateNodes(Handler<AsyncResult<CoordinateList>> resultHandler) {
    return coordinateNodesWithOptions(null, resultHandler);
  }

  @Override
  public Future<CoordinateList> coordinateNodes() {
    Promise<CoordinateList> promise = Promise.promise();
    coordinateNodes(promise);
    return promise.future();
  }

  @Override
  public ConsulClient coordinateNodesWithOptions(BlockingQueryOptions options, Handler<AsyncResult<CoordinateList>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/coordinate/nodes", new Query().put(options), null, resultHandler, (arr, headers) -> {
      List<Coordinate> list = arr.stream().map(obj -> CoordinateParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new CoordinateList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<CoordinateList> coordinateNodesWithOptions(BlockingQueryOptions options) {
    Promise<CoordinateList> promise = Promise.promise();
    coordinateNodesWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient coordinateDatacenters(Handler<AsyncResult<List<DcCoordinates>>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/coordinate/datacenters", null, null, resultHandler, (arr, headers) ->
      arr.stream().map(obj -> CoordinateParser.parseDc((JsonObject) obj)).collect(Collectors.toList())
    );
    return this;
  }

  @Override
  public Future<List<DcCoordinates>> coordinateDatacenters() {
    Promise<List<DcCoordinates>> promise = Promise.promise();
    coordinateDatacenters(promise);
    return promise.future();
  }

  @Override
  public ConsulClient getKeys(String keyPrefix, Handler<AsyncResult<List<String>>> resultHandler) {
    return getKeysWithOptions(keyPrefix, null, resultHandler);
  }

  @Override
  public Future<List<String>> getKeys(String keyPrefix) {
    Promise<List<String>> promise = Promise.promise();
    getKeys(keyPrefix, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getKeysWithOptions(String keyPrefix, BlockingQueryOptions options, Handler<AsyncResult<List<String>>> resultHandler) {
    Query query = Query.of("recurse", true).put("keys", true).put(options);
    request(KV_VALID_CODES, HttpMethod.GET, "/v1/kv/" + urlEncode(keyPrefix), query, null, resultHandler, resp -> {
      if (resp.statusCode() == HttpResponseStatus.NOT_FOUND.code()) {
        return new ArrayList<>();
      } else {
        return resp.bodyAsJsonArray().stream().map(Object::toString).collect(Collectors.toList());
      }
    });
    return this;
  }

  @Override
  public Future<List<String>> getKeysWithOptions(String keyPrefix, BlockingQueryOptions options) {
    Promise<List<String>> promise = Promise.promise();
    getKeysWithOptions(keyPrefix, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getValue(String key, Handler<AsyncResult<KeyValue>> resultHandler) {
    return getValueWithOptions(key, null, resultHandler);
  }

  @Override
  public Future<KeyValue> getValue(String key) {
    Promise<KeyValue> promise = Promise.promise();
    getValue(key, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getValueWithOptions(String key, BlockingQueryOptions options, Handler<AsyncResult<KeyValue>> resultHandler) {
    request(KV_VALID_CODES, HttpMethod.GET, "/v1/kv/" + urlEncode(key), new Query().put(options), null, resultHandler, resp -> {
      if (resp.statusCode() == HttpResponseStatus.NOT_FOUND.code()) {
        return new KeyValue();
      } else {
        return KVParser.parse(resp.bodyAsJsonArray().getJsonObject(0));
      }
    });
    return this;
  }

  @Override
  public Future<KeyValue> getValueWithOptions(String key, BlockingQueryOptions options) {
    Promise<KeyValue> promise = Promise.promise();
    getValueWithOptions(key, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient deleteValue(String key, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.DELETE, "/v1/kv/" + urlEncode(key), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> deleteValue(String key) {
    Promise<Void> promise = Promise.promise();
    deleteValue(key, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getValues(String keyPrefix, Handler<AsyncResult<KeyValueList>> resultHandler) {
    return getValuesWithOptions(keyPrefix, null, resultHandler);
  }

  @Override
  public Future<KeyValueList> getValues(String keyPrefix) {
    Promise<KeyValueList> promise = Promise.promise();
    getValues(keyPrefix, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getValuesWithOptions(String keyPrefix, BlockingQueryOptions options, Handler<AsyncResult<KeyValueList>> resultHandler) {
    Query query = Query.of("recurse", true).put(options);
    request(KV_VALID_CODES, HttpMethod.GET, "/v1/kv/" + urlEncode(keyPrefix), query, null, resultHandler, resp -> {
      if (resp.statusCode() == HttpResponseStatus.NOT_FOUND.code()) {
        return new KeyValueList();
      } else {
        List<KeyValue> list = resp.bodyAsJsonArray().stream().map(obj -> KVParser.parse((JsonObject) obj)).collect(Collectors.toList());
        return new KeyValueList().setList(list).setIndex(Long.parseLong(resp.headers().get(INDEX_HEADER)));
      }
    });
    return this;
  }

  @Override
  public Future<KeyValueList> getValuesWithOptions(String keyPrefix, BlockingQueryOptions options) {
    Promise<KeyValueList> promise = Promise.promise();
    getValuesWithOptions(keyPrefix, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient deleteValues(String keyPrefix, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.DELETE, "/v1/kv/" + urlEncode(keyPrefix), Query.of("recurse", true), null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> deleteValues(String keyPrefix) {
    Promise<Void> promise = Promise.promise();
    deleteValues(keyPrefix, promise);
    return promise.future();
  }

  @Override
  public ConsulClient putValue(String key, String value, Handler<AsyncResult<Boolean>> resultHandler) {
    return putValueWithOptions(key, value, null, resultHandler);
  }

  @Override
  public Future<Boolean> putValue(String key, String value) {
    Promise<Boolean> promise = Promise.promise();
    putValue(key, value, promise);
    return promise.future();
  }

  @Override
  public ConsulClient putValueWithOptions(String key, String value, KeyValueOptions options, Handler<AsyncResult<Boolean>> resultHandler) {
    Query query = new Query();
    if (options != null) {
      query.put("flags", Long.toUnsignedString(options.getFlags()))
        .put("acquire", options.getAcquireSession())
        .put("release", options.getReleaseSession());
      long cas = options.getCasIndex();
      if (cas >= 0) {
        query.put("cas", cas);
      }
    }
    requestString(HttpMethod.PUT, "/v1/kv/" + urlEncode(key), query, value, resultHandler,
      (bool, headers) -> Boolean.valueOf(bool));
    return this;
  }

  @Override
  public Future<Boolean> putValueWithOptions(String key, String value, KeyValueOptions options) {
    Promise<Boolean> promise = Promise.promise();
    putValueWithOptions(key, value, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient transaction(TxnRequest request, Handler<AsyncResult<TxnResponse>> resultHandler) {
    String boby = request.toJson().getJsonArray("operations").encode();
    request(TXN_VALID_CODES, HttpMethod.PUT, "/v1/txn", null, boby, resultHandler, resp -> TxnResponseParser.parse(resp.bodyAsJsonObject()));
    return this;
  }

  @Override
  public Future<TxnResponse> transaction(TxnRequest request) {
    Promise<TxnResponse> promise = Promise.promise();
    transaction(request, promise);
    return promise.future();
  }

  @Override
  public ConsulClient createAclToken(AclToken token, Handler<AsyncResult<String>> idHandler) {
    requestObject(HttpMethod.PUT, "/v1/acl/create", null, token.toJson().encode(), idHandler, (obj, headers) ->
      obj.getString("ID"));
    return this;
  }

  @Override
  public Future<String> createAclToken(AclToken token) {
    Promise<String> promise = Promise.promise();
    createAclToken(token, promise);
    return promise.future();
  }

  @Override
  public ConsulClient updateAclToken(AclToken token, Handler<AsyncResult<String>> idHandler) {
    requestObject(HttpMethod.PUT, "/v1/acl/update", null, token.toJson().encode(), idHandler, (obj, headers) ->
      obj.getString("ID"));
    return this;
  }

  @Override
  public Future<String> updateAclToken(AclToken token) {
    Promise<String> promise = Promise.promise();
    updateAclToken(token, promise);
    return promise.future();
  }

  @Override
  public ConsulClient cloneAclToken(String id, Handler<AsyncResult<String>> idHandler) {
    requestObject(HttpMethod.PUT, "/v1/acl/clone/" + urlEncode(id), null, null, idHandler, (obj, headers) ->
      obj.getString("ID"));
    return this;
  }

  @Override
  public Future<String> cloneAclToken(String id) {
    Promise<String> promise = Promise.promise();
    cloneAclToken(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient listAclTokens(Handler<AsyncResult<List<AclToken>>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/acl/list", null, null, resultHandler, (arr, headers) ->
      arr.stream()
        .map(obj -> new AclToken((JsonObject) obj))
        .collect(Collectors.toList()));
    return this;
  }

  @Override
  public Future<List<AclToken>> listAclTokens() {
    Promise<List<AclToken>> promise = Promise.promise();
    listAclTokens(promise);
    return promise.future();
  }

  @Override
  public ConsulClient infoAclToken(String id, Handler<AsyncResult<AclToken>> tokenHandler) {
    requestArray(HttpMethod.GET, "/v1/acl/info/" + urlEncode(id), null, null, tokenHandler, (arr, headers) -> {
      JsonObject jsonObject = arr.getJsonObject(0);
      return new AclToken(jsonObject);
    });
    return this;
  }

  @Override
  public Future<AclToken> infoAclToken(String id) {
    Promise<AclToken> promise = Promise.promise();
    infoAclToken(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient destroyAclToken(String id, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/acl/destroy/" + urlEncode(id), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> destroyAclToken(String id) {
    Promise<Void> promise = Promise.promise();
    destroyAclToken(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient fireEvent(String name, Handler<AsyncResult<Event>> resultHandler) {
    fireEventWithOptions(name, null, resultHandler);
    return this;
  }

  @Override
  public Future<Event> fireEvent(String name) {
    Promise<Event> promise = Promise.promise();
    fireEvent(name, promise);
    return promise.future();
  }

  @Override
  public ConsulClient fireEventWithOptions(String name, EventOptions options, Handler<AsyncResult<Event>> resultHandler) {
    Query query = new Query();
    if (options != null) {
      query.put("node", options.getNode()).put("service", options.getService()).put("tag", options.getTag());
    }
    String body = options == null || options.getPayload() == null ? "" : options.getPayload();
    requestObject(HttpMethod.PUT, "/v1/event/fire/" + urlEncode(name), query, body, resultHandler, (jsonObject, headers) -> EventParser.parse(jsonObject));
    return this;
  }

  @Override
  public Future<Event> fireEventWithOptions(String name, EventOptions options) {
    Promise<Event> promise = Promise.promise();
    fireEventWithOptions(name, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient listEvents(Handler<AsyncResult<EventList>> resultHandler) {
    listEventsWithOptions(null, resultHandler);
    return this;
  }

  @Override
  public Future<EventList> listEvents() {
    Promise<EventList> promise = Promise.promise();
    listEvents(promise);
    return promise.future();
  }

  @Override
  public ConsulClient listEventsWithOptions(EventListOptions options, Handler<AsyncResult<EventList>> resultHandler) {
    Query query = options == null ? null : Query.of(options.getBlockingOptions()).put("name", options.getName());
    requestArray(HttpMethod.GET, "/v1/event/list", query, null, resultHandler, (jsonArray, headers) -> {
      List<Event> list = jsonArray.stream().map(obj -> EventParser.parse(((JsonObject) obj))).collect(Collectors.toList());
      return new EventList().setList(list).setIndex(Long.parseUnsignedLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<EventList> listEventsWithOptions(EventListOptions options) {
    Promise<EventList> promise = Promise.promise();
    listEventsWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient registerService(ServiceOptions serviceOptions, Handler<AsyncResult<Void>> resultHandler) {
    JsonObject jsonOpts = new JsonObject()
      .put("ID", serviceOptions.getId())
      .put("Name", serviceOptions.getName())
      .put("Tags", serviceOptions.getTags())
      .put("Address", serviceOptions.getAddress())
      .put("Port", serviceOptions.getPort());
    if (serviceOptions.getCheckOptions() != null) {
      jsonOpts.put("Check", checkOpts(serviceOptions.getCheckOptions(), "CheckID", false));
    }
    if (serviceOptions.getCheckListOptions() != null) {
      jsonOpts.put("Checks", checkListOpts(serviceOptions.getCheckListOptions(), "CheckID", false));
    }
    if (serviceOptions.getMeta() != null && !serviceOptions.getMeta().isEmpty()) {
      jsonOpts.put("Meta", serviceOptions.getMeta());
    }
    requestVoid(HttpMethod.PUT, "/v1/agent/service/register", null, jsonOpts.encode(), resultHandler);
    return this;
  }

  @Override
  public Future<Void> registerService(ServiceOptions serviceOptions) {
    Promise<Void> promise = Promise.promise();
    registerService(serviceOptions, promise);
    return promise.future();
  }

  @Override
  public ConsulClient maintenanceService(MaintenanceOptions opts, Handler<AsyncResult<Void>> resultHandler) {
    Query query = Query.of("enable", opts.isEnable()).put("reason", opts.getReason());
    requestVoid(HttpMethod.PUT, "/v1/agent/service/maintenance/" + urlEncode(opts.getId()), query, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> maintenanceService(MaintenanceOptions maintenanceOptions) {
    Promise<Void> promise = Promise.promise();
    maintenanceService(maintenanceOptions, promise);
    return promise.future();
  }

  @Override
  public ConsulClient deregisterService(String id, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/service/deregister/" + urlEncode(id), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> deregisterService(String id) {
    Promise<Void> promise = Promise.promise();
    deregisterService(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogServiceNodes(String service, Handler<AsyncResult<ServiceList>> resultHandler) {
    return catalogServiceNodesWithOptions(service, null, resultHandler);
  }

  @Override
  public Future<ServiceList> catalogServiceNodes(String service) {
    Promise<ServiceList> promise = Promise.promise();
    catalogServiceNodes(service, promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogServiceNodesWithOptions(String service, ServiceQueryOptions options, Handler<AsyncResult<ServiceList>> resultHandler) {
    Query query = options == null ? null : Query.of("tag", options.getTag()).put("near", options.getNear()).put(options.getBlockingOptions());
    requestArray(HttpMethod.GET, "/v1/catalog/service/" + urlEncode(service), query, null, resultHandler, (arr, headers) -> {
      List<Service> list = arr.stream().map(obj -> new Service((JsonObject) obj)).collect(Collectors.toList());
      return new ServiceList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<ServiceList> catalogServiceNodesWithOptions(String service, ServiceQueryOptions options) {
    Promise<ServiceList> promise = Promise.promise();
    catalogServiceNodesWithOptions(service, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogDatacenters(Handler<AsyncResult<List<String>>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/catalog/datacenters", null, null, resultHandler, (arr, headers) -> listOf(arr));
    return this;
  }

  @Override
  public Future<List<String>> catalogDatacenters() {
    Promise<List<String>> promise = Promise.promise();
    catalogDatacenters(promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogNodes(Handler<AsyncResult<NodeList>> resultHandler) {
    return catalogNodesWithOptions(null, resultHandler);
  }

  @Override
  public Future<NodeList> catalogNodes() {
    Promise<NodeList> promise = Promise.promise();
    catalogNodes(promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogNodesWithOptions(NodeQueryOptions options, Handler<AsyncResult<NodeList>> resultHandler) {
    Query query = options == null ? null : Query.of("near", options.getNear()).put(options.getBlockingOptions());
    requestArray(HttpMethod.GET, "/v1/catalog/nodes", query, null, resultHandler, (arr, headers) -> {
      List<Node> list = arr.stream().map(obj -> NodeParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new NodeList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<NodeList> catalogNodesWithOptions(NodeQueryOptions options) {
    Promise<NodeList> promise = Promise.promise();
    catalogNodesWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthChecks(String service, Handler<AsyncResult<CheckList>> resultHandler) {
    return healthChecksWithOptions(service, null, resultHandler);
  }

  @Override
  public Future<CheckList> healthChecks(String service) {
    Promise<CheckList> promise = Promise.promise();
    healthChecks(service, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthChecksWithOptions(String service, CheckQueryOptions options, Handler<AsyncResult<CheckList>> resultHandler) {
    Query query = options == null ? null : Query.of("near", options.getNear()).put(options.getBlockingOptions());
    requestArray(HttpMethod.GET, "/v1/health/checks/" + urlEncode(service), query, null, resultHandler, (arr, headers) -> {
      List<Check> list = arr.stream().map(obj -> CheckParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new CheckList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<CheckList> healthChecksWithOptions(String service, CheckQueryOptions options) {
    Promise<CheckList> promise = Promise.promise();
    healthChecksWithOptions(service, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthState(HealthState healthState, Handler<AsyncResult<CheckList>> resultHandler) {
    return healthStateWithOptions(healthState, null, resultHandler);
  }

  @Override
  public Future<CheckList> healthState(HealthState healthState) {
    Promise<CheckList> promise = Promise.promise();
    healthState(healthState, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthStateWithOptions(HealthState healthState, CheckQueryOptions options, Handler<AsyncResult<CheckList>> resultHandler) {
    Query query = options == null ? null : Query.of("near", options.getNear()).put(options.getBlockingOptions());
    requestArray(HttpMethod.GET, "/v1/health/state/" + healthState.key, query, null, resultHandler, (arr, headers) -> {
      List<Check> list = arr.stream().map(obj -> CheckParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new CheckList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<CheckList> healthStateWithOptions(HealthState healthState, CheckQueryOptions options) {
    Promise<CheckList> promise = Promise.promise();
    healthStateWithOptions(healthState, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthServiceNodes(String service, boolean passing, Handler<AsyncResult<ServiceEntryList>> resultHandler) {
    return healthServiceNodesWithOptions(service, passing, null, resultHandler);
  }

  @Override
  public Future<ServiceEntryList> healthServiceNodes(String service, boolean passing) {
    Promise<ServiceEntryList> promise = Promise.promise();
    healthServiceNodes(service, passing, promise);
    return promise.future();
  }

  @Override
  public ConsulClient healthServiceNodesWithOptions(String service, boolean passing, ServiceQueryOptions options, Handler<AsyncResult<ServiceEntryList>> resultHandler) {
    Query query = new Query().put("passing", passing ? 1 : null);
    if (options != null) {
      query.put(options.getBlockingOptions()).put("near", options.getNear()).put("tag", options.getTag());
    }
    requestArray(HttpMethod.GET, "/v1/health/service/" + urlEncode(service), query, null, resultHandler, (arr, headers) -> {
      List<ServiceEntry> list = arr.stream().map(obj -> ServiceEntryParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new ServiceEntryList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<ServiceEntryList> healthServiceNodesWithOptions(String service, boolean passing, ServiceQueryOptions options) {
    Promise<ServiceEntryList> promise = Promise.promise();
    healthServiceNodesWithOptions(service, passing, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogServices(Handler<AsyncResult<ServiceList>> resultHandler) {
    return catalogServicesWithOptions(null, resultHandler);
  }

  @Override
  public Future<ServiceList> catalogServices() {
    Promise<ServiceList> promise = Promise.promise();
    catalogServices(promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogServicesWithOptions(BlockingQueryOptions options, Handler<AsyncResult<ServiceList>> resultHandler) {
    requestObject(HttpMethod.GET, "/v1/catalog/services", Query.of(options), null, resultHandler, (json, headers) -> {
      List<Service> list = json.stream().map(ServiceParser::parseCatalogInfo).collect(Collectors.toList());
      return new ServiceList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<ServiceList> catalogServicesWithOptions(BlockingQueryOptions options) {
    Promise<ServiceList> promise = Promise.promise();
    catalogServicesWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient localChecks(Handler<AsyncResult<List<Check>>> resultHandler) {
    requestObject(HttpMethod.GET, "/v1/agent/checks", null, null, resultHandler, (json, headers) -> json.stream()
      .map(obj -> CheckParser.parse((JsonObject) obj.getValue()))
      .collect(Collectors.toList()));
    return this;
  }

  @Override
  public Future<List<Check>> localChecks() {
    Promise<List<Check>> promise = Promise.promise();
    localChecks(promise);
    return promise.future();
  }

  @Override
  public ConsulClient localServices(Handler<AsyncResult<List<Service>>> resultHandler) {
    requestObject(HttpMethod.GET, "/v1/agent/services", null, null, resultHandler, (json, headers) -> json.stream()
      .map(obj -> ServiceParser.parseAgentInfo((JsonObject) obj.getValue()))
      .collect(Collectors.toList()));
    return this;
  }

  @Override
  public Future<List<Service>> localServices() {
    Promise<List<Service>> promise = Promise.promise();
    localServices(promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogNodeServices(String node, Handler<AsyncResult<ServiceList>> resultHandler) {
    return catalogNodeServicesWithOptions(node, null, resultHandler);
  }

  @Override
  public Future<ServiceList> catalogNodeServices(String node) {
    Promise<ServiceList> promise = Promise.promise();
    catalogNodeServices(node, promise);
    return promise.future();
  }

  @Override
  public ConsulClient catalogNodeServicesWithOptions(String node, BlockingQueryOptions options, Handler<AsyncResult<ServiceList>> resultHandler) {
    requestObject(HttpMethod.GET, "/v1/catalog/node/" + urlEncode(node), Query.of(options), null, resultHandler, (json, headers) -> {
      JsonObject nodeInfo = json.getJsonObject("Node");
      String nodeName = nodeInfo.getString("Node");
      String nodeAddress = nodeInfo.getString("Address");
      List<Service> list = json.getJsonObject("Services").stream()
        .map(obj -> ServiceParser.parseNodeInfo(nodeName, nodeAddress, (JsonObject) obj.getValue()))
        .collect(Collectors.toList());
      return new ServiceList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<ServiceList> catalogNodeServicesWithOptions(String node, BlockingQueryOptions options) {
    Promise<ServiceList> promise = Promise.promise();
    catalogNodeServicesWithOptions(node, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient registerCheck(CheckOptions checkOptions, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/check/register", null, checkOpts(checkOptions, "ID", true).encode(), resultHandler);
    return this;
  }

  @Override
  public Future<Void> registerCheck(CheckOptions checkOptions) {
    Promise<Void> promise = Promise.promise();
    registerCheck(checkOptions, promise);
    return promise.future();
  }

  private static JsonObject checkOpts(CheckOptions checkOptions, String checkIdKey, boolean extended) {
    JsonObject json = new JsonObject()
      .put(checkIdKey, checkOptions.getId())
      .put("Name", checkOptions.getName())
      .put("Notes", checkOptions.getNotes())
      .put("ScriptArgs", checkOptions.getScriptArgs())
      .put("HTTP", checkOptions.getHttp())
      .put("TLSSkipVerify", checkOptions.isTlsSkipVerify())
      .put("GRPC", checkOptions.getGrpc())
      .put("Interval", checkOptions.getInterval())
      .put("TTL", checkOptions.getTtl())
      .put("TCP", checkOptions.getTcp());
    if (checkOptions.getGrpc() != null) {
      json.put("GRPCUseTLS", checkOptions.isGrpcTls());
    }
    if (checkOptions.getDeregisterAfter() != null) {
      json.put("DeregisterCriticalServiceAfter", checkOptions.getDeregisterAfter());
    }
    if (checkOptions.getStatus() != null) {
      json.put("Status", checkOptions.getStatus().key);
    }
    if (extended && checkOptions.getServiceId() != null) {
      json.put("ServiceID", checkOptions.getServiceId());
    }
    return json;
  }

  private static JsonArray checkListOpts(List<CheckOptions> listChecks, String checkIdKey, boolean extended) {
    JsonArray jsonArray = new JsonArray();
    listChecks.stream().map(c -> checkOpts(c, checkIdKey,extended)).forEach(jsonArray::add);
    return jsonArray;
  }

  @Override
  public ConsulClient deregisterCheck(String checkId, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/check/deregister/" + urlEncode(checkId), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> deregisterCheck(String checkId) {
    Promise<Void> promise = Promise.promise();
    deregisterCheck(checkId, promise);
    return promise.future();
  }

  @Override
  public ConsulClient passCheck(String checkId, Handler<AsyncResult<Void>> resultHandler) {
    return passCheckWithNote(checkId, null, resultHandler);
  }

  @Override
  public Future<Void> passCheck(String checkId) {
    Promise<Void> promise = Promise.promise();
    passCheck(checkId, promise);
    return promise.future();
  }

  @Override
  public ConsulClient passCheckWithNote(String checkId, String note, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/check/pass/" + urlEncode(checkId), Query.of("note", note), null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> passCheckWithNote(String checkId, String note) {
    Promise<Void> promise = Promise.promise();
    passCheckWithNote(checkId, note, promise);
    return promise.future();
  }

  @Override
  public ConsulClient warnCheck(String checkId, Handler<AsyncResult<Void>> resultHandler) {
    return warnCheckWithNote(checkId, null, resultHandler);
  }

  @Override
  public Future<Void> warnCheck(String checkId) {
    Promise<Void> promise = Promise.promise();
    warnCheck(checkId, promise);
    return promise.future();
  }

  @Override
  public ConsulClient warnCheckWithNote(String checkId, String note, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/check/warn/" + urlEncode(checkId), Query.of("note", note), null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> warnCheckWithNote(String checkId, String note) {
    Promise<Void> promise = Promise.promise();
    warnCheckWithNote(checkId, note, promise);
    return promise.future();
  }

  @Override
  public ConsulClient failCheck(String checkId, Handler<AsyncResult<Void>> resultHandler) {
    return failCheckWithNote(checkId, null, resultHandler);
  }

  @Override
  public Future<Void> failCheck(String checkId) {
    Promise<Void> promise = Promise.promise();
    failCheck(checkId, promise);
    return promise.future();
  }

  @Override
  public ConsulClient failCheckWithNote(String checkId, String note, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/agent/check/fail/" + urlEncode(checkId), Query.of("note", note), null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> failCheckWithNote(String checkId, String note) {
    Promise<Void> promise = Promise.promise();
    failCheckWithNote(checkId, note, promise);
    return promise.future();
  }

  @Override
  public ConsulClient updateCheck(String checkId, CheckStatus status, Handler<AsyncResult<Void>> resultHandler) {
    return updateCheckWithNote(checkId, status, null, resultHandler);
  }

  @Override
  public Future<Void> updateCheck(String checkId, CheckStatus status) {
    Promise<Void> promise = Promise.promise();
    updateCheck(checkId, status, promise);
    return promise.future();
  }

  @Override
  public ConsulClient updateCheckWithNote(String checkId, CheckStatus status, String note, Handler<AsyncResult<Void>> resultHandler) {
    JsonObject put = new JsonObject().put("Status", status.key);
    if (note != null) {
      put.put("Output", note);
    }
    requestVoid(HttpMethod.PUT, "/v1/agent/check/update/" + urlEncode(checkId), null, put.encode(), resultHandler);
    return this;
  }

  @Override
  public Future<Void> updateCheckWithNote(String checkId, CheckStatus status, String note) {
    Promise<Void> promise = Promise.promise();
    updateCheckWithNote(checkId, status, note, promise);
    return promise.future();
  }

  @Override
  public ConsulClient leaderStatus(Handler<AsyncResult<String>> resultHandler) {
    requestString(HttpMethod.GET, "/v1/status/leader", null, null, resultHandler, (leader, headers) ->
      leader.substring(1, leader.length() - 2));
    return this;
  }

  @Override
  public Future<String> leaderStatus() {
    Promise<String> promise = Promise.promise();
    leaderStatus(promise);
    return promise.future();
  }

  @Override
  public ConsulClient peersStatus(Handler<AsyncResult<List<String>>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/status/peers", null, null, resultHandler, (arr, headers) -> arr.stream()
      .map(obj -> (String) obj)
      .collect(Collectors.toList()));
    return this;
  }

  @Override
  public Future<List<String>> peersStatus() {
    Promise<List<String>> promise = Promise.promise();
    peersStatus(promise);
    return promise.future();
  }

  @Override
  public ConsulClient createSession(Handler<AsyncResult<String>> idHandler) {
    createSessionWithOptions(null, idHandler);
    return this;
  }

  @Override
  public Future<String> createSession() {
    Promise<String> promise = Promise.promise();
    createSession(promise);
    return promise.future();
  }

  @Override
  public ConsulClient createSessionWithOptions(SessionOptions options, Handler<AsyncResult<String>> idHandler) {
    String body = options == null ? null : options.toJson().encode();
    requestObject(HttpMethod.PUT, "/v1/session/create", null, body, idHandler, (obj, headers) -> obj.getString("ID"));
    return this;
  }

  @Override
  public Future<String> createSessionWithOptions(SessionOptions options) {
    Promise<String> promise = Promise.promise();
    createSessionWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient infoSession(String id, Handler<AsyncResult<Session>> resultHandler) {
    return infoSessionWithOptions(id, null, resultHandler);
  }

  @Override
  public Future<Session> infoSession(String id) {
    Promise<Session> promise = Promise.promise();
    infoSession(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient infoSessionWithOptions(String id, BlockingQueryOptions options, Handler<AsyncResult<Session>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/session/info/" + urlEncode(id), Query.of(options), null, resultHandler, (sessions, headers) -> {
      if (sessions.size() == 0) {
        throw new RuntimeException("Unknown session ID: " + id);
      } else {
        return SessionParser.parse(sessions.getJsonObject(0), Long.parseLong(headers.get(INDEX_HEADER)));
      }
    });
    return this;
  }

  @Override
  public Future<Session> infoSessionWithOptions(String id, BlockingQueryOptions options) {
    Promise<Session> promise = Promise.promise();
    infoSessionWithOptions(id, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient renewSession(String id, Handler<AsyncResult<Session>> resultHandler) {
    requestArray(HttpMethod.PUT, "/v1/session/renew/" + urlEncode(id), null, null, resultHandler, (arr, headers) ->
      SessionParser.parse(arr.getJsonObject(0)));
    return this;
  }

  @Override
  public Future<Session> renewSession(String id) {
    Promise<Session> promise = Promise.promise();
    renewSession(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient listSessions(Handler<AsyncResult<SessionList>> resultHandler) {
    return listSessionsWithOptions(null, resultHandler);
  }

  @Override
  public Future<SessionList> listSessions() {
    Promise<SessionList> promise = Promise.promise();
    listSessions(promise);
    return promise.future();
  }

  @Override
  public ConsulClient listSessionsWithOptions(BlockingQueryOptions options, Handler<AsyncResult<SessionList>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/session/list", Query.of(options), null, resultHandler, (arr, headers) -> {
      List<Session> list = arr.stream().map(obj -> SessionParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new SessionList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<SessionList> listSessionsWithOptions(BlockingQueryOptions options) {
    Promise<SessionList> promise = Promise.promise();
    listSessionsWithOptions(options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient listNodeSessions(String nodeId, Handler<AsyncResult<SessionList>> resultHandler) {
    return listNodeSessionsWithOptions(nodeId, null, resultHandler);
  }

  @Override
  public Future<SessionList> listNodeSessions(String nodeId) {
    Promise<SessionList> promise = Promise.promise();
    listNodeSessions(nodeId, promise);
    return promise.future();
  }

  @Override
  public ConsulClient listNodeSessionsWithOptions(String nodeId, BlockingQueryOptions options, Handler<AsyncResult<SessionList>> resultHandler) {
    requestArray(HttpMethod.GET, "/v1/session/node/" + urlEncode(nodeId), Query.of(options), null, resultHandler, (arr, headers) -> {
      List<Session> list = arr.stream().map(obj -> SessionParser.parse((JsonObject) obj)).collect(Collectors.toList());
      return new SessionList().setList(list).setIndex(Long.parseLong(headers.get(INDEX_HEADER)));
    });
    return this;
  }

  @Override
  public Future<SessionList> listNodeSessionsWithOptions(String nodeId, BlockingQueryOptions options) {
    Promise<SessionList> promise = Promise.promise();
    listNodeSessionsWithOptions(nodeId, options, promise);
    return promise.future();
  }

  @Override
  public ConsulClient destroySession(String id, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.PUT, "/v1/session/destroy/" + urlEncode(id), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> destroySession(String id) {
    Promise<Void> promise = Promise.promise();
    destroySession(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient createPreparedQuery(PreparedQueryDefinition definition, Handler<AsyncResult<String>> resultHandler) {
    requestObject(HttpMethod.POST, "/v1/query", null, definition.toJson().encode(), resultHandler, (obj, headers) -> obj.getString("ID"));
    return this;
  }

  @Override
  public Future<String> createPreparedQuery(PreparedQueryDefinition definition) {
    Promise<String> promise = Promise.promise();
    createPreparedQuery(definition, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getPreparedQuery(String id, Handler<AsyncResult<PreparedQueryDefinition>> resultHandler) {
    getPreparedQueryList(id, h -> resultHandler.handle(h.map(list -> list.get(0))));
    return this;
  }

  @Override
  public Future<PreparedQueryDefinition> getPreparedQuery(String id) {
    Promise<PreparedQueryDefinition> promise = Promise.promise();
    getPreparedQuery(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient getAllPreparedQueries(Handler<AsyncResult<List<PreparedQueryDefinition>>> resultHandler) {
    getPreparedQueryList(null, resultHandler);
    return this;
  }

  @Override
  public Future<List<PreparedQueryDefinition>> getAllPreparedQueries() {
    Promise<List<PreparedQueryDefinition>> promise = Promise.promise();
    getAllPreparedQueries(promise);
    return promise.future();
  }

  @Override
  public ConsulClient updatePreparedQuery(PreparedQueryDefinition definition, Handler<AsyncResult<Void>> resultHandler) {
    String path = "/v1/query/" + urlEncode(definition.getId());
    requestVoid(HttpMethod.PUT, path, null, definition.toJson().encode(), resultHandler);
    return this;
  }

  @Override
  public Future<Void> updatePreparedQuery(PreparedQueryDefinition definition) {
    Promise<Void> promise = Promise.promise();
    updatePreparedQuery(definition, promise);
    return promise.future();
  }

  private void getPreparedQueryList(String id, Handler<AsyncResult<List<PreparedQueryDefinition>>> resultHandler) {
    String path = "/v1/query" + (id == null ? "" : "/" + urlEncode(id));
    requestArray(HttpMethod.GET, path, null, null, resultHandler, (arr, headers) -> arr.stream()
      .map(obj -> new PreparedQueryDefinition((JsonObject) obj)).collect(Collectors.toList()));
  }

  @Override
  public ConsulClient deletePreparedQuery(String id, Handler<AsyncResult<Void>> resultHandler) {
    requestVoid(HttpMethod.DELETE, "/v1/query/" + urlEncode(id), null, null, resultHandler);
    return this;
  }

  @Override
  public Future<Void> deletePreparedQuery(String id) {
    Promise<Void> promise = Promise.promise();
    deletePreparedQuery(id, promise);
    return promise.future();
  }

  @Override
  public ConsulClient executePreparedQuery(String query, Handler<AsyncResult<PreparedQueryExecuteResponse>> resultHandler) {
    return executePreparedQueryWithOptions(query, null, resultHandler);
  }

  @Override
  public Future<PreparedQueryExecuteResponse> executePreparedQuery(String query) {
    Promise<PreparedQueryExecuteResponse> promise = Promise.promise();
    executePreparedQuery(query, promise);
    return promise.future();
  }

  @Override
  public ConsulClient executePreparedQueryWithOptions(String query, PreparedQueryExecuteOptions options, Handler<AsyncResult<PreparedQueryExecuteResponse>> resultHandler) {
    String path = "/v1/query/" + urlEncode(query) + "/execute";
    Query q = new Query();
    if (options != null) {
      q.put("near", options.getNear()).put("limit", options.getLimit());
    }
    requestObject(HttpMethod.GET, path, q, null, resultHandler, (obj, headers) -> {
      return new PreparedQueryExecuteResponse()
        .setService(obj.getString("Service"))
        .setDc(obj.getString("Datacenter"))
        .setFailovers(obj.getInteger("Failovers"))
        .setDnsTtl(obj.getJsonObject("DNS").getString("TTL"))
        .setNodes(obj.getJsonArray("Nodes").stream()
          .map(o -> ServiceEntryParser.parse((JsonObject) o))
          .collect(Collectors.toList()));
    });
    return this;
  }

  @Override
  public Future<PreparedQueryExecuteResponse> executePreparedQueryWithOptions(String query, PreparedQueryExecuteOptions options) {
    Promise<PreparedQueryExecuteResponse> promise = Promise.promise();
    executePreparedQueryWithOptions(query, options, promise);
    return promise.future();
  }

  @Override
  public void close() {
    webClient.close();
  }

  private <T> void requestArray(HttpMethod method, String path, Query query, String body,
                                Handler<AsyncResult<T>> resultHandler,
                                BiFunction<JsonArray, MultiMap, T> mapper) {
    request(DEFAULT_VALID_CODES, method, path, query, body, resultHandler, resp -> mapper.apply(resp.bodyAsJsonArray(), resp.headers()));
  }

  private <T> void requestObject(HttpMethod method, String path, Query query, String body,
                                 Handler<AsyncResult<T>> resultHandler,
                                 BiFunction<JsonObject, MultiMap, T> mapper) {
    request(DEFAULT_VALID_CODES, method, path, query, body, resultHandler, resp -> mapper.apply(resp.bodyAsJsonObject(), resp.headers()));
  }

  private <T> void requestString(HttpMethod method, String path, Query query, String body,
                                 Handler<AsyncResult<T>> resultHandler,
                                 BiFunction<String, MultiMap, T> mapper) {
    request(DEFAULT_VALID_CODES, method, path, query, body, resultHandler, resp -> mapper.apply(resp.bodyAsString().trim(), resp.headers()));
  }

  private <T> void requestVoid(HttpMethod method, String path, Query query, String body,
                               Handler<AsyncResult<T>> resultHandler) {
    request(DEFAULT_VALID_CODES, method, path, query, body, resultHandler, resp -> null);
  }

  private <T> void request(List<Integer> validCodes, HttpMethod method, String path, Query query, String body,
                           Handler<AsyncResult<T>> resultHandler,
                           Function<HttpResponse<Buffer>, T> mapper) {
    if (Vertx.currentContext() == ctx) {
      reqOnContext(validCodes, method, path, query, body, resultHandler, mapper);
    } else {
      ctx.runOnContext(v -> reqOnContext(validCodes, method, path, query, body, resultHandler, mapper));
    }
  }

  private <T> void reqOnContext(List<Integer> validCodes, HttpMethod method, String path, Query query, String body,
                                Handler<AsyncResult<T>> resultHandler,
                                Function<HttpResponse<Buffer>, T> mapper) {
    if (query == null) {
      query = new Query();
    }
    if (dc != null) {
      query.put("dc", dc);
    }
    HttpRequest<Buffer> rq = webClient.request(method, path);
    query.entrySet().forEach(e -> rq.addQueryParam(e.getKey(), e.getValue()));
    if (aclToken != null) {
      rq.putHeader(TOKEN_HEADER, aclToken);
    }
    if (timeoutMs > 0) {
      rq.timeout(timeoutMs);
    }
    try {
      rq.sendBuffer(body == null ? Buffer.buffer() : Buffer.buffer(body), h -> {
        if (h.succeeded()) {
          HttpResponse<Buffer> resp = h.result();
          if (validCodes.contains(resp.statusCode())) {
            T mapped;
            try {
              mapped = mapper.apply(resp);
            } catch (Throwable throwable) {
              resultHandler.handle(Future.failedFuture(throwable));
              return;
            }
            resultHandler.handle(Future.succeededFuture(mapped));
          } else {
            resultHandler.handle(Future.failedFuture(
              String.format("Status message: '%s'. Body: '%s' ", resp.statusMessage(), resp.bodyAsString()))
            );
          }
        } else {
          resultHandler.handle(Future.failedFuture(h.cause()));
        }
      });
    } catch (final IllegalStateException e) {
      resultHandler.handle(Future.failedFuture(e));
    }
  }

}
