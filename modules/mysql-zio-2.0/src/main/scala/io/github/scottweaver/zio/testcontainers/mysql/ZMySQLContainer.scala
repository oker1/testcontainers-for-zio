/*
 * Copyright 2021 io.github.scottweaver
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.scottweaver.zio.testcontainers.mysql

import com.dimafeng.testcontainers.MySQLContainer
import com.mysql.cj.jdbc.MysqlDataSource
import io.github.scottweaver.models.JdbcInfo
import org.testcontainers.utility.DockerImageName
import zio._

import java.sql.DriverManager
object ZMySQLContainer {

  final case class Settings(
    imageVersion: String,
    databaseName: String,
    username: String,
    password: String,
    reuse: Boolean
  )

  object Settings {
    val default = ZLayer.succeed(
      Settings(
        "latest",
        MySQLContainer.defaultDatabaseName,
        MySQLContainer.defaultUsername,
        MySQLContainer.defaultPassword,
        reuse = false
      )
    )
  }

  val live = {

    def makeScopedConnection(container: MySQLContainer) =
      ZIO.acquireRelease(
        ZIO.attempt {
          DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password
          )
        }.orDie
      )(conn =>
        ZIO
          .attempt(conn.close())
          .ignoreLogged
      )

    def makeScopedContainer(settings: Settings) =
      ZIO.acquireRelease(
        ZIO.attempt {
          val containerDef = MySQLContainer.Def(
            dockerImageName = DockerImageName.parse(s"mysql:${settings.imageVersion}"),
            databaseName = settings.databaseName,
            username = settings.username,
            password = settings.password
          )
          val c = containerDef.createContainer()
          if (settings.reuse) {
            // there should be a cleaner api in testcontainers-scala for this
            c.underlyingUnsafeContainer.withReuse(true)
          }
          c.start()
          c
        }.orDie
      )(container =>
        ZIO
          .attempt(container.stop())
          .ignoreLogged
          .unless(settings.reuse)
      )

    ZLayer.scopedEnvironment {
      for {
        settings  <- ZIO.service[Settings]
        container <- makeScopedContainer(settings)
        conn      <- makeScopedConnection(container)

      } yield {

        val jdbcInfo = JdbcInfo(
          driverClassName = container.driverClassName,
          jdbcUrl = container.jdbcUrl,
          username = container.username,
          password = container.password
        )

        val dataSource = new MysqlDataSource()
        dataSource.setUrl(container.jdbcUrl)
        dataSource.setUser(container.username)
        dataSource.setPassword(container.password)

        ZEnvironment(jdbcInfo, conn, dataSource, container)
      }

    }
  }

}
