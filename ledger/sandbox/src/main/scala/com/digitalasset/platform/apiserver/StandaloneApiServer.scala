// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.apiserver

import java.io.File
import java.nio.file.Files
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import com.daml.ledger.participant.state.v1.{ParticipantId, ReadService, WriteService}
import com.digitalasset.daml.lf.engine.Engine
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.auth.interceptor.AuthorizationInterceptor
import com.digitalasset.ledger.api.auth.{AuthService, Authorizer}
import com.digitalasset.ledger.api.domain
import com.digitalasset.ledger.api.health.HealthChecks
import com.digitalasset.platform.apiserver.StandaloneApiServer._
import com.digitalasset.platform.common.logging.NamedLoggerFactory
import com.digitalasset.platform.resources.{Resource, ResourceOwner}
import com.digitalasset.platform.sandbox.BuildInfo
import com.digitalasset.platform.sandbox.config.SandboxConfig
import com.digitalasset.platform.sandbox.stores.InMemoryPackageStore
import com.digitalasset.platform.server.services.testing.TimeServiceBackend

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

// Main entry point to start an index server that also hosts the ledger API.
// See v2.ReferenceServer on how it is used.
final class StandaloneApiServer(
    config: ApiServerConfig,
    readService: ReadService,
    writeService: WriteService,
    authService: AuthService,
    loggerFactory: NamedLoggerFactory,
    metrics: MetricRegistry,
    engine: Engine = sharedEngine, // allows sharing DAML engine with DAML-on-X participant
    timeServiceBackendO: Option[TimeServiceBackend] = None,
) extends ResourceOwner[Unit] {
  private val logger = loggerFactory.getLogger(this.getClass)

  // Name of this participant,
  val participantId: ParticipantId = config.participantId

  override def acquire()(implicit executionContext: ExecutionContext): Resource[Unit] = {
    buildAndStartApiServer().map { _ =>
      logger.info("Started Index Server")
      ()
    }
  }

  // if requested, initialize the ledger state with the given scenario
  private def preloadPackages(packageContainer: InMemoryPackageStore): Unit = {
    // [[ScenarioLoader]] needs all the packages to be already compiled --
    // make sure that that's the case
    for {
      (pkgId, _) <- packageContainer.listLfPackagesSync()
      pkg <- packageContainer.getLfPackageSync(pkgId)
    } {
      engine
        .preloadPackage(pkgId, pkg)
        .consume(
          { _ =>
            sys.error("Unexpected request of contract")
          },
          packageContainer.getLfPackageSync, { _ =>
            sys.error("Unexpected request of contract key")
          }
        )
      ()
    }
  }

  private def loadDamlPackages(): InMemoryPackageStore = {
    // TODO is it sensible to have all the initial packages to be known since the epoch?
    config.archiveFiles
      .foldLeft[Either[(String, File), InMemoryPackageStore]](Right(InMemoryPackageStore.empty)) {
        case (storeE, f) =>
          storeE.flatMap(_.withDarFile(Instant.now(), None, f).left.map(_ -> f))
      }
      .fold({ case (err, file) => sys.error(s"Could not load package $file: $err") }, identity)
  }

  private def buildAndStartApiServer()(implicit ec: ExecutionContext): Resource[ApiServer] = {
    val packageStore = loadDamlPackages()
    preloadPackages(packageStore)

    for {
      actorSystem <- ResourceOwner.forActorSystem(() => ActorSystem(actorSystemName)).acquire()
      materializer <- ResourceOwner
        .forMaterializer(() => Materializer(actorSystem))
        .acquire()
      initialConditions <- ResourceOwner
        .forFuture(() => readService.getLedgerInitialConditions().runWith(Sink.head)(materializer))
        .acquire()
      authorizer = new Authorizer(
        () => java.time.Clock.systemUTC.instant(),
        initialConditions.ledgerId,
        participantId)
      indexService <- JdbcIndex(
        readService,
        domain.LedgerId(initialConditions.ledgerId),
        participantId,
        config.jdbcUrl,
        loggerFactory,
        metrics,
      )(materializer)
      healthChecks = new HealthChecks(
        "index" -> indexService,
        "read" -> readService,
        "write" -> writeService,
      )
      apiServer <- new LedgerApiServer(
        (mat: Materializer, esf: ExecutionSequencerFactory) =>
          ApiServices
            .create(
              writeService,
              indexService,
              authorizer,
              engine,
              config.timeProvider,
              initialConditions.config,
              SandboxConfig.defaultCommandConfig,
              timeServiceBackendO,
              loggerFactory,
              metrics,
              healthChecks,
            )(mat, esf),
        config.port,
        config.maxInboundMessageSize,
        None,
        loggerFactory,
        config.tlsConfig.flatMap(_.server),
        List(AuthorizationInterceptor(authService, ec)),
        metrics
      )(actorSystem, materializer).acquire()
      _ <- ResourceOwner.forFuture(() => writePortFile(apiServer.port)).acquire()
    } yield {
      logger.info(
        "Initialized index server version {} with ledger-id = {}, port = {}, dar file = {}",
        BuildInfo.Version,
        initialConditions.ledgerId,
        apiServer.port.toString,
        config.archiveFiles
      )
      apiServer
    }
  }

  private def writePortFile(port: Int)(
      implicit executionContext: ExecutionContext
  ): Future[Unit] =
    config.portFile
      .map(path => Future(Files.write(path, Seq(port.toString).asJava)).map(_ => ()))
      .getOrElse(Future.successful(()))
}

object StandaloneApiServer {
  private val actorSystemName = "index"

  private val sharedEngine = Engine()
}
