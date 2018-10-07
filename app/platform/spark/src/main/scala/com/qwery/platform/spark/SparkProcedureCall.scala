package com.qwery.platform.spark

import org.apache.spark.sql.DataFrame

/**
  * Invokes a procedure by name
  * @param name the name of the procedure
  * @param args the given collection of arguments to be passed to the procedure upon invocation
  */
case class SparkProcedureCall(name: String, args: List[Any]) extends SparkInvokable {
  override def execute(input: Option[DataFrame])(implicit rc: SparkQweryContext): Option[DataFrame] =
    rc.getProcedure(name).invoke(args)
}