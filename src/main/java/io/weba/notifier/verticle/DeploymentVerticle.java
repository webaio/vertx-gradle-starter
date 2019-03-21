/*
 * MIT License
 *
 * Copyright (c) 2019 weba.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.weba.notifier.verticle;

import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.functions.Function;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.Vertx;
import io.weba.notifier.Configuration;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeploymentVerticle extends AbstractVerticle {
  @Override
  public void start(Future<Void> future) {
    log.info("Starting deployment of {}...", this.getClass().getName());

    ConfigRetriever.create(vertx)
        .rxGetConfig()
        .flatMap(clusterVertx())
        .flatMap(deployVerticle())
        .doOnError(future::fail)
        .doOnSuccess(s -> future.complete())
        .subscribe();
  }

  private Function<DeploymentToolkit, SingleSource<? extends String>> deployVerticle() {
    return deploymentToolkit ->
        deploymentToolkit
            .vertx()
            .rxDeployVerticle(
                deploymentToolkit.config().getString(Configuration.VERTICLE_CLASS_NAME),
                new DeploymentOptions(deploymentToolkit.config()));
  }

  private Function<JsonObject, SingleSource<? extends DeploymentToolkit>> clusterVertx() {
    return config -> {
      log.debug("Dump configuration {}", config.encodePrettily());

      if (config.getBoolean("cluster")) {
        return Vertx.rxClusteredVertx(new VertxOptions(config))
            .flatMap(clustered -> Single.just(new DeploymentToolkit(clustered, config)));
      }

      return Single.just(new DeploymentToolkit(vertx, config));
    };
  }

  @Value
  @Accessors(fluent = true)
  public class DeploymentToolkit {
    public Vertx vertx;
    public JsonObject config;
  }
}
