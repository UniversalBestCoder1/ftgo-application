package net.chrisrichardson.ftgo.kitchenservice.domain

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import javax.sql.DataSource

/** Provides an H2 datasource in MySQL-compatibility mode so that INT(1) in
 *  Eventuate's eventuate-tram-sagas-embedded.sql is accepted by H2 2.x. */
@TestConfiguration
open class H2MySQLCompatDataSource {
    @Bean @Primary
    open fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
        .setType(EmbeddedDatabaseType.H2)
        .setName("testdb;MODE=MySQL;DATABASE_TO_UPPER=false")
        .build()
}
