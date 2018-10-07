package com.qwery.language

import com.qwery.models.Insert.{Into, Overwrite}
import com.qwery.models._
import com.qwery.models.expressions._
import org.scalatest.FunSpec

/**
  * SQL Language Parser Test
  * @author lawrence.daniels@gmail.com
  */
class SQLLanguageParserTest extends FunSpec {

  describe(classOf[SQLLanguageParser].getSimpleName) {
    import com.qwery.models.expressions.Expression.Implicits._
    import com.qwery.util.OptionHelper.Implicits.Risky._

    it("should support BEGIN ... END statements") {
      // test type 1
      val results1 = SQLLanguageParser.parse(
        """|BEGIN
           |  PRINT 'Hello World'
           |END
           |""".stripMargin)
      assert(results1 == SQL(Console.Print("Hello World")))

      // test type 2
      val results2 = SQLLanguageParser.parse(
        """|{
           |  PRINT 'Hello World'
           |}
           |""".stripMargin)
      assert(results2 == SQL(Console.Print("Hello World")))
    }

    it("should support CALL statements") {
      val results = SQLLanguageParser.parse("CALL computeArea(length, width)")
      assert(results == ProcedureCall(name = "computeArea", args = List("length", "width").map(Field.apply)))
    }

    it("should support CREATE FUNCTION statements") {
      val results = SQLLanguageParser.parse("CREATE FUNCTION myFunc AS 'com.qwery.udf.MyFunc'")
      assert(results == Create(UserDefinedFunction(name = "myFunc", `class` = "com.qwery.udf.MyFunc", jar = None)))
    }

    it("should support CREATE INLINE TABLE statements") {
      val results = SQLLanguageParser.parse(
        """|CREATE INLINE TABLE SpecialSecurities (symbol STRING, lastSale DOUBLE)
           |FROM VALUES ('AAPL', 202.11), ('AMD', 23.50), ('GOOG', 765.33), ('AMZN', 699.01)
           |""".stripMargin)
      assert(results == Create(InlineTable(
        name = "SpecialSecurities",
        columns = List("symbol STRING", "lastSale DOUBLE").map(Column.apply),
        source = Insert.Values(List(List("AAPL", 202.11), List("AMD", 23.50), List("GOOG", 765.33), List("AMZN", 699.01)))
      )))
    }

    it("should support CREATE PROCEDURE statements") {
      val results = SQLLanguageParser.parse(
        """|CREATE PROCEDURE testInserts(industry STRING) AS
           |  RETURN (
           |    SELECT Symbol, Name, Sector, Industry, `Summary Quote`
           |    FROM Customers
           |    WHERE Industry = $industry
           |  )
           |""".stripMargin)
      assert(results == Create(Procedure(name = "testInserts",
        params = List("industry STRING").map(Column.apply),
        code = Return(Select(
          fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
          from = Table("Customers"),
          where = Field("Industry") === LocalVariableRef(name = "industry")
        ))
      )))
    }

    it("should support CREATE TABLE statements") {
      // CREATE EXTERNAL TABLE
      val results1 = SQLLanguageParser.parse(
        """|CREATE EXTERNAL TABLE Customers (customer_id STRING, name STRING, address STRING, ingestion_date LONG)
           |PARTITIONED BY (year STRING, month STRING, day STRING)
           |STORED AS INPUTFORMAT 'PARQUET' OUTPUTFORMAT 'PARQUET'
           |LOCATION './dataSets/customers/parquet/'
           |""".stripMargin)
      assert(results1 == Create(Table(name = "Customers",
        columns = List("customer_id STRING", "name STRING", "address STRING", "ingestion_date LONG").map(Column.apply),
        inputFormat = StorageFormats.PARQUET,
        outputFormat = StorageFormats.PARQUET,
        location = "./dataSets/customers/parquet/"
      )))

      // CREATE TABLE
      val results2 = SQLLanguageParser.parse(
        """|CREATE TABLE Customers (customer_uid UUID, name STRING, address STRING, ingestion_date LONG)
           |PARTITIONED BY (year STRING, month STRING, day STRING)
           |STORED AS INPUTFORMAT 'JSON' OUTPUTFORMAT 'JSON'
           |LOCATION './dataSets/customers/json/'
           |""".stripMargin)
      assert(results2 == Create(Table(name = "Customers",
        columns = List("customer_uid UUID", "name STRING", "address STRING", "ingestion_date LONG").map(Column.apply),
        inputFormat = StorageFormats.JSON,
        outputFormat = StorageFormats.JSON,
        location = "./dataSets/customers/json/"
      )))
    }

    it("should support CREATE TABLE ... WITH statements") {
      val results = SQLLanguageParser.parse(
        """|CREATE TABLE Customers (customer_uid UUID, name STRING, address STRING, ingestion_date LONG)
           |PARTITIONED BY (year STRING, month STRING, day STRING)
           |ROW FORMAT DELIMITED
           |FIELDS TERMINATED BY ','
           |STORED AS INPUTFORMAT 'CSV'
           |WITH HEADERS ON
           |WITH NULL VALUES AS 'n/a'
           |LOCATION './dataSets/customers/csv/'
           |""".stripMargin)
      assert(results == Create(Table(name = "Customers",
        columns = List("customer_uid UUID", "name STRING", "address STRING", "ingestion_date LONG").map(Column.apply),
        fieldDelimiter = ",",
        fieldTerminator = None,
        headersIncluded = true,
        nullValue = Some("n/a"),
        inputFormat = StorageFormats.CSV,
        outputFormat = None,
        location = "./dataSets/customers/csv/"
      )))
    }

    it("should support CREATE TEMPORARY FUNCTION") {
      val results = SQLLanguageParser.parse(
        """|CREATE TEMPORARY FUNCTION myFunc AS 'com.qwery.udf.MyFunc'
           |USING JAR '/home/ldaniels/shocktrade/jars/shocktrade-0.8.jar'
           |""".stripMargin)
      assert(results == Create(UserDefinedFunction(
        name = "myFunc",
        `class` = "com.qwery.udf.MyFunc",
        jar = "/home/ldaniels/shocktrade/jars/shocktrade-0.8.jar"
      )))
    }

    it("should support CREATE VIEW statements") {
      val results = SQLLanguageParser.parse(
        """|CREATE VIEW OilAndGas AS
           |SELECT Symbol, Name, Sector, Industry, `Summary Quote`
           |FROM Customers
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      assert(results == Create(View(name = "OilAndGas",
        query = Select(
          fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
          from = Table("Customers"),
          where = Field("Industry") === "Oil/Gas Transmission"
        ))))
    }

    it("should support DEBUG, ERROR, INFO, LOG, PRINT and WARN statements") {
      case class Expected(command: String, opCode: String => Console, message: String)
      val tests = Seq(
        Expected("DEBUG", Console.Debug.apply, "This is a debug message"),
        Expected("ERROR", Console.Error.apply, "This is an error message"),
        Expected("INFO", Console.Info.apply, "This is an informational message"),
        Expected("LOG", Console.Log.apply, "This is an informational message"),
        Expected("PRINT", Console.Print.apply, "This message will be printed to STDOUT"),
        Expected("WARN", Console.Warn.apply, "This is a warning message"))
      tests foreach { case Expected(command, opCode, message) =>
        val results = SQLLanguageParser.parse(s"$command '$message'")
        assert(results == opCode(message))
      }
    }

    it("should support INCLUDE statements") {
      val results = SQLLanguageParser.parse("INCLUDE 'models/securities.sql'")
      assert(results == Include("models/securities.sql"))
    }

    it("should support INSERT statements without explicitly defined fields") {
      val results = SQLLanguageParser.parse(
        "INSERT INTO Students VALUES ('Fred Flintstone', 35, 1.28), ('Barney Rubble', 32, 2.32)")
      assert(results == Insert(Into(Table("Students")), Insert.Values(values = List(
        List("Fred Flintstone", 35.0, 1.28),
        List("Barney Rubble", 32.0, 2.32)
      ))))
    }

    it("should support INSERT-INTO-SELECT statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT INTO TABLE OilGasSecurities (Symbol, Name, LastSale, MarketCap, IPOyear, Sector, Industry)
           |SELECT Symbol, Name, LastSale, MarketCap, IPOyear, Sector, Industry
           |FROM Securities
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'LastSale, 'MarketCap, 'IPOyear, 'Sector, 'Industry).map(s => Field(s.name))
      assert(results == Insert(Into(Table("OilGasSecurities")),
        Select(
          fields = fields,
          from = Table("Securities"),
          where = Field("Industry") === "Oil/Gas Transmission"),
        fields = fields
      ))
    }

    it("should support INSERT-INTO-VALUES statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT INTO TABLE OilGasSecurities (Symbol, Name, Sector, Industry, LastSale)
           |VALUES
           |  ('AAPL', 'Apple, Inc.', 'Technology', 'Computers', 203.45),
           |  ('AMD', 'American Micro Devices, Inc.', 'Technology', 'Computers', 22.33)
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'Sector, 'Industry, 'LastSale).map(s => Field(s.name))
      assert(results == Insert(Into(Table("OilGasSecurities")),
        Insert.Values(
          values = List(
            List("AAPL", "Apple, Inc.", "Technology", "Computers", 203.45),
            List("AMD", "American Micro Devices, Inc.", "Technology", "Computers", 22.33)
          )),
        fields = fields
      ))
    }

    it("should support INSERT-INTO-LOCATION-SELECT statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT INTO LOCATION '/dir/subdir' (Symbol, Name, Sector, Industry, LastSale)
           |SELECT Symbol, Name, Sector, Industry, LastSale
           |FROM Securities
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'Sector, 'Industry, 'LastSale).map(s => Field(s.name))
      assert(results == Insert(Into(LocationRef("/dir/subdir")),
        Select(
          fields = fields,
          from = Table("Securities"),
          where = Field("Industry") === "Oil/Gas Transmission"),
        fields = fields
      ))
    }

    it("should support INSERT-OVERWRITE-SELECT statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT OVERWRITE TABLE OilGasSecurities (Symbol, Name, LastSale, MarketCap, IPOyear, Sector, Industry)
           |SELECT Symbol, Name, LastSale, MarketCap, IPOyear, Sector, Industry
           |FROM Securities
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'LastSale, 'MarketCap, 'IPOyear, 'Sector, 'Industry).map(s => Field(s.name))
      assert(results == Insert(Overwrite(Table("OilGasSecurities")),
        Select(
          fields = fields,
          from = Table("Securities"),
          where = Field("Industry") === "Oil/Gas Transmission"),
        fields = fields
      ))
    }

    it("should support INSERT-OVERWRITE-VALUES statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT OVERWRITE TABLE OilGasSecurities (Symbol, Name, Sector, Industry, LastSale)
           |VALUES
           |  ('AAPL', 'Apple, Inc.', 'Technology', 'Computers', 203.45),
           |  ('AMD', 'American Micro Devices, Inc.', 'Technology', 'Computers', 22.33)
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'Sector, 'Industry, 'LastSale).map(s => Field(s.name))
      assert(results == Insert(Overwrite(Table("OilGasSecurities")),
        Insert.Values(values = List(
          List("AAPL", "Apple, Inc.", "Technology", "Computers", 203.45),
          List("AMD", "American Micro Devices, Inc.", "Technology", "Computers", 22.33)
        )),
        fields = fields
      ))
    }

    it("should support INSERT-OVERWRITE-LOCATION-SELECT statements") {
      val results = SQLLanguageParser.parse(
        """|INSERT OVERWRITE LOCATION '/dir/subdir' (Symbol, Name, Sector, Industry, LastSale)
           |SELECT Symbol, Name, Sector, Industry, LastSale
           |FROM Securities
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      val fields = List('Symbol, 'Name, 'Sector, 'Industry, 'LastSale).map(s => Field(s.name))
      assert(results == Insert(Overwrite(LocationRef("/dir/subdir")),
        Select(
          fields = fields,
          from = Table("Securities"),
          where = Field("Industry") === "Oil/Gas Transmission"),
        fields = fields
      ))
    }

    it("should support MAIN PROGRAM statements") {
      val results = SQLLanguageParser.parse(
        """|MAIN PROGRAM 'Oil_Companies'
           |  WITH ARGUMENTS AS @args
           |  WITH ENVIRONMENT AS @env
           |  WITH HIVE SUPPORT
           |  WITH STREAM PROCESSING
           |AS
           |BEGIN
           |  /* does nothing */
           |END
           |""".stripMargin)
      assert(results == MainProgram(name = "Oil_Companies", code = SQL(),
        arguments = VariableRef("@args"), environment = VariableRef("@env"), hiveSupport = true, streaming = true))
    }

    it("should support SELECT ... GROUP BY statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT Sector, Industry, AVG(LastSale) AS LastSale, COUNT(*) AS total, COUNT(DISTINCT(*)) AS distinctTotal
           |FROM Customers
           |GROUP BY Sector, Industry
           |""".stripMargin)
      assert(results == Select(
        fields = List(Field("Sector"), Field("Industry"), Avg(Field("LastSale")).as("LastSale"),
          Count(AllFields).as("total"), Count(Distinct(AllFields)).as("distinctTotal")),
        from = Table("Customers"),
        groupBy = List("Sector", "Industry").map(Field.apply)
      ))
    }

    it("should support SELECT ... LIMIT n statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT Symbol, Name, Sector, Industry
           |FROM Customers
           |WHERE Industry = 'Oil/Gas Transmission'
           |LIMIT 100
           |""".stripMargin)
      assert(results == Select(
        fields = List("Symbol", "Name", "Sector", "Industry").map(Field.apply),
        from = Table("Customers"),
        where = Field("Industry") === "Oil/Gas Transmission",
        limit = 100
      ))
    }

    it("should support SELECT TOP n ... statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT TOP 20 Symbol, Name, Sector, Industry
           |FROM Customers
           |WHERE Industry = 'Oil/Gas Transmission'
           |""".stripMargin)
      assert(results == Select(
        fields = List("Symbol", "Name", "Sector", "Industry").map(Field.apply),
        from = Table("Customers"),
        where = Field("Industry") === "Oil/Gas Transmission",
        limit = 20
      ))
    }

    it("should support SELECT ... ORDER BY statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT Symbol, Name, Sector, Industry, `Summary Quote`
           |FROM Customers
           |WHERE Industry = 'Oil/Gas Transmission'
           |ORDER BY Symbol DESC, Name ASC
           |""".stripMargin)
      assert(results == Select(
        fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
        from = Table("Customers"),
        where = Field("Industry") === "Oil/Gas Transmission",
        orderBy = List(OrderColumn(name = "Symbol", isAscending = false), OrderColumn(name = "Name", isAscending = true))
      ))
    }

    it("should support SELECT ... UNION statements") {
      Seq("ALL", "DISTINCT", "") foreach { modifier =>
        val results = SQLLanguageParser.parse(
          s"""|SELECT Symbol, Name, Sector, Industry, `Summary Quote`
              |FROM Customers
              |WHERE Industry = 'Oil/Gas Transmission'
              |UNION $modifier
              |SELECT Symbol, Name, Sector, Industry, `Summary Quote`
              |FROM Customers
              |WHERE Industry = 'Computer Manufacturing'
              |""".stripMargin)
        assert(results == Union(
          query0 = Select(
            fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
            from = Table("Customers"),
            where = Field("Industry") === "Oil/Gas Transmission"),
          query1 = Select(
            fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
            from = Table("Customers"),
            where = Field("Industry") === "Computer Manufacturing"),
          isDistinct = modifier == "DISTINCT"
        ))
      }
    }

    it("should support SELECT w/CROSS JOIN statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT C.id, C.firstName, C.lastName, A.city, A.state, A.zipCode
           |FROM Customers as C
           |CROSS JOIN CustomerAddresses as CA ON CA.customerId = C.customerId
           |CROSS JOIN Addresses as A ON A.addressId = CA.addressId
           |WHERE C.firstName = 'Lawrence' AND C.lastName = 'Daniels'
           |""".stripMargin)
      assert(results == Select(
        fields = List("C.id", "C.firstName", "C.lastName", "A.city", "A.state", "A.zipCode").map(Field.apply),
        from = Table("Customers").as("C"),
        joins = List(
          Join(Table("CustomerAddresses").as("CA"), Field("CA.customerId") === Field("C.customerId"), JoinTypes.CROSS),
          Join(Table("Addresses").as("A"), Field("A.addressId") === Field("CA.addressId"), JoinTypes.CROSS)
        ),
        where = Field("C.firstName") === "Lawrence" && Field("C.lastName") === "Daniels"
      ))
    }

    it("should support SELECT w/INNER JOIN statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT C.id, C.firstName, C.lastName, A.city, A.state, A.zipCode
           |FROM Customers as C
           |INNER JOIN CustomerAddresses as CA ON CA.customerId = C.customerId
           |INNER JOIN Addresses as A ON A.addressId = CA.addressId
           |WHERE C.firstName = 'Lawrence' AND C.lastName = 'Daniels'
           |""".stripMargin)
      assert(results == Select(
        fields = List("C.id", "C.firstName", "C.lastName", "A.city", "A.state", "A.zipCode").map(Field.apply),
        from = Table("Customers").as("C"),
        joins = List(
          Join(Table("CustomerAddresses").as("CA"), Field("CA.customerId") === Field("C.customerId"), JoinTypes.INNER),
          Join(Table("Addresses").as("A"), Field("A.addressId") === Field("CA.addressId"), JoinTypes.INNER)
        ),
        where = Field("C.firstName") === "Lawrence" && Field("C.lastName") === "Daniels"
      ))
    }

    it("should support SELECT w/FULL OUTER JOIN statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT C.id, C.firstName, C.lastName, A.city, A.state, A.zipCode
           |FROM Customers as C
           |FULL OUTER JOIN CustomerAddresses as CA ON CA.customerId = C.customerId
           |FULL OUTER JOIN Addresses as A ON A.addressId = CA.addressId
           |WHERE C.firstName = 'Lawrence' AND C.lastName = 'Daniels'
           |""".stripMargin)
      assert(results == Select(
        fields = List("C.id", "C.firstName", "C.lastName", "A.city", "A.state", "A.zipCode").map(Field.apply),
        from = Table("Customers").as("C"),
        joins = List(
          Join(Table("CustomerAddresses").as("CA"), Field("CA.customerId") === Field("C.customerId"), JoinTypes.FULL_OUTER),
          Join(Table("Addresses").as("A"), Field("A.addressId") === Field("CA.addressId"), JoinTypes.FULL_OUTER)
        ),
        where = Field("C.firstName") === "Lawrence" && Field("C.lastName") === "Daniels"
      ))
    }

    it("should support SELECT w/LEFT OUTER JOIN statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT C.id, C.firstName, C.lastName, A.city, A.state, A.zipCode
           |FROM Customers as C
           |LEFT OUTER JOIN CustomerAddresses as CA ON CA.customerId = C.customerId
           |LEFT OUTER JOIN Addresses as A ON A.addressId = CA.addressId
           |WHERE C.firstName = 'Lawrence' AND C.lastName = 'Daniels'
           |""".stripMargin)
      assert(results == Select(
        fields = List("C.id", "C.firstName", "C.lastName", "A.city", "A.state", "A.zipCode").map(Field.apply),
        from = Table("Customers").as("C"),
        joins = List(
          Join(Table("CustomerAddresses").as("CA"), Field("CA.customerId") === Field("C.customerId"), JoinTypes.LEFT_OUTER),
          Join(Table("Addresses").as("A"), Field("A.addressId") === Field("CA.addressId"), JoinTypes.LEFT_OUTER)
        ),
        where = Field("C.firstName") === "Lawrence" && Field("C.lastName") === "Daniels"
      ))
    }

    it("should support SELECT w/RIGHT OUTER JOIN statements") {
      val results = SQLLanguageParser.parse(
        """|SELECT C.id, C.firstName, C.lastName, A.city, A.state, A.zipCode
           |FROM Customers as C
           |RIGHT OUTER JOIN CustomerAddresses as CA ON CA.customerId = C.customerId
           |RIGHT OUTER JOIN Addresses as A ON A.addressId = CA.addressId
           |WHERE C.firstName = 'Lawrence' AND C.lastName = 'Daniels'
           |""".stripMargin)
      assert(results == Select(
        fields = List("C.id", "C.firstName", "C.lastName", "A.city", "A.state", "A.zipCode").map(Field.apply),
        from = Table("Customers").as("C"),
        joins = List(
          Join(Table("CustomerAddresses").as("CA"), Field("CA.customerId") === Field("C.customerId"), JoinTypes.RIGHT_OUTER),
          Join(Table("Addresses").as("A"), Field("A.addressId") === Field("CA.addressId"), JoinTypes.RIGHT_OUTER)
        ),
        where = Field("C.firstName") === "Lawrence" && Field("C.lastName") === "Daniels"
      ))
    }

    it("should support SET statements") {
      val results = SQLLanguageParser.parse(
        """|{
           |  SET @customers = (
           |    SELECT Symbol, Name, Sector, Industry, `Summary Quote`
           |    FROM Customers
           |    WHERE Industry = 'Oil/Gas Transmission'
           |    ORDER BY Symbol ASC
           |  )
           |}
           |""".stripMargin)
      assert(results == SQL(SetVariable(variable = VariableRef(name = "@customers"),
        Select(
          fields = List("Symbol", "Name", "Sector", "Industry", "Summary Quote").map(Field.apply),
          from = Table("Customers"),
          where = Field("Industry") === "Oil/Gas Transmission",
          orderBy = List(OrderColumn(name = "Symbol", isAscending = true))
        ))))
    }

    it("should support SHOW statements") {
      val results = SQLLanguageParser.parse("SHOW @theResults LIMIT 5")
      assert(results == Show(rows = RowSetVariableRef(name = "theResults"), limit = 5))
    }

    it("should support UPDATE statements") {
      val results = SQLLanguageParser.parse(
        """|UPDATE Companies
           |SET Symbol = 'AAPL', Name = 'Apple, Inc.', Sector = 'Technology', Industry = 'Computers', LastSale = 203.45
           |WHERE Symbol = 'AAPL'
           |""".stripMargin)
      assert(results == Update(
        table = Table("Companies"),
        assignments = Seq(
          "Symbol" -> "AAPL", "Name" -> "Apple, Inc.",
          "Sector" -> "Technology", "Industry" -> "Computers", "LastSale" -> 203.45
        ),
        where = Field("Symbol") === "AAPL"
      ))
    }

  }

}