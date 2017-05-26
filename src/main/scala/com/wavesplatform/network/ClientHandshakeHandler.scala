package com.wavesplatform.network

import java.io.IOException
import java.net.InetSocketAddress
import java.util
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, TimeUnit}

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.ReplayingDecoder
import io.netty.util.concurrent.ScheduledFuture
import scorex.network.peer.PeerDatabase
import scorex.utils.ScorexLogging

class HandshakeDecoder extends ReplayingDecoder[Void] with ScorexLogging {
  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]) = {
    out.add(Handshake.decode(in))
  }
}

object HandshakeDecoder {
  val Name = "handshake-decoder"
}

case object HandshakeTimeoutExpired

class HandshakeTimeoutHandler extends ChannelInboundHandlerAdapter with ScorexLogging {
  private var timeout: Option[ScheduledFuture[_]] = None

  private def cancelTimeout(): Unit = timeout.foreach(_.cancel(true))

  override def channelActive(ctx: ChannelHandlerContext) = {
    log.debug(s"Scheduling handshake timeout for ${ctx.channel().id().asShortText()}")
    timeout = Some(ctx.channel().eventLoop().schedule((() => {
      log.debug(s"FIRE TIMEOUT ${ctx.channel().id().asShortText()}")
      ctx.fireChannelRead(HandshakeTimeoutExpired)
    }): Runnable, 10, TimeUnit.SECONDS))

    super.channelActive(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext) = {
    cancelTimeout()
    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = msg match {
    case hs: Handshake =>
      cancelTimeout()
      super.channelRead(ctx, hs)
    case other =>
      super.channelRead(ctx, other)
  }
}

@Sharable
class ClientHandshakeHandler(
    handshake: Handshake,
    peerDatabase: PeerDatabase,
    establishedConnections: ConcurrentMap[Channel, PeerInfo]) extends ChannelInboundHandlerAdapter with ScorexLogging {

  private val connections = new ConcurrentHashMap[PeerKey, Channel]

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = msg match {
    case HandshakeTimeoutExpired => ctx.channel().close()
    case incomingHandshake: Handshake =>
      log.debug(s"${ctx.channel().id().asShortText()}: Received handshake $incomingHandshake")
      val channelRemoteAddress = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress]
      establishedConnections.put(ctx.channel(), PeerInfo(
          channelRemoteAddress,
          incomingHandshake.declaredAddress,
          incomingHandshake.applicationName,
          incomingHandshake.applicationVersion,
          incomingHandshake.nodeName,
          incomingHandshake.nodeNonce))

      val remoteAddress = incomingHandshake.declaredAddress.getOrElse(channelRemoteAddress).getAddress
      val key = PeerKey(remoteAddress, incomingHandshake.nodeNonce)

      ctx.channel().closeFuture().addListener { (chf: ChannelFuture) =>
        connections.remove(key, chf.channel())
      }

      if (connections.putIfAbsent(key, ctx.channel()) != null) {
        log.debug(s"${ctx.channel().id().asShortText()}: Already connected to peer, disconnecting")
        ctx.close()
      } else {
        ctx.writeAndFlush(handshake.encode(ctx.alloc().buffer()))
        ctx.pipeline().remove(classOf[HandshakeDecoder])
        ctx.pipeline().remove(classOf[HandshakeTimeoutHandler])
        ctx.pipeline().remove(this)
      }
    case other =>
      log.debug(s"${ctx.channel().id().asShortText()}: Unexpected message $other while waiting for handshake")
  }


  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) = cause match {
    case ioe: IOException =>
      val hostname = ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress].getHostName
      log.warn(s"${ctx.channel().id().asShortText()}: Unexpected error while waiting for handshake, blacklisting $hostname", ioe)
      peerDatabase.blacklistHost(hostname)
      ctx.close()
    case _ => super.exceptionCaught(ctx, cause)
  }

  override def channelInactive(ctx: ChannelHandlerContext) = establishedConnections.remove(ctx.channel())
}

object ClientHandshakeHandler {
  val Name = "handshake-handler"
}