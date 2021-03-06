// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.persistence

import com.codahale.metrics.MetricRegistry
import com.digitalasset.dec.DirectExecutionContext
import com.digitalasset.platform.common.logging.NamedLoggerFactory
import com.digitalasset.platform.resources.Resource
import com.digitalasset.platform.sandbox.stores.ledger.sql.dao.{
  HikariJdbcConnectionProvider,
  JdbcConnectionProvider
}
import com.digitalasset.platform.sandbox.stores.ledger.sql.migration.FlywayMigrations
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class PostgresIT extends WordSpec with Matchers with PostgresAroundAll with BeforeAndAfterAll {

  private val loggerFactory = NamedLoggerFactory("PostgresIT")

  private var connectionProviderResource: Resource[JdbcConnectionProvider] = _
  private var connectionProvider: JdbcConnectionProvider = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    connectionProviderResource = HikariJdbcConnectionProvider
      .owner(postgresFixture.jdbcUrl, maxConnections = 4, new MetricRegistry)
      .acquire()(DirectExecutionContext)
      .vary
    connectionProvider = Await.result(connectionProviderResource.asFuture, 10.seconds)
  }

  override protected def afterAll(): Unit = {
    Await.result(connectionProviderResource.release(), 10.seconds)
    super.afterAll()
  }

  "Postgres" when {

    "running queries using Hikari" should {

      "be accessible" in {
        connectionProvider.runSQL { conn =>
          val resultSet = conn.createStatement().executeQuery("SELECT 1")
          resultSet.next()
          val result = resultSet.getInt(1)
          result shouldEqual 1
        }
      }

    }

  }

  "Flyway" should {

    "execute initialisation script" in {
      FlywayMigrations(postgresFixture.jdbcUrl, loggerFactory).migrate()
      connectionProvider.runSQL { conn =>
        def checkTableExists(table: String) = {
          val resultSet = conn.createStatement().executeQuery(s"SELECT * from $table")
          resultSet.next shouldEqual false
        }

        checkTableExists("ledger_entries")
        checkTableExists("contracts")
        checkTableExists("disclosures")
        checkTableExists("contract_witnesses")
        checkTableExists("parameters")
        checkTableExists("parties")
      }
    }

  }
}
