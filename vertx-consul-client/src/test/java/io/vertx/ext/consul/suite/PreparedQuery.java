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

import io.vertx.ext.consul.ConsulTestBase;
import io.vertx.ext.consul.PreparedQueryDefinition;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author <a href="mailto:ruslan.sennov@gmail.com">Ruslan Sennov</a>
 */
@RunWith(VertxUnitRunner.class)
public class PreparedQuery extends ConsulTestBase {

  @Test
  public void createAndDestroy(TestContext tc) {
    Async async = tc.async();
    String service = randomFooBarAlpha();
    ctx.rxWriteClient()
      .rxCreatePreparedQuery(new PreparedQueryDefinition().setService(service))
      .flatMap(id -> ctx.rxReadClient()
        .rxGetPreparedQuery(id)
        .map(check(q -> tc.assertTrue(q.getService().equals(service))))
        .flatMap(list -> ctx.rxWriteClient().rxDeletePreparedQuery(id)))
      .subscribe(o -> async.complete(), tc::fail);
  }
}
