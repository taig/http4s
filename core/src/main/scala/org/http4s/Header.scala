/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on https://github.com/spray/spray/blob/v1.1-M7/spray-http/src/main/scala/spray/http/HttpHeader.scala
 * Copyright (C) 2011-2012 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team
 */

package org.http4s

import cats.{Order, Show, Monoid}
import cats.data.NonEmptyList
import cats.syntax.all._
import org.http4s.util._
import org.typelevel.ci.CIString
import scala.util.hashing.MurmurHash3

object newH {

  sealed trait T
  case class Singleton() extends T
  case class Recurring() extends T
  sealed trait Select[A <: T] {
    type F[_]
    def from[H](headers: List[Header.Raw])(implicit h: Header[H, A]): Option[F[H]]
  }
  object Select {
    implicit def singletons: Select[Singleton] { type F[B] = cats.Id[B]} =
      new Select[Singleton] {
        type F[B] = cats.Id[B]
        def from[H](headers: List[Header.Raw])(implicit h: Header[H, Singleton]): Option[H] =
          headers.collectFirst(Function.unlift((_: Header.Raw).toHeader[H]))
      }

    implicit def recurrings: Select[Recurring] { type F[B] = NonEmptyList[B]} =
      new Select[Recurring] {
        type F[B] = NonEmptyList[B]
        def from[H](headers: List[Header.Raw])(implicit h: Header[H, Recurring]): Option[NonEmptyList[H]] =
          headers.collect(Function.unlift((_: Header.Raw).toHeader[H])).toNel
      }
  }
  trait SelectHeader[H] {
    type F[_]
    def from(headers: List[Header.Raw]): Option[F[H]]
  }
  object SelectHeader {
    implicit def all[H, TT <: T](implicit h: Header[H, TT], s: Select[TT]): SelectHeader[H] { type F[B] = s.F[B] } =
      new SelectHeader[H] {
        type F[B] = s.F[B]
        def from(headers: List[Header.Raw]): Option[F[H]] = s.from(headers)
      }
  }

  /**
    * Typeclass representing an HTTP header, which all the http4s
    * default headers satisfy.
    * You can add custom headers by providing an implicit instance of
    * `Header[YourCustomHeader]`
    */
  trait Header[A, TT <: T] {
    /**
      * Name of the header. Not case sensitive.
      */
    def name: CIString
    /**
      * Value of the header, which is represented as a String.
      * Will be a comma separated String for headers with multiple values.
      */
    def value(a: A): String
    /**
      * Parses the header from its String representation.
      * Could be a comma separated String in case of a Header with
      * multiple values.
      */
    def parse(headerValue: String): Option[A]
  }
  object Header {
    def apply[A](implicit ev: Header[A, _]): ev.type = ev

    /**
      * Target for implicit conversions to Header.Raw from custom
      * headers and key-value pairs.
      * See @see [[org.http4s.Headers$.apply]]
      */
    trait ToRaw {
      def value: Header.Raw
    }
    object ToRaw {
      implicit def rawToRaw(h: Header.Raw): Header.ToRaw = new Header.ToRaw {
        val value = h
      }

      implicit def keyValuesToRaw(kv: (String, String)): Header.ToRaw = new Header.ToRaw {
        val value = Header.Raw(CIString(kv._1), kv._2)
      }

      implicit def customHeadersToRaw[H](h: H)(implicit H: Header[H, _]): Header.ToRaw =
        new Header.ToRaw {
          val value = Header.Raw(H.name, H.value(h))
        }
    }


    case class Raw(name: CIString, value: String) {
      def toHeader[A](implicit h: Header[A, _]): Option[A] =
        (name == Header[A].name).guard[Option] >> Header[A].parse(value)
    }
    object Raw {
      def fromHeader[A](a: A)(implicit h: Header[A, _]): Header.Raw =
        Header.Raw(Header[A].name, Header[A].value(a))
    }
  }

  import scala.collection.mutable.ListBuffer

  /** A collection of HTTP Headers */
  final class Headers (val headers: List[Header.Raw]) extends AnyVal {
    /**
      * TODO get by type
      * TODO recurring
      * TODO revise scaladoc
      * Attempt to get a [[org.http4s.Header]] of type key.HeaderT from this collection
      *
      * @param key [[HeaderKey.Extractable]] that can identify the required header
      * @return a scala.Option possibly containing the resulting header of type key.HeaderT
      * @see [[Header]] object and get([[org.typelevel.ci.CIString]])
      */
    def get[A](implicit ev: SelectHeader[A]): Option[ev.F[A]] =
      ev.from(headers)

    /** Attempt to get a [[org.http4s.Header]] from this collection of headers
      *
      * @param key name of the header to find
      * @return a scala.Option possibly containing the resulting [[org.http4s.Header]]
      */
    def get(key: CIString): Option[Header.Raw] = headers.find(_.name == key)

    /** TODO revise scaladoc
      * Make a new collection adding the specified headers, replacing existing headers of singleton type
      * The passed headers are assumed to contain no duplicate Singleton headers.
      *
      * @param in multiple [[Header]] to append to the new collection
      * @return a new [[Headers]] containing the sum of the initial and input headers
      */
    def put(in: Header.ToRaw*): Headers =
      if (in.isEmpty) this
      else if (this.headers.isEmpty) Headers(in:_*)
      else this ++ Headers(in:_*)

    /** Concatenate the two collections
      * If the resulting collection is of Headers type, duplicate Singleton headers will be removed from
      * this Headers collection.
      *
      * @param that collection to append
      * @tparam B type contained in collection `that`
      * @tparam That resulting type of the new collection
      */
    def ++(that: Headers): Headers =
      if (that.headers.isEmpty) this
      else if (this.headers.isEmpty) that
      else {
        val hs = that.headers
        val acc = new ListBuffer[Header.Raw]
        this.headers.foreach { orig =>
          orig match {
            // TODO recurring
            // case _: Header.Recurring => acc += orig
            // case _: `Set-Cookie` => acc += orig
            case h if !hs.exists(_.name == h.name) => acc += orig
            case _ => // NOOP, drop non recurring header that already exists
          }
        }

        Headers.of(acc.prependToList(hs))
      }

    /** Removes the `Content-Length`, `Content-Range`, `Trailer`, and
      * `Transfer-Encoding` headers.
      *
      *  https://tools.ietf.org/html/rfc7231#section-3.3
      */
    def removePayloadHeaders: Headers =
      Headers.of(headers.filterNot(h => Headers.PayloadHeaderKeys(h.name)))

    def redactSensitive(
      redactWhen: CIString => Boolean = Headers.SensitiveHeaders.contains): Headers =
      Headers.of {
        headers.map {
          case h if redactWhen(h.name) => Header.Raw(h.name, "<REDACTED>")
          case h => h
        }
      }

    // TODO
    // override def toString: String =
    //   Headers.headersShow.show(this)
  }

  object Headers {
    ///// test for construction
    case class Foo(v: String)
    object Foo {
      implicit def headerFoo: Header[Foo, Singleton] = new Header[Foo, Singleton] {
        def name = CIString("foo")
        def value(f: Foo) = f.v
        def parse(s: String) = Foo(s).some
      }
    }
    def bar = Header.Raw(CIString("bar"), "bbb")

    val myHeaders = Headers(
      Foo("hello"),
      "my" -> "header",
      bar
    )
    ////// test for selection
    case class Bar(v: String)
    object Bar {
      implicit def headerBar: Header[Bar, Recurring] = new Header[Bar, Recurring] {
        def name = CIString("Bar")
        def value(f: Bar) = f.v
        def parse(s: String) = Bar(s).some
      }
    }

    val hs = Headers(
      Bar("one"),
      Foo("two"),
      Bar("three")
    )

    val a = hs.get[Foo]


    val b = hs.get[Bar]

    /////

    val empty = of(List.empty)

    def apply(headers: Header.ToRaw*): Headers =
      of(headers.toList.map(_.value))

    /** Create a new Headers collection from the headers */
    def of(headers: List[Header.Raw]): Headers =
      new Headers(headers)

    // TODO
    // implicit val headersShow: Show[Headers] =
    //   Show.show[Headers] {
    //     _.headers.iterator.map(_.show).mkString("Headers(", ", ", ")")
    //   }

    // TODO
    // implicit lazy val HeadersOrder: Order[Headers] =
    //   Order.by(_.headers)

    implicit val headersMonoid: Monoid[Headers] = new Monoid[Headers] {
      def empty: Headers = Headers.empty
      def combine(xa: Headers, xb: Headers): Headers =
        xa ++ xb
    }

    private val PayloadHeaderKeys = Set(
      CIString("Content-Length"),
      CIString("Content-Range"),
      CIString("Trailer"),
      CIString("Transfer-Encoding")
    )

    val SensitiveHeaders = Set(
      CIString("Authorization"),
      CIString("Cookie"),
      CIString("Set-Cookie")
    )
  }
}

/** Abstract representation o the HTTP header
  * @see org.http4s.HeaderKey
  */
sealed trait Header extends Renderable with Product {
  import Header.Raw

  def name: CIString

  def parsed: Header

  def renderValue(writer: Writer): writer.type

  def value: String = {
    val w = new StringWriter
    renderValue(w).result
  }

  def is(key: HeaderKey): Boolean = key.matchHeader(this).isDefined

  def isNot(key: HeaderKey): Boolean = !is(key)

  override def toString: String = name.toString + ": " + value

  def toRaw: Raw = Raw(name, value)

  final def render(writer: Writer): writer.type = {
    writer << name << ':' << ' '
    renderValue(writer)
  }

  final override def hashCode(): Int =
    MurmurHash3.mixLast(name.hashCode, MurmurHash3.productHash(parsed))

  final override def equals(that: Any): Boolean =
    that match {
      case h: AnyRef if this eq h => true
      case h: Header =>
        (name == h.name) &&
          (parsed.productArity == h.parsed.productArity) &&
          (parsed.productIterator.sameElements(h.parsed.productIterator))
      case _ => false
    }

  /** Length of the rendered header, including name and final '\r\n' */
  def renderedLength: Long =
    render(new HeaderLengthCountingWriter).length + 2
}

object Header {
  def unapply(header: Header): Option[(CIString, String)] =
    Some((header.name, header.value))

  def apply(name: String, value: String): Raw = Raw(CIString(name), value)

  /** Raw representation of the Header
    *
    * This can be considered the simplest representation where the header is specified as the product of
    * a key and a value
    * @param name case-insensitive string used to identify the header
    * @param value String representation of the header value
    */
  final case class Raw(name: CIString, override val value: String) extends Header {
    private[this] var _parsed: Header = null
    final override def parsed: Header = {
      if (_parsed == null)
        _parsed = parser.HttpHeaderParser.parseHeader(this).getOrElse(this)
      _parsed
    }
    override def renderValue(writer: Writer): writer.type = writer.append(value)
  }

  /** A Header that is already parsed from its String representation. */
  trait Parsed extends Header {
    def key: HeaderKey
    def name: CIString = key.name
    def parsed: this.type = this
  }

  /** A recurring header that satisfies this clause of the Spec:
    *
    * Multiple message-header fields with the same field-name MAY be present in a message if and only if the entire
    * field-value for that header field is defined as a comma-separated list [i.e., #(values)]. It MUST be possible
    * to combine the multiple header fields into one "field-name: field-value" pair, without changing the semantics
    * of the message, by appending each subsequent field-value to the first, each separated by a comma.
    */
  trait Recurring extends Parsed {
    type Value
    def values: NonEmptyList[Value]
  }

  /** Simple helper trait that provides a default way of rendering the value */
  trait RecurringRenderable extends Recurring {
    type Value <: Renderable
    override def renderValue(writer: Writer): writer.type = {
      values.head.render(writer)
      values.tail.foreach(writer << ", " << _)
      writer
    }
  }

  /** Helper trait that provides a default way of rendering the value provided a Renderer */
  trait RecurringRenderer extends Recurring {
    type Value
    implicit def renderer: Renderer[Value]
    override def renderValue(writer: Writer): writer.type = {
      renderer.render(writer, values.head)
      values.tail.foreach(writer << ", " << Renderer.renderString(_))
      writer
    }
  }

  implicit val HeaderShow: Show[Header] = Show.show[Header] {
    _.toString
  }

  implicit lazy val HeaderOrder: Order[Header] =
    Order.from { case (a, b) =>
      val nameComparison: Int = a.name.compare(b.name)
      if (nameComparison === 0) {
        a.value.compare(b.value)
      } else {
        nameComparison
      }
    }
}
