package com.github.ldaniels528.qwery.ops.sql

import com.github.ldaniels528.qwery.ops.{Executable, ResultSet, Scope, _}
import com.github.ldaniels528.qwery.sources.NamedResource

/**
  * Represents a generic JOIN operation
  * @author lawrence.daniels@gmail.com
  */
sealed trait Join extends Executable {

  def left: NamedResource

  def right: NamedResource

  def condition: Condition

}

/**
  * Represents an INNER JOIN
  * @author lawrence.daniels@gmail.com
  */
case class InnerJoin(left: NamedResource, right: NamedResource, condition: Condition) extends Join {

  override def execute(parentScope: Scope): ResultSet = {
    val scope = LocalScope(parentScope, row = Nil)
    ResultSet(rows =
      left.execute(scope).rows flatMap { row0 =>
        scope.setRow(left.name, row0)

        right.execute(scope).rows flatMap { row1 =>
          scope.setRow(right.name, row1)

          // determine whether to include the results
          if (condition.isSatisfied(scope)) Some(row0 ++ row1) else None
        }
      })
  }
}