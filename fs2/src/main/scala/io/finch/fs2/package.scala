package io.finch

import _root_.fs2.Stream
import cats.effect.Effect
import com.twitter.finagle.http.Response
import com.twitter.io.Buf
import com.twitter.util.Future
import shapeless.Witness

package object fs2 extends StreamInstances {

  implicit def streamLiftReader[F[_] : Effect](implicit toEffect: ToEffect[Future, F]): LiftReader[Stream, F] =
    LiftReader.instance { reader =>
      Stream
        .repeatEval(Effect[F].defer(toEffect(reader.read)))
        .unNoneTerminate
        .onFinalize(Effect[F].delay(reader.discard()))
    }

  implicit def streamToJsonResponse[F[_] : Effect, A](implicit
    e: Encode.Aux[A, Application.Json],
    w: Witness.Aux[Application.Json]
  ): ToResponse.Aux[Stream[F, A], Application.Json] = {
    mkToResponse[F, A, Application.Json](delimiter = Some(ToResponse.NewLine))
  }

}

trait StreamInstances {

  implicit def streamToResponse[F[_] : Effect, A, CT <: String](implicit
    e: Encode.Aux[A, CT],
    w: Witness.Aux[CT],
    toEffect: ToEffect[Future, F]
  ): ToResponse.Aux[Stream[F, A], CT] = {
    mkToResponse[F, A, CT](delimiter = None)
  }

  protected def mkToResponse[F[_] : Effect, A, CT <: String](delimiter: Option[Buf])(implicit
    e: Encode.Aux[A, CT],
    w: Witness.Aux[CT],
    toEffect: ToEffect[Future, F]
  ): ToResponse.Aux[Stream[F, A], CT] = {
    ToResponse.instance[Stream[F, A], CT]((stream, cs) => {
      val response = Response()
      response.setChunked(true)
      response.contentType = w.value
      val writer = response.writer
      val effect = stream
        .map(e.apply(_, cs))
        .evalMap(buf => toEffect(writer.write(delimiter match {
          case Some(d) => buf.concat(d)
          case _ => buf
        })))
        .onFinalize(Effect[F].defer(toEffect(writer.close())))
        .compile
        .drain

      Effect[F].toIO(effect).unsafeRunAsyncAndForget()
      response
    })
  }

}