package com.github.ldaniels528.qwery.sources

import java.sql.{Connection, PreparedStatement}

import com.github.ldaniels528.qwery.SQLGenerator
import com.github.ldaniels528.qwery.devices._
import com.github.ldaniels528.qwery.ops._
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}

/**
  * JDBC Output Source
  * @author lawrence.daniels@gmail.com
  */
case class JDBCOutputSource(url: String, tableName: String, hints: Option[Hints])
  extends OutputSource with OutputDevice with JDBCSupport {
  private val log = LoggerFactory.getLogger(getClass)
  private val preparedStatements = TrieMap[String, PreparedStatement]()
  private val sqlGenerator = new SQLGenerator()
  private var conn_? : Option[Connection] = None
  private var offset = 0L

  override def close(): Unit = {
    preparedStatements.values.foreach(ps => Try(ps.close()))
    conn_?.foreach(_.close())
  }

  override def device: this.type = this

  override def getStatistics: Option[Statistics] = statsGen.update(force = true)

  override def open(scope: Scope): Unit = {
    super.open(scope)
    offset = 0

    // open the connection
    createConnection(scope, url, hints) match {
      case Success(conn) => conn_? = Option(conn)
      case Failure(e) =>
        throw new IllegalStateException(s"Connection error: ${e.getMessage}", e)
    }
  }

  override def write(record: Record): Any = {
    throw new IllegalStateException("Illegal write attempt")
  }

  override def write(row: Row): Unit = insert(row) match {
    case Success(count_?) =>
      count_?.foreach { case (inserted, updated) =>
        offset += 1
        statsGen.update(records = inserted + updated)
      }
    case Failure(e) =>
      offset += 1
      statsGen.update(failures = 1)
      log.error(s"Record #$offset failed: ${e.getMessage}")
  }

  def upsert(row: Row, where: Seq[String]): Option[(Int, Int)] = {
    val results: Try[Option[(Int, Int)]] = insert(row) match {
      case outcome@Success(counts) =>
        counts foreach { case (inserted, updated) =>
          statsGen.update(records = inserted + updated)
        }
        outcome
      case Failure(e) =>
        if (e.getMessage.toLowerCase().contains("duplicate")) update(row, where) else Failure(e)
    }
    results match {
      case Success(counts) => counts
      case Failure(e) =>
        statsGen.update(failures = 1)
        log.warn(s"insert/update failed: ${e.getMessage}")
        None
    }
  }

  def insert(row: Row): Try[Option[(Int, Int)]] = Try {
    val sql = sqlGenerator.insert(tableName, row)
    conn_? map { conn =>
      val ps = preparedStatements.getOrElseUpdate(sql, conn.prepareStatement(sql))
      row.columns.map(_._2).zipWithIndex foreach { case (value, index) =>
        ps.setObject(index + 1, value)
      }
      (ps.executeUpdate(), 0)
    }
  }

  def update(row: Row, where: Seq[String]): Try[Option[(Int, Int)]] = Try {
    val sql = sqlGenerator.update(tableName, row, where)
    conn_? map { conn =>
      val ps = preparedStatements.getOrElseUpdate(sql, conn.prepareStatement(sql))
      row.columns.map(_._2).zipWithIndex foreach { case (value, index) =>
        ps.setObject(index + 1, value)
      }
      where.zipWithIndex foreach { case (name, index) =>
        ps.setObject(index + row.size + 1, row.get(name).orNull)
      }
      (0, ps.executeUpdate())
    }
  }

  def update(row: Row, where: Condition): Try[Option[(Int, Int)]] = Try {
    val sql = sqlGenerator.update(tableName, row, where)
    conn_? map { conn =>
      val ps = preparedStatements.getOrElseUpdate(sql, conn.prepareStatement(sql))
      row.columns.map(_._2).zipWithIndex foreach { case (value, index) =>
        ps.setObject(index + 1, value)
      }
      (0, ps.executeUpdate())
    }
  }

}

/**
  * JDBC Output Device Companion
  * @author lawrence.daniels@gmail.com
  */
object JDBCOutputSource extends OutputDeviceFactory with SourceUrlParser {

  /**
    * Returns a compatible output device for the given URL.
    * @param path the given URL (e.g. "jdbc:mysql://localhost/test")
    * @return an option of the [[OutputDevice output device]]
    */
  override def parseOutputURL(path: String, hints: Option[Hints]): Option[OutputDevice] = {
    if (path.startsWith("jdbc:")) {
      val comps = parseURI(path)
      for {
        tableName <- comps.params.get("table")
        url = path.indexOf('?') match {
          case -1 => path
          case index => path.substring(0, index)
        }
      } yield JDBCOutputSource(url = url, tableName = tableName, hints)
    }
    else None
  }

}