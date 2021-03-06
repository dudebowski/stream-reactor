package com.datamountaineer.streamreactor.connect.cassandra

import java.util.concurrent.atomic.AtomicLong

import com.datamountaineer.streamreactor.connect.cassandra.CassandraWrapper.resultSetFutureToScala
import com.datamountaineer.streamreactor.connect.utils.ConverterUtil
import com.datastax.driver.core.{PreparedStatement, ResultSet}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.{SinkRecord, SinkTaskContext}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * <h1>CassandraJsonWriter</h1>
  * Cassandra Json writer for Kafka connect
  * Writes a list of Kafka connect sink records to Cassandra using the JSON support
  *
  */
class CassandraJsonWriter(connector: CassandraConnection, context : SinkTaskContext) extends StrictLogging with ConverterUtil {
  logger.info("Initialising Cassandra writer")
  val insertCount = new AtomicLong()
  configureConverter(jsonConverter)

  //get topic list from context assignment
  private val topics = context.assignment().asScala.map(c=>c.topic()).toList

  //check a table exists in Cassandra for the topics
  checkCassandraTables(topics, connector.session.getLoggedKeyspace)

  //cache for prepared statements
  private val preparedCache: Map[String, PreparedStatement] = cachePreparedStatements(topics)

  /**
    * Check if we have tables in Cassandra and if we have table named the same as our topic
    *
    * @param topics A list of the assigned topics
    * @param keySpace The keyspace to look in for the tables
    * */
  private def checkCassandraTables(topics: List[String], keySpace: String) = {
    val metaData = connector.cluster.getMetadata.getKeyspace(keySpace).getTables.asScala
    val tables: List[String] = metaData.map(t=>t.getName).toList

    //check tables
    if (tables.isEmpty) throw new ConnectException(s"No tables found in Cassandra for keyspace $keySpace")

    //check we have a table for all topics
    val missing = topics.filter( tp => !tables.contains(tp))

    if (missing.nonEmpty) throw new ConnectException(s"Not table found in Cassandra for topics ${missing.mkString(",")}")
  }

  /**
    * Cache the preparedStatements per topic rather than create them every time
    * Each one is an insert statement aligned to topics
    *
    * @param topics A list of topics
    * @return A Map of topic->preparedStatements
    * */
  private def cachePreparedStatements(topics : List[String]) : Map[String, PreparedStatement] = {
    logger.info(s"Preparing statements for ${topics.mkString(",")}.")
    topics.distinct.map( t=> (t, getPreparedStatement(t))).toMap
  }

  /**
    * Build a preparedStatement for the given topic
    *
    * @param topic The topic/table name to prepare the statement for
    * @return A prepared statement for the given topic
    * */
  private def getPreparedStatement(topic : String) : PreparedStatement = {
    connector.session.prepare(s"INSERT INTO ${connector.session.getLoggedKeyspace}.$topic JSON ?")
  }

  /**
    * Write SinkRecords to Cassandra (aSync) in Json
    *
    * @param records A list of SinkRecords from Kafka Connect to write
    * */
  def write(records : List[SinkRecord]) = {
    if (records.isEmpty) logger.info("No records received.") else insert(records)
  }

  /**
    * Write SinkRecords to Cassandra (aSync) in Json
    *
    * @param records A list of SinkRecords from Kafka Connect to write
    * @return boolean indication successful write
    * */
  def insert(records: List[SinkRecord]) = {
    insertCount.addAndGet(records.size)
    //group by topic
    val grouped = records.groupBy(_.topic())
    grouped.foreach(t=> {
      val preparedStatement = preparedCache.get(t._1).get
      t._2.foreach(r=>{
        //execute async and convert FutureResultSet to Scala future
        val results = resultSetFutureToScala(
          connector.session.executeAsync(preparedStatement.bind(convertValueToJson(r).toString)))

        //increment latch
        results.onSuccess({
          case r:ResultSet =>
            logger.debug(s"Write successful!")
            insertCount.decrementAndGet()
        })

        //increment latch but set status to false
        results.onFailure({
          case t:Throwable =>
            logger.warn(s"Write failed! ${t.getMessage}")
            insertCount.decrementAndGet()
        })
      })
    })
  }

  /**
    * Closed down the driver session and cluster
    * */
  def close(): Unit = {
    logger.info("Shutting down Cassandra driver session and cluster")
    connector.session.close()
    connector.cluster.close()
  }
}
