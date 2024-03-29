// Copyright 2014,2015,2016 the original author or authors. All rights reserved.
// site: http://www.ganshane.com
package stark.rpc.internal

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}

import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.GeneratedMessage.GeneratedExtension
import stark.rpc.config.RpcBindSupport
import stark.rpc.protocol.CommandProto.{BaseCommand, CommandStatus}
import stark.rpc.services._
import stark.utils.services.{LoggerSupport, MonadException, StarkUtils}
import org.apache.tapestry5.ioc.annotations.EagerLoad
import org.apache.tapestry5.ioc.services.RegistryShutdownHub
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory

import scala.util.control.NonFatal

/**
 * rpc server based on netty
 */
@EagerLoad
class NettyRpcServerImpl(rpcBindSupport: RpcBindSupport,
                         messageHandler: RpcServerMessageHandler,
                         listener: RpcServerListener,
                         registry: ExtensionRegistry)
  extends RpcServer
  with NettyProtobufPipelineSupport
  with ServerChannelManagerSupport
  with LoggerSupport {
  //一个主IO，2个worker
  val ioThread = rpcBindSupport.rpc.ioThread
  val workerThread = rpcBindSupport.rpc.workerThread
  val executor = Executors.newFixedThreadPool(ioThread + workerThread + 2, new ThreadFactory {
    private val seq = new AtomicInteger(0)

    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName("rpc-server-%s".format(seq.incrementAndGet()))
      thread.setDaemon(true)

      thread
    }
  })
  private val channels = new DefaultChannelGroup("rpc-server")
  private var channelFactory: NioServerSocketChannelFactory = _
  private var bootstrap: ServerBootstrap = _

  /**
   * 启动对象实例
   */
  //@PostConstruct
  //因为需要在最后启动RPC Server
  def start(hub: RegistryShutdownHub) {

    channelFactory = new NioServerSocketChannelFactory(executor, executor, workerThread)
    bootstrap = new ServerBootstrap(channelFactory)
    bootstrap.setOption("child.tcpNoDelay", true)
    bootstrap.setOption("child.keepAlive", true)
    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      def getPipeline: ChannelPipeline = {
        val pipeline = Channels.pipeline()
        initChannelManager(pipeline)
        InitPipeline(pipeline,rpcBindSupport.rpc.maxFrameLength)
        //业务逻辑处理
        pipeline.addLast("handler", new CommandServerHandler)
        pipeline
      }
    })
    openOnce()
    listener.afterStart()

  }

  private def openOnce(): Channel = {
    try {
      val bindTuple = StarkUtils.parseBind(rpcBindSupport.rpc.bind)
      val address = new InetSocketAddress("0.0.0.0", bindTuple._2)
      val channel = bootstrap.bind(address)
      channels.add(channel)
      channel
    } catch {
      case NonFatal(e) =>
        shutdown()
        throw MonadException.wrap(e)
    }
  }

  def registryShutdownListener(hub: RegistryShutdownHub): Unit = {
    hub.addRegistryWillShutdownListener(new Runnable {
      override def run(): Unit = shutdown()
    })
  }

  /**
   * 关闭对象
   */
  def shutdown() {
    info("closing rpc server ...")
    closeAllChannels()
    channels.close().awaitUninterruptibly()
    channelFactory.releaseExternalResources()
    StarkUtils.shutdownExecutor(executor, "rpc server executor service")
    listener.afterStop()
  }

  override protected def extentionRegistry: ExtensionRegistry = registry

  class CommandServerHandler extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
      val command = e.getMessage.asInstanceOf[BaseCommand]
      val result = messageHandler.handle(command, new ServerResponse(e.getChannel))
      if (!result) {
        error("message not handled {}", command.toString)
        ctx.getChannel.close()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent): Unit = {
      e.getCause match {
        case me: MonadException =>
          error("server exception,client:{} msg:{}", e.getChannel.getRemoteAddress, me.toString)
        case other =>
          error("server exception,client:" + e.getChannel.getRemoteAddress, other)
      }

      //服务器上发生异常，则关闭此channel
      e.getChannel.close()
    }
  }

}

class ServerResponse(channel: Channel)
  extends CommandResponse
  with ProtobufCommandHelper {

  override def writeMessage[T](baseCommand: BaseCommand, extension: GeneratedExtension[BaseCommand, T], value: T) = {
    channel.write(wrap(baseCommand.getTaskId, extension, value))
  }

  override def writeErrorMessage[T](commandRequest: BaseCommand, message: String): ChannelFuture = {
    val command = BaseCommand.newBuilder().setTaskId(commandRequest.getTaskId)
      .setStatus(CommandStatus.FAIL)
      .setMsg(message)
      .build()

    channel.write(command)
  }
}
