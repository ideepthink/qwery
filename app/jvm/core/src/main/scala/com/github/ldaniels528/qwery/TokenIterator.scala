package com.github.ldaniels528.qwery

/**
  * Token Iterator
  * @author lawrence.daniels@gmail.com
  */
case class TokenIterator(input: String) extends Iterator[Token] {
  private var pos = 0
  private val ca = input.toCharArray
  private val operators = "=*-+/|&><".toCharArray
  private val compoundOperators = Seq("!=", ">=", "<=", "<>", "||", "**")

  private def parsers = List(
    parseNumeric _, parseAlphaNumeric _, parseQuotesBackticks _, parseQuotesDouble _,
    parseQuotesSingle _, parseCompoundOperators _, parseOperators _, parseSymbols _)

  override def hasNext: Boolean = {
    skipWhitespace()
    pos < ca.length
  }

  override def next(): Token = {
    if (!hasNext) throw new NoSuchElementException()
    else {
      val outcome = parsers.foldLeft[Option[Token]](None) { (token_?, parser) =>
        if (token_?.isEmpty) parser() else token_?
      }
      outcome.getOrElse(throw new IllegalArgumentException(String.copyValueOf(ca, pos, ca.length)))
    }
  }

  def nextOption(): Option[Token] = {
    if (!hasNext) None
    else parsers.foldLeft[Option[Token]](None) { (token_?, parser) =>
      if (token_?.isEmpty) parser() else token_?
    }
  }

  def peek: Option[Token] = {
    val mark = pos
    val token_? = nextOption()
    pos = mark
    token_?
  }

  def span(length: Int): Option[String] = {
    if(pos + length < ca.length) Some(String.copyValueOf(ca, pos, length)) else None
  }

  @inline
  private def hasMore = pos < ca.length

  private def parseAlphaNumeric(): Option[Token] = {
    val start = pos
    while (hasMore && (ca(pos).isLetterOrDigit || ca(pos) == '_')) pos += 1
    if (pos > start) Some(AlphaToken(String.copyValueOf(ca, start, pos - start), start)) else None
  }

  private def parseCompoundOperators(): Option[Token] = {
    if (hasMore && span(2).exists(compoundOperators.contains)) {
      val start = pos
      val result = span(2).map(OperatorToken(_, start))
      pos += 2
      result
    }
    else None
  }

  private def parseNumeric(): Option[Token] = {
    val start = pos
    while (hasMore && (ca(pos).isDigit || ca(pos) == '.')) pos += 1
    if (pos > start) Some(NumericToken(String.copyValueOf(ca, start, pos - start), start)) else None
  }

  private def parseOperators(): Option[Token] = {
    if (hasMore && operators.contains(ca(pos))) {
      val start = pos
      pos += 1
      Some(OperatorToken(ca(start).toString, start))
    }
    else None
  }

  private def parseQuotesBackticks() = parseQuotes('`')

  private def parseQuotesDouble() = parseQuotes('"')

  private def parseQuotesSingle() = parseQuotes('\'')

  private def parseQuotes(ch: Char): Option[Token] = {
    if (hasMore && ca(pos) == ch) {
      pos += 1
      val start = pos
      while (hasMore && ca(pos) != ch) pos += 1
      val length = pos - start
      pos += 1
      Some(QuotedToken(String.copyValueOf(ca, start, length), start, ch))
    }
    else None
  }

  private def parseSymbols(): Option[Token] = {
    if (hasMore) {
      val start = pos
      pos += 1
      Some(SymbolToken(ca(start).toString, start))
    }
    else None
  }

  private def skipWhitespace() {
    while (hasMore && ca(pos).isWhitespace) pos += 1
  }

}

/**
  * Represents a token
  * @author lawrence.daniels@gmail.com
  */
sealed trait Token {

  def is(value: String): Boolean = text.equalsIgnoreCase(value)

  def start: Int

  def text: String

  def value: Any

}

/**
  * Represents a text token
  * @author lawrence.daniels@gmail.com
  */
trait TextToken extends Token

/**
  * Represents an alphanumeric token
  * @author lawrence.daniels@gmail.com
  */
case class AlphaToken(text: String, start: Int) extends TextToken {
  override def value: String = text
}

/**
  * Represents a quoted token
  * @author lawrence.daniels@gmail.com
  */
case class QuotedToken(text: String, start: Int, quoteChar: Char) extends TextToken {
  override def value: String = text

  def isBackticks: Boolean = quoteChar == '`'

  def isDoubleQuoted: Boolean = quoteChar == '"'

  def isSingleQuoted: Boolean = quoteChar == '\''

}

/**
  * Represents a numeric token
  * @author lawrence.daniels@gmail.com
  */
case class NumericToken(text: String, start: Int) extends Token {
  override def value: Double = text.toDouble
}

/**
  * Represents an operator token
  * @author lawrence.daniels@gmail.com
  */
case class OperatorToken(text: String, start: Int) extends Token {
  override def value: String = text
}

/**
  * Represents a symbolic token
  * @author lawrence.daniels@gmail.com
  */
case class SymbolToken(text: String, start: Int) extends Token {
  override def value: String = text
}

