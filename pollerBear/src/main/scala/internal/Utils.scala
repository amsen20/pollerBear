package pollerBear
package internal

import scala.collection.mutable.ArrayBuffer
import scala.scalanative.libc.string._
import scala.scalanative.runtime
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._

private[pollerBear] object Utils {

  def toPtr(a: AnyRef): Ptr[Byte] =
    runtime.fromRawPtr(Intrinsics.castObjectToRawPtr(a))

  def fromPtr[A](ptr: Ptr[Byte]): A =
    Intrinsics.castRawPtrToObject(runtime.toRawPtr(ptr)).asInstanceOf[A]

  def appendBufferToArrayBuffer(
      buffer: Ptr[Byte],
      arrayBuffer: ArrayBuffer[Byte],
      size: Int
  ): Unit = {
    val array = new Array[Byte](size)
    memcpy(array.at(0), buffer, Size.intToSize(size).toUSize)
    arrayBuffer ++= array
  }

}
